#!/usr/bin/env python3

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def git(repository, *arguments):
    return subprocess.run(
        ["git", "-C", str(repository), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def main():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Manage the malbec ALOS source patches.")
    parser.add_argument("action", choices=("apply", "check", "status", "revert"), default="apply", nargs="?")
    parser.add_argument("--source-root", type=Path, default=directory.parents[4])
    args = parser.parse_args()
    root = args.source_root.resolve()
    manifest = json.loads((directory / "series.json").read_text())
    repository = (root / manifest["repository"]).resolve()
    if not (root / "build/envsetup.sh").is_file() or not repository.is_relative_to(root):
        parser.error("--source-root must point to an Android source checkout")
    result = git(repository, "rev-parse", "--show-toplevel")
    if result.returncode or Path(result.stdout.strip()).resolve() != repository:
        parser.error(f"missing Git repository: {repository}")

    pending = []
    conflicts = []
    for entry in manifest["patches"]:
        patch = (directory / entry["file"]).resolve()
        if not patch.is_relative_to(directory):
            parser.error(f"invalid patch path: {entry['file']}")
        if hashlib.sha256(patch.read_bytes()).hexdigest() != entry["sha256"]:
            parser.error(f"patch checksum mismatch: {entry['file']}")
        forward = git(repository, "apply", "--check", str(patch))
        reverse = git(repository, "apply", "--reverse", "--check", str(patch))
        if forward.returncode == 0 and reverse.returncode != 0:
            state = "ready"
        elif reverse.returncode == 0 and forward.returncode != 0:
            state = "applied"
        else:
            state = "conflict"
            conflicts.append((patch.name, forward.stderr, reverse.stderr))
        print(f"{state:8} {patch.name}")
        if (args.action == "revert" and state == "applied") or (
            args.action in ("apply", "check") and state == "ready"
        ):
            pending.append(str(patch))

    if conflicts:
        for name, forward_error, reverse_error in conflicts:
            print(f"\n{name}:\n{forward_error}{reverse_error}", file=sys.stderr)
        print("No files changed. Resolve the source mismatch before applying.", file=sys.stderr)
        return 1
    if args.action == "status":
        return 0
    direction = ["--reverse"] if args.action == "revert" else []
    if direction:
        pending.reverse()
    if pending:
        result = git(repository, "apply", *direction, "--check", *pending)
        if result.returncode:
            print(result.stderr, file=sys.stderr)
            return 1
        if args.action != "check":
            result = git(repository, "apply", *direction, *pending)
            if result.returncode:
                print(result.stderr, file=sys.stderr)
                return 1
    print("Checks passed." if args.action == "check" else "Source patches are up to date.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
