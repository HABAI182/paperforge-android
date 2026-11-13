"""Recover, per chunk, the start state a stored realistic_scenes set was generated with, so the set can be rebuilt
byte-exactly with `realistic_scenes.py --regen-table` and the stored copy can be deleted.

Sets generated before 2026-11-12 depended on page_scenes.HARD carried over between chunks inside a worker
(0, 1 or 2). For every chunk this tries the three values on the chunk's first scene and records the one that
reproduces it, then rebuilds --verify whole chunks (all 100 scenes) and compares them byte for byte.

usage: scene_regen_table.py <set dir> --cases ... --textures <dir> --coco <dir> [--verify 6] [--workers 4]
Writes <set dir>/regen_table.json; exit status 0 only if every chunk matched and every verified chunk is identical.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import random
import sys
from pathlib import Path

import numpy as np

import realistic_scenes as rs


class Sink(dict):
    def flush(self):
        pass


def stored(root, count):
    images = np.memmap(Path(root) / "images.bin", np.uint8, "r", shape=(count, 320, 320, 3))
    masks = np.memmap(Path(root) / "masks.bin", np.uint8, "r", shape=(count, 160, 160))
    return images, masks


def build(job, hard, limit):
    images, masks = Sink(), Sink()
    rs.chunk(job, start_hard=hard, limit=limit, images=images, masks=masks)
    return images, masks


def identify(job):
    """Start states that reproduce the chunk: tried on its first scene, and when more than one fits (the first
    scene did not depend on it), settled on the whole chunk."""
    root, count, first, last = job[:4]
    images, masks = stored(root, count)

    def fits(h, limit):
        im, mk = build(job, h, limit)
        return all((im[i] == images[i]).all() and (mk[i] == masks[i]).all() for i in im)

    found = [h for h in (0, 1, 2) if fits(h, 1)]
    if len(found) > 1:
        found = [h for h in found if fits(h, None)]
    return first, found


def verify(job, hard):
    root, count, first, last = job[:4]
    images, masks = stored(root, count)
    im, mk = build(job, hard, None)
    return first, all((im[i] == images[i]).all() and (mk[i] == masks[i]).all() for i in range(first, last))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", type=Path)
    ap.add_argument("--cases", nargs="+", default=None)
    ap.add_argument("--textures", type=Path, required=True)
    ap.add_argument("--coco", type=Path, required=True)
    ap.add_argument("--verify", type=int, default=6)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    meta = json.loads((args.root / "scenes.json").read_text(encoding="utf-8"))
    count, seed = meta["count"], meta["seed"]
    cases = args.cases or rs.CASES
    paths = [r["texture"] for r in json.loads((args.textures / "manifest.json").read_text(encoding="utf-8"))["records"]]
    jobs = [(str(args.root), count, i, min(i + 100, count), seed, paths, str(args.coco), cases) for i in range(0, count, 100)]
    table, unmatched, ambiguous = {}, [], []
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        for first, found in pool.map(identify, jobs):
            if len(found) == 1 or (found and len(set(found)) == 1):
                table[first] = found[0]
            elif found:  # several start states give the identical chunk: any of them rebuilds it
                table[first] = found[0]
                ambiguous.append(first)
            else:
                unmatched.append(first)
        sample = random.Random(7).sample([j for j in jobs if j[2] in table], min(args.verify, len(table)))
        checks = list(pool.map(verify, sample, [table[j[2]] for j in sample]))
    ok = not unmatched and all(c[1] for c in checks)
    record = {
        "set": args.root.name, "count": count, "seed": seed, "cases": cases,
        "startHard": {str(k): v for k, v in sorted(table.items())}, "unmatchedChunks": unmatched, "chunksIdenticalUnderSeveralStarts": ambiguous,
        "verifiedWholeChunks": [{"first": f, "identical": bool(v)} for f, v in checks],
        "rebuild": f"realistic_scenes.py --textures <public-textures> --coco <val2017> --out <dir> --count {count} --seed {seed}"
                   + (f" --cases {' '.join(cases)}" if args.cases else "") + " --regen-table regen_table.json",
        "exact": ok,
    }
    (args.root / "regen_table.json").write_text(json.dumps(record, indent=1), encoding="utf-8")
    print(json.dumps({k: record[k] for k in ("set", "unmatchedChunks", "verifiedWholeChunks", "exact")}))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
