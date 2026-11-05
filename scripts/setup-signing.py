#!/usr/bin/env python3
"""Configure an external Android upload key without putting passwords in shell history."""

import argparse
import getpass
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIRECTORY = Path.home() / ".config" / "upm-mail"


def external_path(value: str) -> Path:
    path = Path(value).expanduser().resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError("Signing files must be stored outside the repository.")
    return path


def property_value(value: str) -> str:
    return (value.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ "))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--existing", metavar="KEYSTORE", help="Use the existing Play upload key.")
    mode.add_argument("--generate", action="store_true", help="Explicitly create a new upload key; use only for a new app or an approved upload-key reset.")
    parser.add_argument("--config", default=str(DEFAULT_DIRECTORY / "signing.properties"))
    parser.add_argument("--keystore", default=str(DEFAULT_DIRECTORY / "upload-key.jks"), help="Output path when generating a new key.")
    parser.add_argument("--alias", help="Existing alias, or the alias for a new key (default: upload).")
    args = parser.parse_args()

    # getpass otherwise falls back to echoed stdin on headless pipes.
    if not sys.stdin.isatty():
        parser.error("Run this script in an interactive SSH/terminal session so passwords can be hidden.")
    keytool = shutil.which("keytool")
    if keytool is None:
        parser.error("keytool is unavailable. Add JDK 17's bin directory to PATH first.")
    try:
        config = external_path(args.config)
        keystore = external_path(args.existing or args.keystore)
        if config.exists():
            raise ValueError("The configuration file already exists; choose another --config path to preserve it.")
        if args.generate and keystore.exists():
            raise ValueError("The keystore already exists; it will not be overwritten.")
        if args.existing and not keystore.is_file():
            raise ValueError("The existing keystore was not found.")
        alias = args.alias or ("upload" if args.generate else input("Upload key alias: ").strip())
        if not alias:
            raise ValueError("A key alias is required.")
        store_password = getpass.getpass("Keystore password: ")
        if not store_password:
            raise ValueError("The keystore password cannot be empty.")
        if args.generate:
            if len(store_password) < 6:
                raise ValueError("A new keystore password must have at least six characters.")
            if store_password != getpass.getpass("Confirm keystore password: "):
                raise ValueError("The keystore passwords do not match.")
        key_password = getpass.getpass("Key password (Enter to reuse keystore password): ") or store_password
        if args.generate and len(key_password) < 6:
            raise ValueError("A new key password must have at least six characters.")

        os.umask(0o077)
        config.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        child_env = os.environ.copy()
        child_env["UPM_SETUP_STORE_PASSWORD"] = store_password
        child_env["UPM_SETUP_KEY_PASSWORD"] = key_password
        command = [keytool]
        if args.generate:
            keystore.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
            command += ["-genkeypair", "-storetype", "JKS", "-keyalg", "RSA", "-keysize", "4096",
                        "-validity", "10000", "-dname", "CN=UPM Mail Upload",
                        "-keypass:env", "UPM_SETUP_KEY_PASSWORD"]
        else:
            command += ["-list"]
        command += ["-keystore", str(keystore), "-alias", alias,
                    "-storepass:env", "UPM_SETUP_STORE_PASSWORD"]
        result = subprocess.run(command, env=child_env, capture_output=True, text=True)
        if result.returncode:
            # Do not echo subprocess output that might include user-supplied configuration.
            raise ValueError("keytool could not access or create the requested key. Check the path, alias and password.")
        keystore.chmod(0o600)
        values = {"storeFile": str(keystore), "storePassword": store_password,
                  "keyAlias": alias, "keyPassword": key_password}
        descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write("# Private Android upload signing settings. Do not commit or share.\n")
            for name, value in values.items():
                output.write(f"{name}={property_value(value)}\n")
        print(f"Signing configuration saved with owner-only permissions: {config}")
        print("Set UPM_SIGNING_PROPERTIES_FILE to that path before running scripts/build-release.py.")
        if args.generate:
            print("Back up the keystore and passwords securely before the first Play upload.")
        return 0
    except (OSError, ValueError) as error:
        print(f"Signing setup failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
