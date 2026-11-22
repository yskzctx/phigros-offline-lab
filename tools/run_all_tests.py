#!/usr/bin/env python3
"""Run every host-side check for the offline build and the LSPosed module.

The project has no single build system: the offline APK is assembled from a base
APK, the module has its own build_module.py, and neither runs the host regression
tests. This script runs everything that can be verified without a device, so a
release is never prepared on the strength of "it compiled once".

Usage:
    python tools/run_all_tests.py            # run everything available locally
    python tools/run_all_tests.py --quick    # skip the NDK/JDK cross builds
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULE_REPO = REPO.parent / 'phigros-phira-lsposed-lab'
MODULE = MODULE_REPO / 'modules' / 'phigros'

# C regression tests: (name, source, extra args)
C_TESTS = [
    ('goal_rules', 'tests/goal_rules_test.c', []),
    ('goal_guard177', 'tests/goal_guard177_test.c', []),
    ('native_dispatch', 'tests/native_dispatch_test.c', ['-pthread']),
    ('guard_result_regression', 'tests/guard_result_regression.c', ['-pthread']),
    ('skin_scale', 'tests/skin_scale_test.c', ['-lm']),
]
MODULE_C_TESTS = [
    ('cloud_guard', 'modules/phigros/tests/cloud_guard_test.c', []),
    ('attachment_order', 'modules/phigros/tests/attachment-order-test.c', []),
    ('phira_guard', 'modules/phira/tests/guard_rescue_test.c', ['-I', 'modules/phira/native']),
]

C_FLAGS = ['-O2', '-std=c11', '-Wall', '-Wextra', '-Werror',
           '-Wno-unused-function', '-Wno-unused-variable']


def find_tool(*candidates: str) -> str | None:
    for candidate in candidates:
        path = shutil.which(candidate)
        if path:
            return path
    return None


def run(cmd: list[str], cwd: Path) -> tuple[int, str]:
    completed = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                               errors='replace')
    return completed.returncode, (completed.stdout + completed.stderr).strip()


def c_suite(compiler: str, root: Path, tests, label: str) -> tuple[int, int]:
    passed = failed = 0
    for name, source, extra in tests:
        exe = Path(os.environ.get('TEMP', '/tmp')) / f'dsh-{label}-{name}.exe'
        code, output = run([compiler, *C_FLAGS, str(root / source), '-o', str(exe), *extra], root)
        if code != 0:
            print(f'  FAIL {label}/{name}: compile')
            print(f'       {output.splitlines()[0] if output else ""}')
            failed += 1
            continue
        code, output = run([str(exe)], root)
        if code == 0:
            print(f'  PASS {label}/{name}')
            passed += 1
        else:
            print(f'  FAIL {label}/{name}: {output}')
            failed += 1
    return passed, failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--quick', action='store_true', help='skip JDK/NDK cross builds')
    args = parser.parse_args()

    total_pass = total_fail = 0

    print('== source synchronisation ==')
    code, output = run([sys.executable, 'tools/sync_sources.py', '--check'], REPO)
    if code == 0:
        print('  PASS all generated copies match canonical sources')
        total_pass += 1
    else:
        print('  FAIL source copies are stale:')
        for line in output.splitlines()[:10]:
            print(f'       {line}')
        total_fail += 1

    print('== profile files are well-formed ==')
    profiles = sorted((REPO / 'profiles').glob('*.json'))
    if not profiles:
        print('  FAIL no profiles found')
        total_fail += 1
    else:
        import json as _json
        bad = []
        for path in profiles:
            try:
                data = _json.loads(path.read_text(encoding='utf-8'))
            except Exception as error:
                bad.append(f'{path.name}: {error}')
                continue
            for key in ('profileVersion', 'versionCode', 'versionName', 'il2cppSha256', 'hooks'):
                if key not in data:
                    bad.append(f'{path.name}: missing {key}')
            # An incomplete profile must say why, or a reader could mistake an empty
            # hook table for "this version needs no hooks".
            if not data.get('complete') and not data.get('blockedReason'):
                bad.append(f'{path.name}: incomplete without blockedReason')
            # A complete profile must be usable by the hook engine: every hook needs
            # the 16-byte prologue it matches on. A recovered profile also needs
            # recoveredFrom, since that is what ties it back to an audited build -
            # profiles generated from the C headers are the audited build and have no
            # such field.
            if data.get('complete'):
                recovered = 'recoveryMethod' in data
                for name, hook in (data.get('hooks') or {}).items():
                    required = ['rva', 'prefix'] + (['recoveredFrom'] if recovered else [])
                    for field in required:
                        if field not in hook:
                            bad.append(f'{path.name}: {name} missing {field}')
                    prefix = hook.get('prefix', '')
                    if len(prefix) != 32:
                        bad.append(f'{path.name}: {name} prefix is {len(prefix)} hex chars, expected 32')
                values = [int(h['rva']) for h in (data.get('hooks') or {}).values() if 'rva' in h]
                if len(values) != len(set(values)):
                    bad.append(f'{path.name}: duplicate rva values')
        if bad:
            print(f'  FAIL {len(bad)} problem(s)')
            for problem in bad[:5]:
                print(f'       {problem}')
            total_fail += 1
        else:
            print(f'  PASS {len(profiles)} profile(s): ' + ', '.join(p.stem for p in profiles))
            total_pass += 1

    print('== profile matches C headers ==')
    code, output = run([sys.executable, 'tools/gen_profile.py',
                        '--version-code', '157', '--version-name', '4.0.1',
                        '--il2cpp-sha256', '511e15b9324c171b585ae954e87266437b5de439289918331d1afce9c2b88433',
                        '--il2cpp-bytes', '71706832',
                        '--out', 'profiles/157.json', '--check'], REPO)
    print(f'  {"PASS" if code == 0 else "FAIL"} profiles/157.json')
    print(f'       {output.splitlines()[-1] if output else ""}')
    total_pass += 1 if code == 0 else 0
    total_fail += 0 if code == 0 else 1

    # The headers are what the native code compiles in, so a profile that disagrees
    # with them is a profile that does not describe the shipped binary.
    print('== C headers match the 157 profile ==')
    code, output = run([sys.executable, 'tools/gen_hooks_header.py',
                        '--profile', 'profiles/157.json', '--check'], REPO)
    print(f'  {"PASS" if code == 0 else "FAIL"} src/expected-hooks.h, src/skin-hooks.h')
    if code:
        for line in output.splitlines():
            if line.startswith('FAIL'):
                print(f'       {line}')
    total_pass += 1 if code == 0 else 0
    total_fail += 0 if code == 0 else 1

    compiler = find_tool('gcc', 'cc', 'clang')
    print('== C regression tests (offline) ==')
    if compiler:
        p, f = c_suite(compiler, REPO, C_TESTS, 'offline')
        total_pass += p
        total_fail += f
    else:
        print('  SKIP no C compiler on PATH')
    print('== C regression tests (module) ==')
    if compiler and MODULE_REPO.is_dir():
        p, f = c_suite(compiler, MODULE_REPO, MODULE_C_TESTS, 'module')
        total_pass += p
        total_fail += f
    else:
        print('  SKIP module repo or compiler unavailable')

    if args.quick:
        print('== JDK/NDK builds ==')
        print('  SKIP (--quick)')
    else:
        javac = find_tool('javac')
        android_jar = Path(r'C:\Users\yskzc\Android\Sdk\platforms\android-36\android.jar')
        print('== Java compile (offline) ==')
        if javac and android_jar.is_file():
            out = Path(os.environ.get('TEMP', '/tmp')) / 'dsh-offline-java'
            shutil.rmtree(out, ignore_errors=True)
            sources = sorted(str(p) for p in (REPO / 'src' / 'com' / 'phigros' / 'offline').glob('*.java'))
            code, output = run([javac, '--release', '8', '-encoding', 'UTF-8',
                                '-cp', str(android_jar), '-d', str(out), *sources], REPO)
            print(f'  {"PASS" if code == 0 else "FAIL"} {len(sources)} offline sources')
            if code:
                print(f'       {output.splitlines()[0] if output else ""}')
            total_pass += 1 if code == 0 else 0
            total_fail += 0 if code == 0 else 1

            print('== Java regression (guard + forced result) ==')
            java_tests = [
                ('GuardAndForcedResultTest', 'com.phigros.offline.GuardAndForcedResultTest'),
                ('SkinStyleTest', 'com.phigros.offline.SkinStyleTest'),
                ('ForcedResultRksTest', 'com.phigros.offline.ForcedResultRksTest'),
            ]
            test_out = Path(os.environ.get('TEMP', '/tmp')) / 'dsh-offline-javatest'
            shutil.rmtree(test_out, ignore_errors=True)
            test_sources = [str(REPO / 'tests' / f'{name}.java') for name, _cls in java_tests]
            code, output = run([javac, '--release', '8', '-encoding', 'UTF-8',
                                '-cp', str(android_jar), '-d', str(test_out),
                                *sources, *test_sources], REPO)
            if code == 0:
                env = dict(os.environ, JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8')
                for name, cls in java_tests:
                    completed = subprocess.run(['java', '-cp', str(test_out), cls],
                                               capture_output=True, text=True, env=env, errors='replace')
                    ok = completed.returncode == 0
                    last = completed.stdout.strip().splitlines()[-1] if completed.stdout.strip() else ''
                    print(f'  {"PASS" if ok else "FAIL"} {name}')
                    if last:
                        print(f'       {last}')
                    total_pass += 1 if ok else 0
                    total_fail += 0 if ok else 1
            else:
                print('  FAIL compile')
                for line in output.splitlines()[:3]:
                    print(f'       {line}')
                total_fail += 1
        else:
            print('  SKIP javac or android.jar unavailable')

        print('== Java compile (module) ==')
        api_jar = Path(r'C:\Users\yskzc\Android\api-82.jar')
        if javac and android_jar.is_file() and api_jar.is_file() and MODULE.is_dir():
            out = Path(os.environ.get('TEMP', '/tmp')) / 'dsh-module-java'
            shutil.rmtree(out, ignore_errors=True)
            sources = sorted(str(p) for p in (MODULE / 'src').rglob('*.java'))
            code, output = run([javac, '--release', '8', '-encoding', 'UTF-8',
                                '-cp', f'{android_jar}{os.pathsep}{api_jar}',
                                '-d', str(out), *sources], MODULE_REPO)
            print(f'  {"PASS" if code == 0 else "FAIL"} {len(sources)} module sources')
            if code:
                for line in output.splitlines()[:3]:
                    print(f'       {line}')
            total_pass += 1 if code == 0 else 0
            total_fail += 0 if code == 0 else 1
        else:
            print('  SKIP javac / android.jar / api-82.jar unavailable')

        print('== NDK native compile (module) ==')
        ndk_clang = Path(r'C:\Users\yskzc\Android\Sdk\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd')
        if ndk_clang.is_file() and MODULE.is_dir():
            for label, source in (('libphigros_lsp.so', MODULE / 'native' / 'lsp-entry.c'),
                                  ('libphira.so', MODULE_REPO / 'modules' / 'phira' / 'native' / 'module.c')):
                target = Path(os.environ.get('TEMP', '/tmp')) / f'dsh-{label}'
                code, output = run([str(ndk_clang), '-shared', '-fPIC', '-O2', '-std=c11',
                                    '-Wall', '-Wextra', '-Werror', '-Wno-unused-function',
                                    '-Wno-unused-variable', '-o', str(target),
                                    str(source), '-llog', '-ldl', '-lm'], MODULE_REPO)
                print(f'  {"PASS" if code == 0 else "FAIL"} {label}')
                if code:
                    for line in output.splitlines()[:3]:
                        print(f'       {line}')
                total_pass += 1 if code == 0 else 0
                total_fail += 0 if code == 0 else 1
        else:
            print('  SKIP NDK clang unavailable')

        # These two guard boundaries that fail silently: a module missing a native
        # library it loads still installs and starts, and a wrong network permission
        # only shows up as "sync failed" on a device. Both are cheap to check here.
        print('== module ships every native library it loads ==')
        script = MODULE / 'tests' / 'native_libraries_test.py'
        if script.is_file():
            code, output = run([sys.executable, str(script)], MODULE_REPO)
            print(f'  {"PASS" if code == 0 else "FAIL"} native library completeness')
            for line in output.splitlines():
                if line.startswith(('PASS', 'FAIL', 'SKIP')) and not line.startswith(('PASS all', 'PASS offline')):
                    print(f'       {line}')
            total_pass += 1 if code == 0 else 0
            total_fail += 0 if code == 0 else 1
        else:
            print('  SKIP test script missing')

        print('== network boundary: offline blocked, modules inherit host ==')
        script = MODULE / 'tests' / 'network_boundary_test.py'
        if script.is_file():
            code, output = run([sys.executable, str(script)], MODULE_REPO)
            print(f'  {"PASS" if code == 0 else "FAIL"} permission split')
            for line in output.splitlines():
                if line.startswith(('FAIL', 'SKIP')):
                    print(f'       {line}')
            total_pass += 1 if code == 0 else 0
            total_fail += 0 if code == 0 else 1
        else:
            print('  SKIP test script missing')

    print()
    print(f'{total_pass} passed, {total_fail} failed')
    return 1 if total_fail else 0


if __name__ == '__main__':
    sys.exit(main())
