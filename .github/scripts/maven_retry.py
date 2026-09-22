"""Retry Maven downloads rate-limited by a repository, preserving build failures."""

import re
import subprocess  # nosec B404: fixed Maven commands below, with no shell or user arguments.
import sys
import time


def run_maven(stage):
    """Only run the two fixed Maven commands used by this Linux CI workflow."""
    if stage == "verify":
        return subprocess.run(  # nosec B603: fixed executable and literal arguments; shell disabled.
            ["/usr/bin/mvn", "-U", "--batch-mode", "--no-transfer-progress",
             "-Dstyle.color=never", "clean", "verify"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False,
        )
    if stage == "pmd":
        return subprocess.run(  # nosec B603: fixed executable and literal arguments; shell disabled.
            ["/usr/bin/mvn", "-U", "--batch-mode", "--no-transfer-progress",
             "-Dstyle.color=never", "org.apache.maven.plugins:maven-pmd-plugin:3.26.0:pmd"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False,
        )
    raise ValueError("Expected verify or pmd")


def run(stage):
    """Allow three attempts only when Maven reports a rate-limited transfer."""
    for attempt in range(3):
        result = run_maven(stage)
        print(result.stdout, end="", flush=True)
        if result.returncode == 0:
            return 0
        limited = re.search(
            r"(?m)^\[ERROR\].*Could not transfer artifact.*(?:status code: 429|Too Many Requests)",
            result.stdout,
        )
        if not limited or attempt == 2:
            return result.returncode
        delay = 20 * (attempt + 1)
        print(f"Maven download rate-limited; retrying in {delay} seconds.", flush=True)
        time.sleep(delay)
    return 1


if __name__ == "__main__":
    sys.exit(run(sys.argv[1]))
