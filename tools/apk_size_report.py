"""Account for APK bytes by runtime family; compare compressed and installed payload sizes."""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import zipfile


def family(name: str) -> str:
    if name.startswith("lib/"):
        library = name.rsplit("/", 1)[-1]
        if library.startswith(("liblo-", "libnspr", "libpl", "libnss", "libfreebl", "libsoftokn", "libsqlite3", "libsmime", "libssl3", "libc++_shared")):
            return "Office native"
        if "mupdf" in library:
            return "MuPDF native"
        if "onnx" in library or "opencv" in library or "paperforge_scan" in library:
            return "Scan / ONNX / OpenCV native"
        if "ocr" in library or "translate" in library:
            return "ML Kit native"
        return "Other native"
    if re.fullmatch(r"classes\d*\.dex", name):
        return "DEX"
    if name.endswith((".ttf", ".otf", ".pfb")):
        return "Fonts"
    if name.startswith(("assets/docquad/", "assets/scan/", "assets/paddle", "assets/mlkit")):
        return "Offline models"
    if name.startswith(("assets/unpack/", "assets/share/", "assets/program/", "assets/license/")):
        return "Office assets"
    if name.startswith("assets/"):
        return "Other assets"
    if name.startswith("res/") or name == "resources.arsc":
        return "Android resources"
    return "Java resources / metadata"


def analyze(path: Path) -> dict:
    with path.open("rb") as stream:
        apk_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    groups = defaultdict(lambda: {"compressedBytes": 0, "uncompressedBytes": 0, "entries": 0})
    duplicates = defaultdict(list)
    with zipfile.ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        for entry in entries:
            group = groups[family(entry.filename)]
            group["compressedBytes"] += entry.compress_size
            group["uncompressedBytes"] += entry.file_size
            group["entries"] += 1
            # Only payload resources; empty files and repeated tiny metadata are unhelpful.
            if entry.file_size >= 65536 and not entry.filename.endswith((".dex", ".so")):
                digest = hashlib.sha256(archive.read(entry)).hexdigest()
                duplicates[digest].append(entry.filename)
        return {
            "file": path.name,
            "apkBytes": path.stat().st_size,
            "sha256": apk_hash,
            "abis": sorted({entry.filename.split("/")[1] for entry in entries if entry.filename.startswith("lib/")}),
            "compressedPayloadBytes": sum(entry.compress_size for entry in entries),
            "uncompressedPayloadBytes": sum(entry.file_size for entry in entries),
            "groups": dict(sorted(groups.items(), key=lambda item: -item[1]["compressedBytes"])),
            "largestEntries": [
                {"path": entry.filename, "compressedBytes": entry.compress_size, "uncompressedBytes": entry.file_size, "method": entry.compress_type}
                for entry in sorted(entries, key=lambda entry: -entry.compress_size)[:25]
            ],
            "duplicateResources": [names for names in duplicates.values() if len(names) > 1],
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = [analyze(path) for path in args.apks]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(f'{result["file"]}: {result["apkBytes"]:,} bytes; ABI={",".join(result["abis"])}')
        for key, value in result["groups"].items():
            print(f'  {key}: {value["compressedBytes"]:,} compressed / {value["uncompressedBytes"]:,} unpacked')


if __name__ == "__main__":
    main()
