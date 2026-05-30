#!/usr/bin/env python3
"""
Merge script: Combines carrier APK with native libs from split APKs,
bundles all original splits as assets, outputs universal unsigned APK.
"""

import os
import sys
import zipfile
import hashlib
import shutil

BUILD_WORK = "/home/user/SDF/build_work"
XAPK_DIR = os.path.join(BUILD_WORK, "xapk")
BUILD_DIR = os.path.join(BUILD_WORK, "build")
OUT_DIR = os.path.join(BUILD_WORK, "out")

CARRIER_APK = os.path.join(BUILD_DIR, "carrier.apk")
OUTPUT_APK = os.path.join(BUILD_DIR, "universal_unsigned.apk")

# Split APKs in xapk dir
ARM64_APK = os.path.join(XAPK_DIR, "config.arm64_v8a.apk")
ARMV7_APK = os.path.join(XAPK_DIR, "config.armeabi_v7a.apk")
EN_APK    = os.path.join(XAPK_DIR, "config.en.apk")
FR_APK    = os.path.join(XAPK_DIR, "config.fr.apk")
XXHDPI_APK = os.path.join(XAPK_DIR, "config.xxhdpi.apk")
BASE_APK  = os.path.join(XAPK_DIR, "com.ik.simwheel.apk")
ORIG_XAPK = os.path.join(BUILD_WORK, "simwheel_5.2.2.xapk")

def sha256_of_zip_entry(zip_path, entry_name):
    with zipfile.ZipFile(zip_path, 'r') as zf:
        with zf.open(entry_name) as f:
            return hashlib.sha256(f.read()).hexdigest()

def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    print("=== SimWheel APK Merge Script ===")
    print(f"Carrier: {CARRIER_APK}")
    print(f"Output:  {OUTPUT_APK}")

    # Verify inputs exist
    for p in [CARRIER_APK, ARM64_APK, ARMV7_APK]:
        if not os.path.exists(p):
            print(f"ERROR: Missing {p}")
            sys.exit(1)

    # Collect native lib entries from arm64 split
    print("\nCollecting native libs from split APKs...")
    arm64_libs = {}
    with zipfile.ZipFile(ARM64_APK, 'r') as zf:
        for info in zf.infolist():
            if info.filename.startswith("lib/arm64-v8a/") and not info.is_dir():
                arm64_libs[info.filename] = zf.read(info.filename)
                print(f"  arm64: {info.filename} ({len(arm64_libs[info.filename])} bytes)")

    armv7_libs = {}
    with zipfile.ZipFile(ARMV7_APK, 'r') as zf:
        for info in zf.infolist():
            if info.filename.startswith("lib/armeabi-v7a/") and not info.is_dir():
                armv7_libs[info.filename] = zf.read(info.filename)
                print(f"  armv7: {info.filename} ({len(armv7_libs[info.filename])} bytes)")

    # Build output APK
    print("\nBuilding merged APK...")
    skip_prefixes = ("META-INF/",)
    skip_entries = {"stamp-cert-sha256"}

    with zipfile.ZipFile(OUTPUT_APK, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=True) as out_zip:
        # 1. Copy carrier APK entries (skip signatures)
        with zipfile.ZipFile(CARRIER_APK, 'r') as carrier:
            for info in carrier.infolist():
                name = info.filename
                skip = False
                for prefix in skip_prefixes:
                    if name.startswith(prefix):
                        skip = True
                        break
                if name in skip_entries:
                    skip = True
                if skip:
                    continue
                data = carrier.read(name)
                out_zip.writestr(info, data)
                print(f"  carrier: {name} ({len(data)} bytes)")

        # 2. Add arm64 native libs (stored uncompressed for performance)
        for name, data in arm64_libs.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_STORED
            out_zip.writestr(info, data)
            print(f"  added native: {name}")

        # 3. Add armv7 native libs
        for name, data in armv7_libs.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_STORED
            out_zip.writestr(info, data)
            print(f"  added native: {name}")

        # 4. Bundle all original split APKs as assets (stored uncompressed)
        splits = [
            ("com.ik.simwheel.apk", BASE_APK),
            ("config.arm64_v8a.apk", ARM64_APK),
            ("config.armeabi_v7a.apk", ARMV7_APK),
        ]
        # Add optional splits if they exist
        for split_name, split_path in [
            ("config.en.apk", EN_APK),
            ("config.fr.apk", FR_APK),
            ("config.xxhdpi.apk", XXHDPI_APK),
        ]:
            if os.path.exists(split_path):
                splits.append((split_name, split_path))

        for split_name, split_path in splits:
            if not os.path.exists(split_path):
                print(f"  WARNING: Missing split {split_path}, skipping")
                continue
            with open(split_path, 'rb') as f:
                data = f.read()
            asset_name = "assets/original_splits/" + split_name
            info = zipfile.ZipInfo(asset_name)
            info.compress_type = zipfile.ZIP_STORED
            out_zip.writestr(info, data)
            print(f"  bundled split: {asset_name} ({len(data)} bytes)")

        # 5. Bundle full XAPK
        if os.path.exists(ORIG_XAPK):
            with open(ORIG_XAPK, 'rb') as f:
                xapk_data = f.read()
            asset_name = "assets/original_splits/com.ik.simwheel_5.2.2_ORIGINAL.xapk"
            info = zipfile.ZipInfo(asset_name)
            info.compress_type = zipfile.ZIP_STORED
            out_zip.writestr(info, xapk_data)
            print(f"  bundled XAPK: {asset_name} ({len(xapk_data)} bytes)")
        else:
            print("  WARNING: XAPK not found, skipping")

    size = os.path.getsize(OUTPUT_APK)
    print(f"\n=== Output: {OUTPUT_APK} ===")
    print(f"Size: {size} bytes ({size / 1024 / 1024:.1f} MB)")

    # Verify size
    if size < 99 * 1024 * 1024:
        print(f"WARNING: APK is {size / 1024 / 1024:.1f} MB, less than 99MB target")
    else:
        print(f"OK: APK size exceeds 99MB requirement")

    # Print arm64 libapp.so SHA256
    print("\nVerifying libapp.so SHA256...")
    try:
        h = sha256_of_zip_entry(OUTPUT_APK, "lib/arm64-v8a/libapp.so")
        print(f"arm64 libapp.so SHA256: {h}")
        expected = "fdbe3192c28cc2c0c197d1a23b13fee67e14d56ba81817359344c2d1d6dc3648"
        if h == expected:
            print("SHA256 MATCH - libapp.so is authentic")
        else:
            print(f"WARNING: SHA256 mismatch (expected {expected})")
    except Exception as e:
        print(f"Could not verify libapp.so: {e}")

    print("\nDone!")

if __name__ == "__main__":
    main()
