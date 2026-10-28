#!/usr/bin/env python3
"""Generates the cell width tables of :core:mosh from the Unicode Character Database.

The client must give each code point the same width as the libc of the host, because mosh-server computes the
widths with its own libc. The rules are the width rules of docs/plans/03-mosh-udp-backbone.md (decision 3), plus
the exceptions that a black-box test found. That test ran a probe in mosh-server 1.4.0 with glibc 2.43. The probe
wrote each code point and asked for the cursor position (CSI 6n).

- Width 0: General_Category Mn, Me, and Cf. Also U+200B, the jamo U+1160..U+11FF and U+D7B0..U+D7FF, and the
  Hangul fillers U+3164 and U+FFA0.
- Width 1, not 0: U+00AD, the Prepended_Concatenation_Mark code points, U+FFF9..U+FFFB, and U+13430..U+1343F.
- Width 2: East_Asian_Width W or F, and U+3248..U+324F.
- Width 1: all other code points. Also the regional indicators U+1F1E6..U+1F1FF and each code point that is not
  assigned (General_Category Cn).

Get the data files of one Unicode version into one directory:

    v=18.0.0
    curl -fsSO https://www.unicode.org/Public/$v/ucd/EastAsianWidth.txt
    curl -fsSO https://www.unicode.org/Public/$v/ucd/PropList.txt
    curl -fsSO https://www.unicode.org/Public/$v/ucd/extracted/DerivedGeneralCategory.txt

Then run, from the root of the repository:

    python3 scripts/generate_cell_width.py --ucd <directory>

The Unicode License v3 applies to the data files. The generated file holds its notice.
"""

import argparse
import os
import re
import sys

MAX_CODE_POINT = 0x10FFFF

DATA_FILES = ("EastAsianWidth.txt", "DerivedGeneralCategory.txt", "PropList.txt")

OUTPUT = os.path.join(
    "core", "mosh", "src", "commonMain", "kotlin", "com", "toxicbakery", "app", "bison", "mosh", "term",
    "CellWidthTables.kt",
)

# Code points with width 0 that are not Mn, Me, or Cf.
EXTRA_ZERO = ((0x200B, 0x200B), (0x1160, 0x11FF), (0xD7B0, 0xD7FF), (0x3164, 0x3164), (0xFFA0, 0xFFA0))

# Cf code points with width 1 in glibc 2.43, other than the Prepended_Concatenation_Mark code points.
CF_WIDTH_ONE = ((0x00AD, 0x00AD), (0xFFF9, 0xFFFB), (0x13430, 0x1343F))

# Code points with width 2 that do not have East_Asian_Width W or F.
EXTRA_WIDE = ((0x3248, 0x324F),)

# Code points with width 1, also when a later Unicode version changes their properties.
ALWAYS_ONE = ((0x1F1E6, 0x1F1FF),)

UNICODE_LICENSE = """\
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
"""


def parse_ranges(lines):
    """Returns (first, last, value) for each data line of a UCD file. The value is the second field."""
    entries = []
    for line in lines:
        data = line.split("#", 1)[0].strip()
        if not data:
            continue
        fields = [field.strip() for field in data.split(";")]
        if len(fields) < 2:
            raise ValueError("A data line has less than two fields: %r" % line)
        first, _, last = fields[0].partition("..")
        entries.append((int(first, 16), int(last or first, 16), fields[1]))
    return entries


def version_of(lines, name):
    """Returns the Unicode version from the first line of a UCD file, for example "18.0.0"."""
    stem = re.escape(name[: -len(".txt")])
    match = re.match(r"#\s*%s-(\d+\.\d+\.\d+)\.txt" % stem, lines[0]) if lines else None
    if not match:
        raise ValueError("%s has no version in its first line." % name)
    return match.group(1)


def fill(entries, wanted=None):
    """Returns a dict from each code point to its value. With wanted, keeps only the entries with that value."""
    values = {}
    for first, last, value in entries:
        if wanted is not None and value != wanted:
            continue
        for code_point in range(first, last + 1):
            values[code_point] = value
    return values


def in_ranges(code_point, ranges):
    return any(first <= code_point <= last for first, last in ranges)


