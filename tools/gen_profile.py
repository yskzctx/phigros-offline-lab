#!/usr/bin/env python3
"""Generate a runtime version profile from the audited C hook headers.

The hook tables live in two places for two different consumers:

  * `src/expected-hooks.h` / `src/skin-hooks.h` are compiled into the native
    library and are the ground truth for what the hook installer expects.
  * `profiles/<version>.json` is what the runtime loader and the audit tools
    read.

Keeping them in sync by hand already went wrong once (the committed
`source-manifest.json` still said candidate 177 while the code was 180). This
script makes the JSON a generated artifact: parse the headers, emit the profile.

Usage:
    python gen_profile.py --version-code 157 --version-name 4.0.1 \
                          --il2cpp-sha256 511e15b9... --il2cpp-bytes 71706832 \
                          --out profiles/157.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HOOK_LINE = re.compile(
    r'\{\s*"([^"]+)"\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*\{([^}]*)\}\s*,\s*(\d+)\s*\}'
)
DEFINE_LINE = re.compile(r'#define\s+(\w+)\s+(0x[0-9a-fA-F]+)')


def parse_hook_table(path: Path) -> dict[str, dict]:
    text = path.read_text(encoding="utf-8")
    hooks: dict[str, dict] = {}
    for match in HOOK_LINE.finditer(text):
        name, rva, body, mask = match.groups()
        raw = bytes(int(token.strip(), 16) for token in body.split(",") if token.strip())
        if len(raw) != 16:
            raise SystemExit(f"{path.name}: hook {name} has {len(raw)} prefix bytes, expected 16")
        hooks[name] = {
            "rva": int(rva, 16),
            "prefix": raw.hex(),
            "relocateMask": int(mask),
        }
    if not hooks:
        raise SystemExit(f"{path.name}: no hook entries found")
    return hooks


def parse_defines(path: Path) -> dict[str, int]:
    text = path.read_text(encoding="utf-8")
    return {name: int(value, 16) for name, value in DEFINE_LINE.findall(text)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", type=Path, default=Path(__file__).resolve().parent.parent / "src")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--il2cpp-sha256", required=True)
    parser.add_argument("--il2cpp-bytes", type=int, required=True)
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="fail instead of writing when --out already differs")
    args = parser.parse_args()

    gameplay = parse_hook_table(args.src / "expected-hooks.h")
    skins = parse_hook_table(args.src / "skin-hooks.h")

    # Skin hooks are keyed by their own names; prefix them so a profile reader can
    # tell a gameplay hook from a skin hook without a second table.
    hooks: dict[str, dict] = dict(gameplay)
    for name, spec in skins.items():
        key = "skin" + name[0].upper() + name[1:]
        hooks[key] = spec

    aux: dict[str, int] = {}
    offline_defines = parse_defines(args.src / "offline.c") if (args.src / "offline.c").is_file() else {}
    if "BAD_CALL_RVA" in offline_defines:
        aux["badCall"] = offline_defines["BAD_CALL_RVA"]

    profile = {
        "profileVersion": 1,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "abi": args.abi,
        "il2cppSha256": args.il2cpp_sha256.lower(),
        "il2cppBytes": args.il2cpp_bytes,
        "complete": True,
        "hooks": hooks,
        "aux": aux,
    }

    rendered = json.dumps(profile, indent=2) + "\n"
    if args.check:
        existing = args.out.read_text(encoding="utf-8") if args.out.is_file() else ""
        if existing != rendered:
            print(f"FAIL: {args.out} is stale; re-run without --check to regenerate", file=sys.stderr)
            return 1
        print(f"PASS: {args.out} matches src/ headers ({len(hooks)} hooks)")
        return 0

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.out}: {len(hooks)} hooks ({len(gameplay)} gameplay + {len(skins)} skin)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
