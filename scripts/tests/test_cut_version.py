#!/usr/bin/env python3
"""Unit tests for the compute core of scripts/cut_version.py.

Run with: python3 -m unittest discover -s scripts/tests   (or python3 scripts/tests/test_cut_version.py)

These exercise the pure functions only — no git, no network — so they run anywhere.
"""

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cut_version import (  # noqa: E402
    compute_version,
    iso_week_year,
    next_count,
    parse_tag,
    tag_name,
    version_code,
)


class ParseTagTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(parse_tag("v26.29.1"), (26, 29, 1))
        self.assertEqual(parse_tag("v26.1.1"), (26, 1, 1))
        self.assertEqual(parse_tag("v26.53.12"), (26, 53, 12))

    def test_strips_whitespace(self):
        self.assertEqual(parse_tag("  v26.29.1\n"), (26, 29, 1))

    def test_two_digit_year(self):
        # The app uses a 2-digit year: jpackage's MSI validator caps the major
        # component at 255, so a 4-digit year cannot be packaged for Windows.
        self.assertEqual(parse_tag("v26.29.1"), (26, 29, 1))

    def test_invalid(self):
        self.assertIsNone(parse_tag("26.29.1"))          # missing v prefix
        self.assertIsNone(parse_tag("v26.29"))           # too few components
        self.assertIsNone(parse_tag("v26.29.1.4"))       # too many components
        self.assertIsNone(parse_tag("v26.29.1-dev+3-g")) # dev suffix
        self.assertIsNone(parse_tag("vfoo"))
        self.assertIsNone(parse_tag(""))
        self.assertIsNone(parse_tag("garbage"))


class IsoWeekYearTests(unittest.TestCase):
    def test_regular_date(self):
        # 2026-07-23 is a Thursday in ISO week 30 of week-year 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 7, 23)), (26, 30))

    def test_first_week_of_year(self):
        # 2026-01-01 is a Thursday -> ISO week 1 of 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 1, 1)), (26, 1))

    def test_late_december_belongs_to_next_iso_year_is_actually_same_year(self):
        # 2026-12-28 (Monday) — its Thursday is 2026-12-31, so ISO week 53 of 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 12, 28)), (26, 53))

    def test_new_year_belongs_to_previous_iso_week_year(self):
        # THE divergence case: 2027-01-01 is a Friday whose ISO week (Mon 2026-12-28..
        # Sun 2027-01-03) has its Thursday in 2026 -> ISO week-year 2026, week 53.
        # Using the calendar year (%Y=2027) here would emit a LOWER version than a tag
        # cut days earlier — exactly why we pair the ISO week with the ISO week-year.
        year, week = iso_week_year(datetime.date(2027, 1, 1))
        self.assertEqual((year, week), (26, 53))
        self.assertNotEqual(year, 27)

    def test_early_january_before_calendar_year(self):
        # 2025-12-29 (Monday) starts ISO week 1 of 2026 (its Thursday is 2026-01-01).
        self.assertEqual(iso_week_year(datetime.date(2025, 12, 29)), (26, 1))


class NextCountTests(unittest.TestCase):
    def test_no_tags_starts_at_one(self):
        self.assertEqual(next_count([], 26, 30), 1)

    def test_increments_from_max(self):
        self.assertEqual(next_count(["v26.30.1"], 26, 30), 2)
        self.assertEqual(next_count(["v26.30.1", "v26.30.2"], 26, 30), 3)

    def test_uses_max_not_length(self):
        # Non-contiguous history (a deleted tag) still advances past the max.
        self.assertEqual(next_count(["v26.30.1", "v26.30.4"], 26, 30), 5)

    def test_ignores_other_weeks(self):
        self.assertEqual(next_count(["v26.29.5"], 26, 30), 1)

    def test_ignores_other_years(self):
        self.assertEqual(next_count(["v25.30.9"], 26, 30), 1)

    def test_count_above_nine_is_numeric_not_lexicographic(self):
        # "10" sorts before "9" as a string; max() over ints must still pick 10.
        self.assertEqual(next_count(["v26.30.9", "v26.30.10"], 26, 30), 11)

    def test_ignores_malformed(self):
        tags = ["v26.30.1", "garbage", "v26.30.x", "v26.30.2", ""]
        self.assertEqual(next_count(tags, 26, 30), 3)


class ComputeVersionTests(unittest.TestCase):
    def test_first_release_of_week(self):
        self.assertEqual(compute_version([], datetime.date(2026, 7, 23)), "26.30.1")

    def test_second_release_of_week(self):
        self.assertEqual(compute_version(["v26.30.1"], datetime.date(2026, 7, 23)), "26.30.2")

    def test_week_one_no_zero_padding(self):
        self.assertEqual(compute_version([], datetime.date(2026, 1, 1)), "26.1.1")


class VersionCodeTests(unittest.TestCase):
    def test_plan_example(self):
        self.assertEqual(version_code("26.29.1"), 2629001)

    def test_new_year_is_monotonic(self):
        # 27.1.1 must exceed the highest possible 26 code.
        self.assertEqual(version_code("27.1.1"), 2701001)
        self.assertEqual(version_code("26.52.9"), 2652009)
        self.assertGreater(version_code("27.1.1"), version_code("26.52.9"))

    def test_week_53(self):
        self.assertEqual(version_code("26.53.1"), 2653001)

    def test_within_week_monotonic(self):
        self.assertGreater(version_code("26.30.2"), version_code("26.30.1"))

    def test_across_weeks_monotonic(self):
        self.assertGreater(version_code("26.30.1"), version_code("26.29.9"))

    def test_ignores_dev_metadata(self):
        self.assertEqual(version_code("26.29.1-dev+7-gabc123"), 2629001)

    def test_dev_no_tag_floors_to_one(self):
        # 0.0.0-dev would compute negative; Android requires versionCode >= 1.
        self.assertEqual(version_code("0.0.0-dev"), 1)
        self.assertEqual(version_code("0.0.0-dev+gabc123"), 1)

    def test_four_digit_year_is_value_identical(self):
        # `% 100` replaced `- 2000`; both yield 26 for 2026, so codes already
        # published under the original 4-digit plan would not regress.
        self.assertEqual(version_code("2026.29.1"), version_code("26.29.1"))

    def test_stays_under_play_ceiling(self):
        # Play's hard ceiling is 2_100_000_000; the scheme stays far below it.
        self.assertLess(version_code("99.53.999"), 2_100_000_000)


class TagNameTests(unittest.TestCase):
    def test_prefix(self):
        self.assertEqual(tag_name("26.29.1"), "v26.29.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
