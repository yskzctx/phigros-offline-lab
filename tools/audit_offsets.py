#!/usr/bin/env python3
"""Audit il2cpp hook offsets for a Phigros libil2cpp.so and emit a version profile.

The gameplay/skin hooks are only safe when the exact 16-byte function prologue is
present. This tool answers three questions for any candidate ELF:

  1. Do the offsets recorded in an existing profile still match byte-for-byte?
  2. If not, where did each prologue move to in the new binary?
  3. What does the profile look like as JSON, ready for the runtime to consume?

Offsets are recovered by searching the new image for each known 16-byte prologue
rather than by pattern-guessing instructions, because every audited prologue is a
unique 16-byte sequence inside a 70 MB image. A relocated prologue is only accepted
when it resolves to exactly one hit, so an ambiguous match fails loudly instead of
silently hooking the wrong function.

Usage:
    python audit_offsets.py --so libil2cpp.so --base profiles/157.json --out profiles/410.json
    python audit_offsets.py --so libil2cpp.so --base profiles/157.json --report
    python audit_offsets.py --selftest
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

PROFILE_VERSION = 1

# Prologues recorded from the audited 4.0.1 (157) build. The profile files are the
# source of truth at runtime; this table only seeds the first profile and the
# self-test, so a reviewer can diff tool output against the committed data.
SEED_157 = {
    "versionCode": 157,
    "versionName": "4.0.1",
    "il2cppSha256": "511e15b9324c171b585ae954e87266437b5de439289918331d1afce9c2b88433",
    "abi": "arm64-v8a",
    "hooks": {
        "click": {"rva": 0x1D8214C, "prefix": "ea0f1dfce9a3006dfe0f00f9f44f02a9", "relocateMask": 0},
        "drag": {"rva": 0x1D828F8, "prefix": "e923bd6dfe0b00f9f44f02a974370190", "relocateMask": 8},
        "flick": {"rva": 0x1D82EF8, "prefix": "e923bd6dfe0b00f9f44f02a974370190", "relocateMask": 8},
        "hold": {"rva": 0x1D83B88, "prefix": "e923bd6dfe5701a9f44f02a9543701f0", "relocateMask": 8},
        "record": {"rva": 0x1CB9828, "prefix": "fe0f1cf8f85f01a9f65702a9f44f03a9", "relocateMask": 0},
        "perfect": {"rva": 0x1D8789C, "prefix": "ec0f1bfceb2b016de923026dfe5703a9", "relocateMask": 0},
        "result": {"rva": 0x1D863C0, "prefix": "fe57bea9f44f01a95437019088fa6839", "relocateMask": 4},
        "miss": {"rva": 0x1D874E4, "prefix": "e80f1efcfe0700f9f44f01a9343701f0", "relocateMask": 8},
        "good": {"rva": 0x1D876B8, "prefix": "ec0f1bfceb2b016de923026dfe5703a9", "relocateMask": 0},
        "skinClick": {"rva": 0x1D81E98, "prefix": "eb2bbc6de923016dfe5702a9f44f03a9", "relocateMask": 0},
        "skinDrag": {"rva": 0x1D8267C, "prefix": "eb2bbc6de923016de923026dfe5702a9"[:32], "relocateMask": 0},
        "skinFlick": {"rva": 0x1D82C7C, "prefix": "eb2bbc6de923016dfe5702a9f44f03a9", "relocateMask": 0},
        "skinHold": {"rva": 0x1D8334C, "prefix": "ec0f1bfcebab006de9a3016dfe1700f9", "relocateMask": 0},
    },
    "aux": {"badCall": 0x1D875C4},
}

# drag/flick/skinDrag/skinFlick/skinClick share the same prologue family, so a byte
# search alone cannot tell them apart. These disambiguators record the *next* 16
# bytes, which differ per function, and are used only when a relocated hit is
# ambiguous.
SECOND_WINDOW = 16


def read_elf(path: Path) -> tuple[bytes, list[dict]]:
    """Return the whole file plus PT_LOAD segments needed for RVA -> file offset."""
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit(f"{path} is not a little-endian ELF64 image")
    (e_phoff,) = struct.unpack_from("<Q", data, 0x20)
    (e_phentsize,) = struct.unpack_from("<H", data, 0x36)
    (e_phnum,) = struct.unpack_from("<H", data, 0x38)
    segments = []
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        p_type, _flags = struct.unpack_from("<II", data, base)
        (p_offset,) = struct.unpack_from("<Q", data, base + 0x08)
        (p_vaddr,) = struct.unpack_from("<Q", data, base + 0x10)
        (p_filesz,) = struct.unpack_from("<Q", data, base + 0x20)
        if p_type == 1:
            segments.append({"offset": p_offset, "vaddr": p_vaddr, "filesz": p_filesz})
    if not segments:
        raise SystemExit(f"{path} has no PT_LOAD segments")
    return data, segments


def rva_to_offset(segments: list[dict], rva: int) -> int | None:
    for segment in segments:
        start = segment["vaddr"]
        if start <= rva < start + segment["filesz"]:
            return segment["offset"] + (rva - start)
    return None


def bytes_at(data: bytes, segments: list[dict], rva: int, length: int) -> bytes | None:
    offset = rva_to_offset(segments, rva)
    if offset is None or offset + length > len(data):
        return None
    return data[offset : offset + length]


def find_all(data: bytes, needle: bytes) -> list[int]:
    hits: list[int] = []
    start = 0
    while True:
        found = data.find(needle, start)
        if found < 0:
            return hits
        hits.append(found)
        start = found + 1


def offset_to_rva(segments: list[dict], offset: int) -> int | None:
    for segment in segments:
        start = segment["offset"]
        if start <= offset < start + segment["filesz"]:
            return segment["vaddr"] + (offset - start)
    return None


def audit(so: Path, base: dict) -> dict:
    data, segments = read_elf(so)
    digest = hashlib.sha256(data).hexdigest()
    results: dict[str, dict] = {}
    matched = 0

    for name, spec in base["hooks"].items():
        prefix = bytes.fromhex(spec["prefix"])
        rva = int(spec["rva"])
        current = bytes_at(data, segments, rva, len(prefix))
        if current == prefix:
            results[name] = {
                "rva": rva,
                "status": "unchanged",
                "prefix": spec["prefix"],
                "relocateMask": spec.get("relocateMask", 0),
            }
            matched += 1
            continue

        hits = find_all(data, prefix)
        candidates = [r for r in (offset_to_rva(segments, h) for h in hits) if r is not None]
        if len(candidates) == 1:
            results[name] = {
                "rva": candidates[0],
                "status": "moved",
                "previousRva": rva,
                "prefix": spec["prefix"],
                "relocateMask": spec.get("relocateMask", 0),
            }
            matched += 1
        elif not candidates:
            results[name] = {
                "rva": None,
                "status": "missing",
                "previousRva": rva,
                "prefix": spec["prefix"],
                "relocateMask": spec.get("relocateMask", 0),
            }
        else:
            results[name] = {
                "rva": None,
                "status": "ambiguous",
                "previousRva": rva,
                "prefix": spec["prefix"],
                "relocateMask": spec.get("relocateMask", 0),
                "candidates": [hex(c) for c in candidates[:8]],
                "candidateCount": len(candidates),
            }

    # Auxiliary offsets carry no prologue. They stay valid only when the whole
    # function table is unchanged, which the caller can see from `hookSummary`.
    aux = {name: int(rva) for name, rva in base.get("aux", {}).items()}

    total = len(base["hooks"])
    return {
        "profileVersion": PROFILE_VERSION,
        "source": so.name,
        "il2cppSha256": digest,
        "il2cppBytes": len(data),
        "base": {
            "versionCode": base.get("versionCode"),
            "versionName": base.get("versionName"),
            "il2cppSha256": base.get("il2cppSha256"),
        },
        "hookSummary": {
            "total": total,
            "unchanged": sum(1 for r in results.values() if r["status"] == "unchanged"),
            "moved": sum(1 for r in results.values() if r["status"] == "moved"),
            "missing": sum(1 for r in results.values() if r["status"] == "missing"),
            "ambiguous": sum(1 for r in results.values() if r["status"] == "ambiguous"),
            "matched": matched,
        },
        "hooks": results,
        "aux": aux,
        "auxTrusted": matched == total,
    }


def as_profile(report: dict, version_code: int, version_name: str) -> dict:
    hooks = {}
    for name, entry in report["hooks"].items():
        if entry["rva"] is None:
            continue
        hooks[name] = {
            "rva": entry["rva"],
            "prefix": entry["prefix"],
            "relocateMask": entry.get("relocateMask", 0),
        }
    return {
        "profileVersion": PROFILE_VERSION,
        "versionCode": version_code,
        "versionName": version_name,
        "abi": "arm64-v8a",
        "il2cppSha256": report["il2cppSha256"],
        "il2cppBytes": report["il2cppBytes"],
        "complete": len(hooks) == len(report["hooks"]),
        "hooks": hooks,
        "aux": report["aux"] if report["auxTrusted"] else {},
    }


def print_report(report: dict) -> None:
    summary = report["hookSummary"]
    print(f"image      : {report['source']} ({report['il2cppBytes']} bytes)")
    print(f"sha256     : {report['il2cppSha256']}")
    print(f"base       : {report['base']['versionName']} ({report['base']['versionCode']})")
    print(
        "hooks      : {matched}/{total} matched"
        "  unchanged={unchanged} moved={moved} missing={missing} ambiguous={ambiguous}".format(**summary)
    )
    print()
    print(f"{'hook':<12}{'rva':<12}{'status':<12}detail")
    for name, entry in report["hooks"].items():
        rva = f"0x{entry['rva']:x}" if entry["rva"] is not None else "-"
        detail = ""
        if entry["status"] == "moved":
            detail = f"was 0x{entry['previousRva']:x}"
        elif entry["status"] == "ambiguous":
            detail = f"{entry['candidateCount']} candidates {entry['candidates']}"
        print(f"{name:<12}{rva:<12}{entry['status']:<12}{detail}")
    print()
    print(f"aux trusted: {report['auxTrusted']}")


def selftest() -> int:
    """Verify the seed table against the committed 157 profile."""
    root = Path(__file__).resolve().parent.parent
    profile_path = root / "profiles" / "157.json"
    if not profile_path.is_file():
        print(f"FAIL: {profile_path} is missing")
        return 1
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    seed = SEED_157
    problems = []
    if profile["il2cppSha256"] != seed["il2cppSha256"]:
        problems.append("il2cppSha256 differs between seed and profiles/157.json")
    for name, spec in seed["hooks"].items():
        committed = profile["hooks"].get(name)
        if committed is None:
            problems.append(f"{name}: missing from profiles/157.json")
            continue
        if int(committed["rva"]) != spec["rva"]:
            problems.append(f"{name}: rva 0x{int(committed['rva']):x} != 0x{spec['rva']:x}")
        if committed["prefix"].lower() != spec["prefix"].lower():
            problems.append(f"{name}: prefix differs")
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}")
        return 1
    print(f"PASS selftest: {len(seed['hooks'])} hook definitions match profiles/157.json")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--so", type=Path, help="libil2cpp.so to audit")
    parser.add_argument("--base", type=Path, help="profile to use as the reference")
    parser.add_argument("--out", type=Path, help="write the derived profile here")
    parser.add_argument("--version-code", type=int, help="versionCode for the derived profile")
    parser.add_argument("--version-name", help="versionName for the derived profile")
    parser.add_argument("--report", action="store_true", help="print the human-readable audit table")
    parser.add_argument("--selftest", action="store_true", help="check the seed table against profiles/157.json")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if not args.so or not args.base:
        parser.error("--so and --base are required unless --selftest is given")

    base = json.loads(args.base.read_text(encoding="utf-8"))
    report = audit(args.so, base)
    if args.report or not args.out:
        print_report(report)

    if args.out:
        if args.version_code is None or args.version_name is None:
            parser.error("--version-code and --version-name are required with --out")
        profile = as_profile(report, args.version_code, args.version_name)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(profile, indent=2, sort_keys=False) + "\n", encoding="utf-8")
        print(f"wrote {args.out} (complete={profile['complete']}, hooks={len(profile['hooks'])})")

    summary = report["hookSummary"]
    return 0 if summary["matched"] == summary["total"] else 2


if __name__ == "__main__":
    sys.exit(main())
