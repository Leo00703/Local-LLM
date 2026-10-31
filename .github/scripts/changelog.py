#!/usr/bin/env python3
"""Render the in-app changelog (Changelog.kt) as Markdown.

    python .github/scripts/changelog.py                     # whole CHANGELOG.md to stdout
    python .github/scripts/changelog.py --version 1.9.139   # release notes for one version

Changelog.kt stays the single source of truth; nothing else is edited by hand.
"""
import argparse
import re
import sys
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/java/com/druk/lmplayground/settings/Changelog.kt"
EMOJI = {"NEW": "✨", "IMPROVED": "⚡", "FIX": "🐛", "DESIGN": "🎨"}

ENTRY = re.compile(r'ChangelogEntry\(\s*"([^"]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*listOf\(')
CHANGE = re.compile(r'Change\(\s*ChangeType\.(\w+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')


def unescape(text: str) -> str:
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


def parse(source: str):
    starts = list(ENTRY.finditer(source))
    for i, entry in enumerate(starts):
        end = starts[i + 1].start() if i + 1 < len(starts) else len(source)
        changes = [(m.group(1), unescape(m.group(2))) for m in CHANGE.finditer(source, entry.end(), end)]
        yield entry.group(1), unescape(entry.group(2)), changes


def bullets(changes) -> str:
    return "\n".join(f"- {EMOJI.get(kind, '•')} {text}" for kind, text in changes)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", help="print only this version, without a heading")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    entries = list(parse(SOURCE.read_text(encoding="utf-8")))
    if args.version:
        for version, title, changes in entries:
            if version == args.version:
                print(f"**{title}**\n")
                print(bullets(changes))
                return 0
        print(f"No changelog entry for {args.version}", file=sys.stderr)
        return 1
    print("# Changelog\n")
    print("Generated from the in-app changelog (`Changelog.kt`) by `.github/scripts/changelog.py`; do not edit by hand.\n")
    print("\n".join(f"## {v} · {t}\n\n{bullets(c)}\n" for v, t, c in entries))
    return 0


if __name__ == "__main__":
    sys.exit(main())
