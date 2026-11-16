"""Report (and optionally remove) regenerable clutter that git does not see.

Categories, each removed only when named with --apply:
  emulator       %LOCALAPPDATA%/Temp/AndroidEmulator/TMP* overlays older than --emulator-days (a read-only emulator
                 instance writes one set per start and leaves it behind; files a running emulator holds stay locked
                 and are skipped).
  scenes         images.bin/masks.bin in scene folders that carry REGENERATE.md and deleted-bins.sha256 (the exact
                 rebuild command and checksums are kept; the bins are written again on demand).
  worktrees      gitignored build outputs (build, .cxx, .gradle) of git worktrees other than the main checkout.
  distributions  <repo>/build/distributions older than --distribution-days (published releases live on GitHub,
                 test builds in work/test-builds).

Never touched: sources, git history, credentials, original documents and photos, models, regression and truth sets,
Desktop deliveries, toolchains, the active checkout's build caches.

usage: python scripts/workspace_hygiene.py [--work <work dir>] [--apply emulator,scenes,worktrees,distributions]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def size(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += (Path(root) / f).stat().st_size
            except OSError:
                pass
    return total


def gb(n: int) -> str:
    return f"{n / 1024 ** 3:6.2f} GB"


def emulator(days: float) -> list[Path]:
    temp = Path(os.environ.get("LOCALAPPDATA", "")) / "Temp" / "AndroidEmulator"
    cut = time.time() - days * 86400
    return sorted(p for p in temp.glob("TMP*") if p.is_file() and p.stat().st_mtime < cut) if temp.exists() else []


def scenes(work: Path) -> list[Path]:
    out = []
    for marker in work.rglob("REGENERATE.md"):
        folder = marker.parent
        if (folder / "deleted-bins.sha256").exists():
            out += [p for p in (folder / "images.bin", folder / "masks.bin", folder / "corners.bin") if p.exists()]
    return out


def worktrees() -> list[Path]:
    listing = subprocess.run(["git", "-C", str(REPO), "worktree", "list", "--porcelain"], capture_output=True, text=True).stdout
    roots = [Path(line.split(" ", 1)[1]) for line in listing.splitlines() if line.startswith("worktree ")]
    out = []
    for root in roots:
        if root.resolve() == REPO.resolve():
            continue
        for name in ("build", ".cxx", ".gradle"):
            for candidate in [root / name, *root.glob(f"*/{name}")]:
                if candidate.is_dir():
                    ignored = subprocess.run(["git", "-C", str(root), "check-ignore", "-q", str(candidate)]).returncode == 0
                    if ignored:
                        out.append(candidate)
    return out


def distributions(days: float) -> list[Path]:
    folder = REPO / "build" / "distributions"
    cut = time.time() - days * 86400
    return sorted(p for p in folder.rglob("*") if p.is_file() and p.stat().st_mtime < cut) if folder.exists() else []


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, default=REPO.parents[1] / "work")
    parser.add_argument("--apply", default="", help="comma-separated categories to remove")
    parser.add_argument("--emulator-days", type=float, default=1)
    parser.add_argument("--distribution-days", type=float, default=14)
    args = parser.parse_args()
    apply = {c.strip() for c in args.apply.split(",") if c.strip()}
    found = {
        "emulator": emulator(args.emulator_days),
        "scenes": scenes(args.work),
        "worktrees": worktrees(),
        "distributions": distributions(args.distribution_days),
    }
    grand = 0
    for category, paths in found.items():
        total = sum(size(p) for p in paths)
        grand += total
        print(f"{category:14s} {gb(total)}  {len(paths)} item(s){'  [removing]' if category in apply else ''}")
        for p in paths[:8]:
            print(f"    {gb(size(p))}  {p}")
        if len(paths) > 8:
            print(f"    ... and {len(paths) - 8} more")
        if category in apply:
            for p in paths:
                try:
                    shutil.rmtree(p) if p.is_dir() else p.unlink()
                except OSError as e:  # a running emulator's overlays stay locked
                    print(f"    skipped {p}: {e}")
    print(f"{'total':14s} {gb(grand)}")


if __name__ == "__main__":
    main()
