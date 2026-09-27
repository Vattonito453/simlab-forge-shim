#!/usr/bin/env python3
"""Card-name lint: fail when a Java string literal in the shim is a card name.

The shim is a thin adapter (README, "Boundary rule"): which cards matter is
plan data that arrives as JSON, never Java. A card name written as a string
literal is the cheapest way to break that rule by accident, so this lint
reads Forge's own card list AT RUNTIME and fails on any literal equal to a
card name. The comparison is exact, as Forge's own Card.getName() comparisons
are: a case-insensitive one flags the JSON keys "threat", "greed" and "life",
which collide with real card names (Threat, Greed, Life // Death) and mean
nothing about cards here. Nothing from Forge is written
anywhere: no card list is committed, cached or printed, except the offending
literal itself.

Comments are not checked (they may name cards when explaining a measurement);
only string literals are code that could act on a card.

Card names come from Forge's cardsfolder: every "Name:" line of every card
script (each face of a multi-face card), plus "A // B" for a script with two
or more faces, which is how Forge names a split card. The folder may be the
unpacked directory of .txt scripts or the shipped cardsfolder.zip.

Usage (stdlib only; `py` on Windows, `python3` elsewhere):
    python3 tools/lint_card_names.py [--cardsfolder PATH] [--src DIR]

PATH defaults to $FORGE_CARDSFOLDER, else res/cardsfolder next to $FORGE_JAR,
else ~/forge/res/cardsfolder. Exit codes: 0 clean, 1 card-name literals found,
2 no cardsfolder found (nothing was checked).
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def default_cardsfolder() -> Path | None:
    env = os.environ.get("FORGE_CARDSFOLDER")
    if env:
        return Path(env)
    jar = os.environ.get("FORGE_JAR")
    if jar:
        cand = Path(jar).resolve().parent / "res" / "cardsfolder"
        if cand.exists():
            return cand
    cand = Path.home() / "forge" / "res" / "cardsfolder"
    return cand if cand.exists() else None


def _names_from_script(text: str, out: set[str]) -> None:
    faces = []
    for line in text.splitlines():
        if line.startswith("Name:"):
            name = line[5:].strip()
            if name:
                faces.append(name)
    out.update(faces)
    if len(faces) >= 2:
        out.add(" // ".join(faces))


def load_card_names(folder: Path) -> set[str]:
    """Every card name Forge knows. Held in memory only."""
    names: set[str] = set()
    zips = []
    if folder.is_file() and folder.suffix.lower() == ".zip":
        zips = [folder]
    elif folder.is_dir():
        for p in folder.rglob("*.txt"):
            _names_from_script(p.read_text(encoding="utf-8", errors="replace"), names)
        if not names:
            zips = sorted(folder.glob("*.zip"))
    for zp in zips:
        with zipfile.ZipFile(zp) as z:
            for info in z.infolist():
                if info.filename.lower().endswith(".txt"):
                    _names_from_script(z.read(info).decode("utf-8", "replace"), names)
    return names


_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f",
            "s": " ", "0": "\0", "\\": "\\", "'": "'", '"': '"'}


def _unescape(raw: str) -> str:
    out = []
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "u":
                j = i + 1
                while j < len(raw) and raw[j] == "u":
                    j += 1
                try:
                    out.append(chr(int(raw[j:j + 4], 16)))
                    i = j + 4
                    continue
                except ValueError:
                    pass
            out.append(_ESCAPES.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def string_literals(src: str):
    """Yield (line_number, value) for every string literal (and text block)
    in Java source, skipping comments and char literals."""
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            body = src[i + 3:j]
            start = line
            line += src.count("\n", i, j + 3)
            # A text block's content starts after its opening line break.
            text = body.split("\n", 1)[1] if "\n" in body else body
            yield start, _unescape("\n".join(s.strip() for s in text.split("\n")).strip())
            i = j + 3
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            yield line, _unescape(src[i + 1:j])
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'" and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
        else:
            i += 1


def lint(src_dir: Path, names: set[str]) -> list[tuple[Path, int, str]]:
    hits = []
    for path in sorted(src_dir.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for lineno, value in string_literals(text):
            v = value.strip()
            if v and v in names:
                hits.append((path, lineno, value))
    return hits


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--cardsfolder", type=Path, default=None,
                    help="Forge res/cardsfolder (directory or cardsfolder.zip)")
    ap.add_argument("--src", type=Path, default=REPO / "src",
                    help="Java source root to scan (default: the repo's src/)")
    args = ap.parse_args()
    folder = args.cardsfolder or default_cardsfolder()
    if folder is None or not folder.exists():
        print("lint_card_names: no Forge cardsfolder found; set FORGE_CARDSFOLDER "
              "or pass --cardsfolder. Nothing was checked.", file=sys.stderr)
        return 2
    names = load_card_names(folder)
    if not names:
        print(f"lint_card_names: no card scripts under {folder}; nothing was checked.",
              file=sys.stderr)
        return 2
    hits = lint(args.src, names)
    files = sum(1 for _ in args.src.rglob("*.java"))
    if hits:
        for path, lineno, value in hits:
            rel = path.relative_to(args.src.parent) if args.src.parent in path.parents else path
            print(f"{rel}:{lineno}: string literal {value!r} is a card name; "
                  "card knowledge belongs in the plan JSON", file=sys.stderr)
        print(f"lint_card_names: FAILED, {len(hits)} card-name literal(s) "
              f"in {files} Java files", file=sys.stderr)
        return 1
    print(f"lint_card_names: ok ({files} Java files, checked against "
          f"{len(names)} Forge card names)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
