"""
SafeBank AI - BB-MAS Phone Subset Extractor (Updated for actual archive layout)
Extracts only smartphone behavioural biometric data from the downloaded BB-MAS archive:
- HandPhone Keyboard (*HandPhone_Keyboard*.csv)
- HandPhone TouchEvent (*HandPhone_TouchEvent*.csv)
- HandPhone Accelerometer (*HandPhone_Accelerometer*.csv)
- HandPhone Gyroscope (*HandPhone_Gyroscope*.csv)
- HandPhone Checkpoints (*HandPhone_Checkpoints*.csv - Task/session boundaries)
- Demographics.csv
- Phone_Freetext.csv
- BBMAS_README.pdf

Preserves original participant IDs and file names without modifying raw records.
Ignores Desktop, Tablet, PocketPhone, and Mouse files to conserve disk space.
"""

import os
import sys
import zipfile
import shutil
from pathlib import Path

DOWNLOADS_DIR = Path("ml/data/downloads")
TARGET_DIR = Path("ml/data/bbmas_phone")

PHONE_PATTERNS = [
    "handphone_keyboard",
    "handphone_touchevent",
    "handphone_accelerometer",
    "handphone_gyroscope",
    "handphone_checkpoints",
    "demographics.csv",
    "phone_freetext.csv",
    "bbmas_readme.pdf"
]

def find_archive():
    for ext in ("*.zip", "*.tar.gz", "*.tar", "*.7z"):
        for p in DOWNLOADS_DIR.glob(ext):
            return p
    # Also check parent downloads
    parent_downloads = Path("C:/Users/hp/Downloads")
    for pattern in ("*bb*mas*.zip", "*su*ais*.zip", "*bbmas*.zip"):
        for p in parent_downloads.glob(pattern):
            return p
    return None

def is_phone_file(filename: str) -> bool:
    fn_lower = filename.lower()
    return any(pat in fn_lower for pat in PHONE_PATTERNS)

def extract_phone_subset(archive_path: Path):
    print(f"Opening archive: {archive_path}")
    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    extracted_count = 0
    total_bytes = 0

    with zipfile.ZipFile(archive_path, 'r') as zf:
        members = zf.infolist()
        total_members = len(members)
        print(f"Total archive entries: {total_members}")
        
        for idx, member in enumerate(members):
            if member.is_dir():
                continue
            name = Path(member.filename).name
            if is_phone_file(name):
                target_file = TARGET_DIR / name
                with zf.open(member) as src, open(target_file, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                extracted_count += 1
                total_bytes += member.file_size
                if extracted_count % 50 == 0:
                    print(f"Extracted {extracted_count} phone files ({total_bytes / (1024*1024):.1f} MB)...")

    print(f"\nExtraction complete!")
    print(f"Extracted: {extracted_count} smartphone files")
    print(f"Total uncompressed size: {total_bytes / (1024*1024):.2f} MB ({total_bytes / (1024*1024*1024):.2f} GB)")
    print(f"Output directory: {TARGET_DIR.resolve()}")
    return extracted_count, total_bytes

if __name__ == "__main__":
    archive = find_archive()
    if not archive:
        print(f"No BB-MAS archive found in {DOWNLOADS_DIR} or user Downloads directory.")
        sys.exit(1)
    extract_phone_subset(archive)