def width(code_point, category, east_asian_width, concatenation_marks):
    """Returns the cell width of one code point by the rules of the module docstring."""
    if category == "Cn" or in_ranges(code_point, ALWAYS_ONE):
        return 1
    if code_point in concatenation_marks or in_ranges(code_point, CF_WIDTH_ONE):
        return 1
    if category in ("Mn", "Me", "Cf") or in_ranges(code_point, EXTRA_ZERO):
        return 0
    if east_asian_width in ("W", "F") or in_ranges(code_point, EXTRA_WIDE):
        return 2
    return 1


def to_ranges(code_points):
    """Merges sorted code points into (first, last) ranges."""
    ranges = []
    for code_point in code_points:
        if ranges and ranges[-1][1] == code_point - 1:
            ranges[-1] = (ranges[-1][0], code_point)
        else:
            ranges.append((code_point, code_point))
    return ranges


def tables(categories, east_asian_widths, concatenation_marks):
    """Returns the ranges of width 0 and of width 2."""
    zero = []
    wide = []
    for code_point in range(MAX_CODE_POINT + 1):
        value = width(
            code_point,
            categories.get(code_point, "Cn"),
            east_asian_widths.get(code_point, "N"),
            concatenation_marks,
        )
        if value == 0:
            zero.append(code_point)
        elif value == 2:
            wide.append(code_point)
    return to_ranges(zero), to_ranges(wide)


def kotlin_array(name, ranges, doc):
    values = ["0x%X" % value for first, last in ranges for value in (first, last)]
    lines = []
    line = "       "
    for value in values:
        if len(line) + len(value) + 2 > 120:
            lines.append(line.rstrip())
            line = "       "
        line += " %s," % value
    lines.append(line.rstrip())
    body = "\n".join(lines)
    return "    /** %s */\n    val %s: IntArray = intArrayOf(\n%s\n    )\n" % (doc, name, body)


def render(version, zero, wide):
    notice = "\n".join(("// " + line).rstrip() for line in UNICODE_LICENSE.splitlines())
    return (
        "// Generated by scripts/generate_cell_width.py from the Unicode Character Database %s. Do not edit.\n"
        "//\n"
        "// The data files have this notice:\n"
        "//\n"
        "%s\n"
        "\n"
        "package com.toxicbakery.app.bison.mosh.term\n"
        "\n"
        "/** The ranges of [CellWidth]. Each pair of values is the first and the last code point of one range. */\n"
        "internal object CellWidthTables {\n"
        "\n"
        "    /** The version of the Unicode data. */\n"
        "    const val UNICODE_VERSION: String = \"%s\"\n"
        "\n"
        "%s\n"
        "%s"
        "}\n"
    ) % (
        version,
        notice,
        version,
        kotlin_array("ZERO", zero, "The code points of width 0, in %d ranges." % len(zero)),
        kotlin_array("WIDE", wide, "The code points of width 2, in %d ranges." % len(wide)),
    )


def read_lines(directory, name):
    for candidate in (os.path.join(directory, name), os.path.join(directory, "extracted", name)):
        if os.path.isfile(candidate):
            with open(candidate, encoding="utf-8") as data:
                return data.read().splitlines()
    raise FileNotFoundError("%s is not in %s." % (name, directory))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ucd", required=True, help="the directory with the Unicode data files")
    parser.add_argument("--output", default=None, help="the Kotlin file to write (default: the file in :core:mosh)")
    args = parser.parse_args(argv)

    lines = {name: read_lines(args.ucd, name) for name in DATA_FILES}
    versions = {version_of(lines[name], name) for name in DATA_FILES}
    if len(versions) != 1:
        raise SystemExit("The data files have different versions: %s" % sorted(versions))
    version = versions.pop()

    categories = fill(parse_ranges(lines["DerivedGeneralCategory.txt"]))
    east_asian_widths = fill(parse_ranges(lines["EastAsianWidth.txt"]))
    concatenation_marks = set(fill(parse_ranges(lines["PropList.txt"]), "Prepended_Concatenation_Mark"))
    if not concatenation_marks:
        raise SystemExit("PropList.txt has no Prepended_Concatenation_Mark code points.")
    zero, wide = tables(categories, east_asian_widths, concatenation_marks)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    output = args.output or os.path.join(root, OUTPUT)
    with open(output, "w", encoding="utf-8", newline="\n") as kotlin:
        kotlin.write(render(version, zero, wide))
    print("Unicode %s: %d ranges of width 0, %d ranges of width 2 -> %s" % (version, len(zero), len(wide), output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
