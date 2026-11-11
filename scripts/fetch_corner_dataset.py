"""Fetch the CC-BY-4.0 Mendeley corner dataset into a private research directory.

Shima Dizaj, A New Image Dataset for Document Corner Localization, version 3,
DOI 10.17632/x3nm4cxr83.3; associated paper DOI 10.1109/MVIP49855.2020.9116896.
Images are not redistributed by this repository. Download URLs may contain
temporary credentials, so only the stable public entry point is recorded.
"""

import argparse
import hashlib
import json
import shutil
import time
import urllib.request
import zipfile
from pathlib import Path

DATASET = "https://data.mendeley.com/datasets/x3nm4cxr83/3"
DOWNLOAD = "https://data.mendeley.com/public-api/zip/x3nm4cxr83/download/3"
LICENSE = "https://creativecommons.org/licenses/by/4.0/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    root = args.destination.resolve()
    root.mkdir(parents=True, exist_ok=True)
    archive = root / "x3nm4cxr83-3.zip"
    metadata = root / "download.json"
    if not archive.exists():
        part = archive.with_suffix(".zip.part")
        request = urllib.request.Request(
            DOWNLOAD, headers={"User-Agent": "PaperForge research", "Referer": DATASET}
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            size = int(response.headers.get("Content-Length", "0"))
            if size and shutil.disk_usage(root).free < size * 2 + 1_000_000_000:
                raise RuntimeError(
                    "Insufficient space for archive and extracted research data"
                )
            total = 0
            started = last = time.monotonic()
            with part.open("wb") as stream:
                while block := response.read(2**20):
                    stream.write(block)
                    total += len(block)
                    now = time.monotonic()
                    if now - last >= 15:
                        print(
                            f"Downloaded {total / 1e6:.0f}/{size / 1e6:.0f} MB",
                            flush=True,
                        )
                        last = now
            if size and total != size:
                raise RuntimeError(f"Incomplete archive: {total}/{size} bytes")
        part.replace(archive)
        metadata.write_text(
            json.dumps(
                {
                    "dataset": DATASET,
                    "doi": "10.17632/x3nm4cxr83.3",
                    "author": "Shima Dizaj",
                    "license": LICENSE,
                    "stableDownload": DOWNLOAD,
                    "bytes": total,
                    "downloadSeconds": time.monotonic() - started,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
    with archive.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    info = json.loads(metadata.read_text(encoding="utf-8"))
    info["sha256"] = digest
    metadata.write_text(json.dumps(info, indent=2), encoding="utf-8")
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        listing = [{"path": x.filename, "bytes": x.file_size} for x in entries]
        (root / "archive-contents.json").write_text(
            json.dumps(listing, indent=2), encoding="utf-8"
        )
        output = root / "extracted"
        for entry in entries:
            target = (output / entry.filename).resolve()
            if not target.is_relative_to(output.resolve()):
                raise ValueError("Archive entry escapes the research directory")
        zipped.extractall(output)
    print(
        json.dumps(
            {"bytes": archive.stat().st_size, "sha256": digest, "entries": len(entries)}
        )
    )


if __name__ == "__main__":
    main()
