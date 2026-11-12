#!/usr/bin/env python3
"""Unit tests for scripts/generate_cell_width.py.

Run with: python3 -m unittest discover -s scripts/tests

The tests use small data lines, not the Unicode data files, so they need no network.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from generate_cell_width import (  # noqa: E402
    parse_ranges,
    render,
    to_ranges,
    version_of,
    width,
)

MARKS = {0x0600, 0x110BD}


class WidthRuleTests(unittest.TestCase):
    def test_marks_and_format_characters_have_width_zero(self):
        self.assertEqual(width(0x0301, "Mn", "A", MARKS), 0)
        self.assertEqual(width(0x20DD, "Me", "N", MARKS), 0)
        self.assertEqual(width(0x200D, "Cf", "N", MARKS), 0)

    def test_the_extra_zero_width_code_points_have_width_zero(self):
        for code_point, category, east_asian in (
            (0x200B, "Cf", "N"),
            (0x1160, "Lo", "N"),
            (0x11A8, "Lo", "N"),
            (0xD7B0, "Lo", "N"),
            (0x3164, "Lo", "W"),
            (0xFFA0, "Lo", "H"),
        ):
            self.assertEqual(width(code_point, category, east_asian, MARKS), 0, hex(code_point))

    def test_the_format_characters_that_glibc_draws_have_width_one(self):
        for code_point in (0x00AD, 0x0600, 0x110BD, 0xFFF9, 0xFFFB, 0x13430, 0x1343F):
            self.assertEqual(width(code_point, "Cf", "N", MARKS), 1, hex(code_point))

    def test_wide_and_fullwidth_code_points_have_width_two(self):
        self.assertEqual(width(0x4E00, "Lo", "W", MARKS), 2)
        self.assertEqual(width(0xFF21, "Lu", "F", MARKS), 2)
        self.assertEqual(width(0x3248, "No", "A", MARKS), 2)

    def test_regional_indicators_and_unassigned_code_points_have_width_one(self):
        self.assertEqual(width(0x1F1E6, "So", "W", MARKS), 1)
        self.assertEqual(width(0x2FFFD, "Cn", "W", MARKS), 1)
        self.assertEqual(width(0x0378, "Cn", "N", MARKS), 1)

    def test_other_code_points_have_width_one(self):
        self.assertEqual(width(0x0041, "Lu", "Na", MARKS), 1)
        self.assertEqual(width(0x00B7, "Po", "A", MARKS), 1)
        self.assertEqual(width(0xE000, "Co", "A", MARKS), 1)


class DataTests(unittest.TestCase):
    def test_parse_ranges_reads_single_code_points_and_ranges(self):
        lines = ["# comment", "", "0300..036F    ; Mn # COMBINING", "00AD          ; Cf # SOFT HYPHEN"]
        self.assertEqual(parse_ranges(lines), [(0x300, 0x36F, "Mn"), (0xAD, 0xAD, "Cf")])

    def test_version_of_reads_the_first_line(self):
        self.assertEqual(version_of(["# EastAsianWidth-18.0.0.txt"], "EastAsianWidth.txt"), "18.0.0")
        with self.assertRaises(ValueError):
            version_of(["# Something else"], "EastAsianWidth.txt")

    def test_to_ranges_merges_neighbors(self):
        self.assertEqual(to_ranges([1, 2, 3, 7, 9, 10]), [(1, 3), (7, 7), (9, 10)])
        self.assertEqual(to_ranges([]), [])

    def test_render_writes_the_version_the_notice_and_the_pairs(self):
        kotlin = render("18.0.0", [(0x300, 0x36F)], [(0x1100, 0x115F)])
        self.assertIn('const val UNICODE_VERSION: String = "18.0.0"', kotlin)
        self.assertIn("UNICODE LICENSE V3", kotlin)
        self.assertIn("0x300, 0x36F,", kotlin)
        self.assertIn("0x1100, 0x115F,", kotlin)
        self.assertTrue(all(len(line) <= 120 for line in kotlin.splitlines()))


if __name__ == "__main__":
    unittest.main()
