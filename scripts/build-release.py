#!/usr/bin/env python3
"""Build and verify a signed Play bundle, or explicitly validate an unsigned bundle."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab"


def certificate_digest(keytool: str, path: Path, *, bundle: bool) -> str:
    command = [keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-printcert",
               "-jarfile" if bundle else "-file", str(path)]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    match = re.search(r"SHA256:\s*([0-9A-F:]+)", result.stdout)
    if not match:
        raise ValueError("Could not read the upload certificate SHA-256 fingerprint.")
    return match.group(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned", action="store_true", help="Build for validation only; the result cannot be uploaded to Play.")
    parser.add_argument("--version-code", type=int, help="Play versionCode, greater than every previously uploaded version.")
    parser.add_argument("--version-name", help="User-visible version, such as 1.0.0.")
    args = parser.parse_args()
    if args.version_code is not None and not 1 <= args.version_code <= 2_100_000_000:
        parser.error("--version-code must be between 1 and 2100000000.")
    if args.version_name is not None and not 1 <= len(args.version_name.strip()) <= 100:
        parser.error("--version-name must contain 1–100 characters.")

    child_env = os.environ.copy()
    if args.version_code is not None:
        child_env["UPM_RELEASE_VERSION_CODE"] = str(args.version_code)
    if args.version_name is not None:
        child_env["UPM_RELEASE_VERSION_NAME"] = args.version_name
    signing_options = ["-PunsignedRelease=true"] if args.unsigned else ["-PrequireReleaseSigning=true"]
    command = [str(ROOT / "gradlew"), "--no-daemon", "--console=plain", *signing_options,
               ":app:lintRelease", ":app:testReleaseUnitTest", ":app:bundleRelease"]
    try:
        subprocess.run(command, cwd=ROOT, env=child_env, check=True)
        with zipfile.ZipFile(BUNDLE) as bundle:
            signature_entries = [name for name in bundle.namelist()
                                 if re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name, re.IGNORECASE)]
            if args.unsigned and signature_entries:
                raise ValueError("The unsigned validation bundle unexpectedly contains a signing certificate.")
            if not args.unsigned and not signature_entries:
                raise ValueError("The release bundle is unsigned and cannot be uploaded to Play.")
        if not args.unsigned:
            jarsigner = shutil.which("jarsigner")
            keytool = shutil.which("keytool")
            if not jarsigner or not keytool:
                raise ValueError("JDK jarsigner and keytool are required to verify the release bundle.")
            verify = subprocess.run([jarsigner, "-J-Duser.language=en", "-J-Duser.country=US",
                                     "-verify", str(BUNDLE)], capture_output=True, text=True, check=True)
            if "jar verified." not in verify.stdout:
                raise ValueError("The release bundle signature could not be verified.")
            upload_digest = certificate_digest(keytool, BUNDLE, bundle=True)
            # Reject the public debug certificate even if accidentally supplied as an external key.
            debug = subprocess.run([keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
                                    "-keystore", str(ROOT / "app" / "debug.keystore"),
                                    "-storepass", "android", "-alias", "androiddebugkey"],
                                   capture_output=True, text=True, check=True)
            debug_digest = re.search(r"SHA256:\s*([0-9A-F:]+)", debug.stdout)
            if not debug_digest or upload_digest == debug_digest.group(1):
                raise ValueError("The public debug signing certificate is forbidden for Play releases.")
            print(f"Upload certificate SHA-256: {upload_digest}")
        digest = hashlib.sha256(BUNDLE.read_bytes()).hexdigest()
        checksum_path = BUNDLE.with_suffix(".aab.sha256")
        checksum_path.write_text(f"{digest}  {BUNDLE.name}\n", encoding="utf-8")
        print(f"{'Unsigned validation' if args.unsigned else 'Verified signed'} bundle: {BUNDLE}")
        print(f"Bundle SHA-256: {digest}")
        if args.unsigned:
            print("Validation only: rerun with upload signing credentials before uploading to Play.")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Release build or verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
