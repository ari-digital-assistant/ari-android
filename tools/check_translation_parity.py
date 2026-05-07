#!/usr/bin/env python3
"""
Validate that every `app/src/main/res/values-{locale}/strings.xml`
declares the same set of `<string name="...">` keys as the canonical
`values/strings.xml`. Missing keys silently fall back to English at
runtime, producing mixed-language chrome — see
[CONTRIBUTING.md](../CONTRIBUTING.md#ci-lint-no-half-translated-locales)
for the rationale.

Exit codes:
  0 — every locale file has the same key set as canonical
  1 — at least one locale file is missing keys or has stray ones
  2 — script could not parse the input (bad XML, missing files)
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def extract_keys(strings_xml: Path) -> set[str]:
    """Return the set of `<string name="...">` keys in `strings_xml`."""
    try:
        tree = ET.parse(strings_xml)
    except ET.ParseError as e:
        print(f"FAIL: {strings_xml}: malformed XML — {e}", file=sys.stderr)
        sys.exit(2)
    root = tree.getroot()
    return {el.get("name") for el in root.findall("string") if el.get("name")}


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    res_dir = repo_root / "app" / "src" / "main" / "res"
    canonical = res_dir / "values" / "strings.xml"

    if not canonical.is_file():
        print(f"FAIL: canonical strings file not found: {canonical}", file=sys.stderr)
        return 2

    canonical_keys = extract_keys(canonical)
    print(f"canonical {canonical.relative_to(repo_root)}: {len(canonical_keys)} keys")

    locale_dirs = sorted(d for d in res_dir.iterdir() if d.name.startswith("values-"))
    if not locale_dirs:
        print("no locale directories — nothing to check")
        return 0

    failures = 0
    for locale_dir in locale_dirs:
        locale_strings = locale_dir / "strings.xml"
        if not locale_strings.is_file():
            # Locale-specific resource dirs that don't have a strings.xml
            # (e.g. values-night/ for the dark theme) are fine — skip.
            continue
        locale_keys = extract_keys(locale_strings)
        missing = canonical_keys - locale_keys
        stray = locale_keys - canonical_keys
        rel = locale_strings.relative_to(repo_root)
        if not missing and not stray:
            print(f"OK   {rel}: {len(locale_keys)} keys, parity")
            continue
        failures += 1
        print(f"FAIL {rel}: {len(locale_keys)} keys", file=sys.stderr)
        if missing:
            print(
                f"  missing {len(missing)} key(s) present in canonical:",
                file=sys.stderr,
            )
            for k in sorted(missing):
                print(f"    - {k}", file=sys.stderr)
        if stray:
            print(
                f"  stray {len(stray)} key(s) absent from canonical:",
                file=sys.stderr,
            )
            for k in sorted(stray):
                print(f"    + {k}", file=sys.stderr)

    if failures:
        print(
            f"\n{failures} locale(s) failed parity check. "
            "See CONTRIBUTING.md for the no-half-translated-locales rule.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
