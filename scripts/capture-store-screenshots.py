#!/usr/bin/env python3
"""Capture real Android UI from a disposable emulator; never renders or edits images.

Example (after installing and opening app-debug.apk once):
  python3 scripts/capture-store-screenshots.py prepare --locale en-US
  python3 scripts/capture-store-screenshots.py inspect
  python3 scripts/capture-store-screenshots.py capture --locale en-US --name 01-inbox.png

The optional prepare action seeds only a debug app's local cache with explicitly
fictional messages. No password is created and both emulator networks are disabled.
It refuses a physical device, the release package, or a non-demo existing account.
Use normal Android navigation or `adb shell input` between captures. Inspect every
saved image before including it in a store listing.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.pportilla.upmmail.debug"
DEMO_EMAIL = "demo@example.invalid"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / ".local/share/upm-mail-toolchain/android-sdk"))


class Emulator:
    def __init__(self, serial):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise SystemExit("Refusing a non-emulator serial; use a disposable Android emulator.")
        self.serial = serial
        self.adb = str(SDK / "platform-tools/adb")

    def run(self, *args, check=True, timeout=45):
        return subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True,
                              check=check, timeout=timeout)

    def shell(self, *args, check=True):
        return self.run("shell", shlex.join(str(a) for a in args), check=check).stdout.decode().strip()

    def verify(self):
        if self.shell("getprop", "ro.kernel.qemu") != "1":
            raise SystemExit("The selected device does not report itself as an emulator.")
        if self.shell("getprop", "sys.boot_completed") != "1":
            raise SystemExit("Android has not finished booting; retry after boot completes.")
        self.shell("run-as", PACKAGE, "id")

    def put_private(self, source, destination):
        temporary = "/data/local/tmp/upm-store-capture-" + Path(destination).name
        self.run("push", str(source), temporary)
        self.shell("run-as", PACKAGE, "cp", temporary, destination)
        self.shell("rm", "-f", temporary)


def prepare(emulator, locale):
    emulator.shell("am", "force-stop", PACKAGE)
    emulator.shell("svc", "wifi", "disable")
    emulator.shell("svc", "data", "disable")
    spanish = locale == "es-ES"
    with tempfile.TemporaryDirectory(prefix="upm-demo-cache-") as directory:
        directory = Path(directory)
        database = directory / "upmmail.db"
        for suffix in ("", "-wal", "-shm"):
            result = emulator.run("exec-out", "run-as", PACKAGE, "cat", "databases/upmmail.db" + suffix, check=False)
            if result.returncode == 0:
                (directory / ("upmmail.db" + suffix)).write_bytes(result.stdout)
            elif not suffix:
                raise SystemExit("Open the debug app once to create its database before preparing demo mail.")
        db = sqlite3.connect(database)
        if db.execute("PRAGMA user_version").fetchone()[0] != 3:
            raise SystemExit("Unrecognized database version; inspect the app schema before seeding.")
        existing = [row[0] for row in db.execute("SELECT email FROM accounts")]
        if any(email != DEMO_EMAIL for email in existing):
            raise SystemExit("Refusing to replace a cache containing a non-demo account.")
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("DELETE FROM accounts")
        db.execute("DELETE FROM contacts")
        db.execute("DELETE FROM recent_searches")
        now = int(time.time() * 1000)
        db.execute("""INSERT INTO accounts
            (id,email,display_name,imap_host,imap_port,imap_security,imap_username,
             smtp_host,smtp_port,smtp_security,smtp_username,color,notifications_enabled,created_at)
            VALUES (1,?,?, 'mail.example.invalid',993,'SSL_TLS','demo',
                    'mail.example.invalid',587,'STARTTLS','demo',-16752468,0,?)""",
                   (DEMO_EMAIL, "Buzón de ejemplo" if spanish else "Demo mailbox", now))
        folders = [(1,"INBOX","INBOX"),(2,"Sent","SENT"),(3,"Drafts","DRAFTS"),
                   (4,"Archive","ARCHIVE"),(5,"Trash","TRASH"),(6,"Spam","JUNK")]
        for folder_id, name, role in folders:
            db.execute("""INSERT INTO folders
                (id,account_id,full_name,display_name,role,uid_validity,more_available,last_synced_at)
                VALUES (?,1,?,?,?,1,0,?)""", (folder_id,name,name,role,now + 3_600_000))
        messages = [
            ("Buzón de ejemplo", "Bienvenido a tu correo de ejemplo", "Estos mensajes son ficticios. No hay ninguna cuenta real conectada."),
            ("Equipo de ejemplo", "Reunión del proyecto · lunes", "Revisaremos las ideas del equipo y los próximos pasos. ¿Nos vemos a las 10?"),
            ("Biblioteca de ejemplo", "Taller de búsqueda bibliográfica", "Ya puedes consultar el programa del taller. Trae tus preguntas y tu portátil."),
            ("Grupo de estudio", "Apuntes para la próxima sesión", "He preparado un resumen de los temas que vamos a repasar esta semana."),
            ("Tutoría de ejemplo", "Recordatorio de la práctica", "Recuerda revisar las instrucciones antes de empezar la sesión del laboratorio."),
            ("Campus de ejemplo", "Actividades de la semana", "Consulta las actividades y organiza tu agenda para los próximos días."),
            ("Lecturas de ejemplo", "Unas ideas para el fin de semana", "Aquí tienes una selección de lecturas para seguir explorando el tema."),
        ] if spanish else [
            ("Demo mailbox", "Welcome to your demo inbox", "These messages are fictional. No real email account is connected."),
            ("Demo team", "Project meeting · Monday", "We will review the team's ideas and next steps. Shall we meet at 10?"),
            ("Demo library", "Research skills workshop", "The workshop programme is ready. Bring your questions and your laptop."),
            ("Study group", "Notes for our next session", "I have prepared a summary of the topics we will review this week."),
            ("Demo tutor", "Lab session reminder", "Remember to read the instructions before the laboratory session begins."),
            ("Demo campus", "This week's activities", "Browse the activities and plan your schedule for the coming days."),
            ("Demo reading list", "A few ideas for the weekend", "Here is a selection of reading to help you explore the topic further."),
        ]
        for index, (sender, subject, body) in enumerate(messages, start=1):
            sender_address = f'"{sender}" <sample{index}@example.invalid>'
            recipient = f'"Demo" <{DEMO_EMAIL}>'
            date = now - (index - 1) * 3_600_000
            db.execute("""INSERT INTO messages
                (id,account_id,folder_id,uid,thread_id,subject,from_addr,to_addr,date,
                 is_read,is_starred,snippet,body_text,body_loaded,size)
                VALUES (?,1,1,?,?,?,?,?,?, ?,?,?,?,?,512)""",
                       (index,index,f"demo-{index}",subject,sender_address,recipient,date,
                        int(index > 3),int(index in (2,4)),body,body,1))
            db.execute("INSERT INTO messages_fts(docid,subject,sender,recipients,body) VALUES (?,?,?,?,?)",
                       (index,subject.lower(),sender.lower(),DEMO_EMAIL,body.lower()))
        db.execute("UPDATE folders SET total_count=7,unread_count=3,window_start_uid=1,window_end_uid=7 WHERE id=1")
        db.commit()
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execute("PRAGMA journal_mode=DELETE")
        db.close()
        for suffix in ("-wal", "-shm"):
            emulator.shell("run-as", PACKAGE, "rm", "-f", "databases/upmmail.db" + suffix)
        emulator.put_private(database, "databases/upmmail.db")
        preferences = directory / "upmmail_settings.xml"
        preferences.write_text(f'''<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
  <string name="theme_mode">LIGHT</string>
  <string name="language">{"es" if spanish else "en"}</string>
  <boolean name="language_in_system" value="false" />
  <boolean name="push_enabled" value="false" />
  <boolean name="notifications_enabled" value="false" />
  <boolean name="dynamic_color" value="false" />
  <int name="sync_interval_minutes" value="0" />
  <long name="last_account_id" value="1" />
  <long name="last_folder_id" value="1" />
</map>
''')
        emulator.shell("run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
        emulator.put_private(preferences, "shared_prefs/upmmail_settings.xml")
    if int(emulator.shell("getprop", "ro.build.version.sdk") or "0") >= 33:
        emulator.shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale)
    emulator.shell("am", "start", "-n", PACKAGE + "/io.github.pportilla.upmmail.MainActivity")
    print(f"Prepared {locale} fictional local mail; no credentials or real servers are configured.")
    print("Emulator Wi-Fi/mobile data and app push/periodic sync are disabled. Wait for the UI, then inspect it.")


def inspect(emulator):
    emulator.shell("uiautomator", "dump", "/data/local/tmp/upm-screen.xml")
    content = emulator.run("exec-out", "cat", "/data/local/tmp/upm-screen.xml").stdout
    for node in ET.fromstring(content).iter("node"):
        label = node.get("text") or node.get("content-desc")
        if label:
            print(json.dumps({"label": label, "bounds": node.get("bounds"),
                              "clickable": node.get("clickable")}, ensure_ascii=False))


def capture(emulator, locale, name):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+\.png", name):
        raise SystemExit("Use a simple PNG name, for example 01-inbox.png.")
    destination = ROOT / "fastlane/metadata/android" / locale / "images/phoneScreenshots"
    destination.mkdir(parents=True, exist_ok=True)
    png = emulator.run("exec-out", "screencap", "-p").stdout
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit("screencap did not return a PNG; no image was written.")
    path = destination / name
    path.write_bytes(png)
    manifest = destination / "CAPTURE.json"
    records = json.loads(manifest.read_text()) if manifest.exists() else {}
    records[name] = {
        "captured_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": "Unmodified adb exec-out screencap -p output from running Android app",
        "device": emulator.serial, "package": PACKAGE, "locale": locale,
        "android_api": emulator.shell("getprop", "ro.build.version.sdk"),
        "dimensions": list(struct.unpack(">II", png[16:24])),
        "fixture": "No real mailbox data or mail credentials; any visible messages use fictional example.invalid identities",
        "sha256": hashlib.sha256(png).hexdigest(),
    }
    manifest.write_text(json.dumps(records, indent=2, ensure_ascii=False) + "\n")
    print(path)
    print("Review the actual screenshot before selecting it for publication.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "inspect", "capture"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--locale", choices=("en-US", "es-ES"), default="en-US")
    parser.add_argument("--name", default="01-inbox.png")
    args = parser.parse_args()
    emulator = Emulator(args.serial)
    emulator.verify()
    if args.action == "prepare":
        prepare(emulator, args.locale)
    elif args.action == "inspect":
        inspect(emulator)
    else:
        capture(emulator, args.locale, args.name)


if __name__ == "__main__":
    main()
