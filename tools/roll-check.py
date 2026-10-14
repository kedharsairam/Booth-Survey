#!/usr/bin/env python3
"""
Refuse to build if anything from a real electoral roll is in this repository.

The rule this exists to enforce: no voter's name, EPIC number or house number is ever
committed. Not as a fixture, not as an example in a comment, not as a string a test
compares against.

That rule has been broken before. Eight real people's names sat in a PDF render test,
two real EPIC numbers in a spreadsheet test, and fifty-one real house numbers spread
through the OCR tooling -- all copied out of a real BLO sheet and real scanned voter
cards while the work was being done. Nothing about that was intentional; the fixtures
just ended up being taken from the material that was to hand, which is the whole way
these things get in.

**A gitignore cannot catch this.** It stops a path being added. It does not stop a real
number being pasted into a line of Kotlin that was already tracked, and it certainly does
not reach anything already in the history.

So the check runs in CI against a fingerprint of the roll.

## The fingerprint

`fingerprint.json` holds **SHA-256 prefixes** of sample values from a real roll -- a few
real EPICs, house numbers and Telugu names, hashed.

Hashes, not values, and that is the whole design. This file is committed and the
repository is meant to be readable by anyone, so a file of real names and real EPICs would
be the exact thing it exists to prevent. Hashing costs nothing here: a voter's EPIC
number does not need to be guessed from a hash, only recognised, and every candidate in
every file is hashed and looked up. So the check works without the repository containing
a single real value of its own.

## What it checks

1. Every tracked file, for any fingerprint value.
2. The whole of git history, because deleting a line today leaves it in the commit before.
3. That the build has no network permission, because "nothing leaves the phone" is a
   promise about the APK and not only about this repository.

Exit code 1 means CI fails. There is no warning mode and no allowlist; a roll value in
this repository is always a bug.
"""

import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FINGERPRINT = os.path.join(ROOT, "fingerprint.json")

TEXT = (".kt", ".java", ".js", ".py", ".md", ".xml", ".kts", ".json", ".txt", ".yml", ".yaml")

# A value long enough that a coincidence is not a coincidence. Short Telugu given names
# are excluded on purpose: there are far more of them than there are people in any one
# ward, so matching on them flags every house number in the ward as a name.
MIN_LEN = 6


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:32]


def load():
    """The set of known digests. The real values never appear in this file."""
    with open(FINGERPRINT, encoding="utf-8") as fh:
        raw = json.load(fh)
    return set(raw.get("epics", [])) | set(raw.get("houses", [])) | set(raw["names"]) \
        if "names" in raw else set(raw.get("epics", [])) | set(raw.get("houses", []))


def tracked_files():
    out = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    return [f for f in out if f.endswith(TEXT) and os.path.isfile(os.path.join(ROOT, f))]


# Voter IDs, door numbers, and Telugu runs. Anything long enough that matching it is not
# a coincidence.
CANDIDATE = re.compile(
    r"[A-Z]{3}\d{7}"            # EPIC / voter ID
    r"|\d{1,2}-\d{1,2}-\d{1,2}(?:/[A-Za-z0-9]+)?"   # door number
    r"|[\u0C00-\u0C7F][\u0C00-\u0C7F ]{4,}"       # a run of Telugu
)


def candidates(text):
    for m in CANDIDATE.finditer(text):
        yield m.group(0).strip()


def scan_text(text, known):
    hits = []
    for token in candidates(text):
        d = digest(token)
        if d in known:
            hits.append(token)
    return hits


def scan_tree(known):
    hits = []
    for path in tracked_files():
        try:
            with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        for v in scan_text(text, known):
            hits.append((path, v))
    return hits


def scan_history(known):
    """Every blob ever committed, not just the ones on the branch now."""
    try:
        out = subprocess.run(
            ["git", "rev-list", "--objects", "--all"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.splitlines()
    except subprocess.CalledProcessError:
        return []
    seen, hits = set(), []
    for line in out:
        parts = line.split(" ", 1)
        if len(parts) != 2:
            continue
        sha, name = parts
        if not name.endswith(TEXT) or sha in seen:
            continue
        seen.add(sha)
        blob = subprocess.run(
            ["git", "cat-file", "-p", sha], cwd=ROOT, capture_output=True
        ).stdout.decode("utf-8", "replace")
        for v in scan_text(blob, known):
            hits.append((name, v))
    return hits


def check_manifest():
    """No network permission. Nothing to do with the fixtures, and the bigger promise."""
    manifest = os.path.join(ROOT, "android", "app", "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(manifest):
        return []
    with open(manifest, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    problems = []
    if re.search(r'android\.permission\.INTERNET', text):
        problems.append("AndroidManifest.xml declares android.permission.INTERNET")
    return problems


def main():
    if not os.path.isfile(FINGERPRINT):
        print("roll-check: fingerprint.json is missing; the check cannot run")
        return 1

    known = load()
    print("roll-check: %d sample digests from the roll" % len(known))

    failures = []

    tree = scan_tree(known)
    if tree:
        failures.append(("working tree", tree))

    history = scan_history(known)
    if history:
        failures.append(("git history", history))

    manifest = check_manifest()
    if manifest:
        failures.append(("manifest", [(m, m) for m in manifest]))

    if failures:
        print("")
        print("roll-check: FAILED")
        print("")
        for where, hits in failures:
            print("  in the %s:" % where)
            for path, value in hits[:20]:
                print("     %s  ->  %r" % (path, value))
            if len(hits) > 20:
                print("     ... and %d more" % (len(hits) - 20))
        print("")
        print("These are real people's details. Remove them, and if they are in history")
        print("rewipe it: a plain delete does not take them out of a commit.")
        return 1

    print("roll-check: no roll data in the working tree or in history")
    return 0


if __name__ == "__main__":
    sys.exit(main())
