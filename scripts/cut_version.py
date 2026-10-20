#!/usr/bin/env python3
"""Compute (and optionally create) the next app release tag.

The app's version is calendar-derived and the git tag is the single source of truth.
The scheme is:

    <2-digit ISO week-year>.<ISO week 1-53>.<release count this week, >= 1>

e.g. the first release cut in ISO week 29 of 2026 is ``26.29.1``; the second that
week is ``26.29.2``. The count is ``max(existing tags this ISO week) + 1`` — no
counter is committed to the repo.

The year is 2-digit because jpackage's MSI validator caps the *major* version component
at 255, so a 4-digit ``2026.x.y`` cannot be packaged for Windows at all.

This is the compute core the ``release.yml`` workflow runs. The pure functions
(:func:`iso_week_year`, :func:`parse_tag`, :func:`next_count`, :func:`compute_version`,
:func:`version_code`) take their inputs explicitly so they are trivially unit-testable
without touching git; see ``tests/test_cut_version.py``.

Usage::

    cut_version.py --dry-run        # print the next version + tag, touch nothing
    cut_version.py --print          # print just the next version to stdout
    cut_version.py --tag [--ref R]  # create annotated tag v<version> at R (default HEAD)

``--tag`` only creates the *local* annotated tag and emits ``version=``/``tag=`` to
``$GITHUB_OUTPUT``; pushing the tag and dispatching the build is left to the workflow
so credentials/permissions stay there.
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import subprocess
import sys

# A release tag: v<year>.<week>.<count>. Components are plain integers (no zero-padding),
# matching the emitted `year.week.count`. Anything else (dev suffixes, malformed) is
# rejected by returning None so it never contributes to the count.
TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")

TAG_PREFIX = "v"


def iso_week_year(today: datetime.date) -> tuple[int, int]:
    """Return ``(2-digit ISO week-year, ISO week)`` for *today*.

    Uses the ISO-8601 week-year (``date.isocalendar()`` / ``date +%G``), which is
    paired with the ISO week and is NOT the calendar year in late December / early
    January. For example 2025-12-29 belongs to ISO week-year **2026** (week 1); using
    the calendar year (``%Y``) there would emit ``25.1`` straight after ``25.52`` — a
    *lower* version after a higher one.

    The year is truncated to 2 digits (``2026`` -> ``26``) to stay under jpackage's
    MSI major-version cap of 255; see the module docstring.
    """
    iso = today.isocalendar()
    # isocalendar() yields (year, week, weekday) — a namedtuple on 3.9+, a plain tuple
    # on older versions; index access works on both.
    return iso[0] % 100, iso[1]


def parse_tag(tag: str) -> tuple[int, int, int] | None:
    """Parse ``v<year>.<week>.<count>`` into an int triple, or None if it doesn't match."""
    match = TAG_RE.match(tag.strip())
    if match is None:
        return None
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def next_count(tags: "list[str] | tuple[str, ...]", year: int, week: int) -> int:
    """The next release count for (*year*, *week*): ``max(existing) + 1``, else 1.

    Malformed tags and tags from other weeks/years are ignored.
    """
    counts = [
        count
        for parsed in (parse_tag(tag) for tag in tags)
        if parsed is not None
        for (tag_year, tag_week, count) in [parsed]
        if tag_year == year and tag_week == week
    ]
    return max(counts, default=0) + 1


def compute_version(tags: "list[str] | tuple[str, ...]", today: datetime.date) -> str:
    """The next version string, e.g. ``26.29.1``."""
    year, week = iso_week_year(today)
    count = next_count(tags, year, week)
    return f"{year}.{week}.{count}"


def version_code(version: str) -> int:
    """Monotonic integer for the stores (Play versionCode / App Store build number).

        code = (year % 100) * 100000 + week * 1000 + count

    ``26.29.1`` -> ``2629001``. Any ``-dev+...`` metadata is ignored. Floors at 1
    because Android requires ``versionCode >= 1``.

    ``% 100`` (rather than ``- 2000``) keeps the arithmetic correct for the 2-digit year
    the scheme uses, and is value-identical for a 4-digit input — ``2026 - 2000`` and
    ``2026 % 100`` are both ``26``.

    Mirrors ``projectVersionCode()`` in ``composite`` build-logic; keep the two in sync.
    """
    base = version.split("-", 1)[0]
    parts = base.split(".")

    def component(index: int) -> int:
        try:
            return int(parts[index])
        except (IndexError, ValueError):
            return 0

    year, week, count = component(0), component(1), component(2)
    code = (year % 100) * 100000 + week * 1000 + count
    return max(code, 1)


def tag_name(version: str) -> str:
    return f"{TAG_PREFIX}{version}"


# --------------------------------------------------------------------------------------
# git / CI glue (not exercised by the unit tests)
# --------------------------------------------------------------------------------------


def _run_git(args: "list[str]") -> str:
    """Run git, surfacing its stderr on failure.

    ``capture_output`` swallows git's own diagnostics, so without this a bad ``--ref``
    would surface only as a bare CalledProcessError traceback in the Actions log. Fail
    with the same SystemExit idiom :func:`create_tag` uses so CI shows one clear line.
    """
    try:
        result = subprocess.run(
            ["git", *args],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        raise SystemExit("git is not installed or not on PATH")
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip() or f"exit status {error.returncode}"
        raise SystemExit(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def existing_tags() -> "list[str]":
    """All release-shaped tags in the repo (``v*``)."""
    out = _run_git(["tag", "--list", f"{TAG_PREFIX}*"])
    return [line.strip() for line in out.splitlines() if line.strip()]


def create_tag(version: str, ref: str) -> str:
    """Create an annotated tag ``v<version>`` at *ref*. Returns the tag name."""
    tag = tag_name(version)
    if tag in existing_tags():
        raise SystemExit(f"refusing to create {tag}: it already exists")
    _run_git(["tag", "-a", tag, "-m", f"Release {tag}", ref])
    return tag


def _emit_github_output(version: str, tag: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as handle:
        handle.write(f"version={version}\n")
        handle.write(f"tag={tag}\n")
        handle.write(f"version_code={version_code(version)}\n")


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="print the next version/tag and exit; touch nothing")
    mode.add_argument("--print", dest="print_only", action="store_true", help="print only the next version to stdout")
    mode.add_argument("--tag", action="store_true", help="create the annotated tag locally (does not push)")
    parser.add_argument("--ref", default="HEAD", help="commit-ish to tag with --tag (default: HEAD)")
    args = parser.parse_args(argv)

    tags = existing_tags()
    today = datetime.datetime.now(datetime.timezone.utc).date()
    version = compute_version(tags, today)
    tag = tag_name(version)

    if args.print_only:
        print(version)
        return 0

    if args.dry_run:
        print(f"Next version: {version}")
        print(f"Next tag:     {tag}")
        print(f"versionCode:  {version_code(version)}")
        return 0

    if args.tag:
        created = create_tag(version, args.ref)
        _emit_github_output(version, created)
        print(f"Created tag {created} at {args.ref}")
        return 0

    # Default: behave like --dry-run so a bare invocation is always safe.
    print(f"Next version: {version}")
    print(f"Next tag:     {tag}")
    print(f"versionCode:  {version_code(version)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
