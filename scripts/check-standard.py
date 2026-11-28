"""Prüft, ob gemeinsame Dateien und Handbuchoberfläche zur PunishSystem-Vorlage passen."""
import hashlib
import json
from pathlib import Path
import re
from documentation import check_structure, fragments

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def check(root=ROOT):
    standard = json.loads((root / "scripts/standard.json").read_text(encoding="utf-8"))
    errors = []
    for relative, expected in standard["files"].items():
        path = root / relative
        if not path.is_file() or digest(path.read_bytes()) != expected:
            errors.append("Gemeinsame Datei weicht ab: " + relative)
    handbook = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    style = re.search(r"<style[^>]*>(.*?)</style>", handbook, re.S)
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", handbook, re.S)
    blocks = {"handbookCss": style[1] if style else "",
              "handbookBoot": scripts[0] if scripts else "",
              "handbookEngine": scripts[-1].split("/* PROJECT CONTENT */")[0] if scripts else ""}
    for key, value in blocks.items():
        if digest(value.encode("utf-8")) != standard[key]:
            errors.append("Handbuchoberfläche weicht ab: " + key)
    actual = {key: digest(value.encode("utf-8")) for key, value in fragments(handbook).items()}
    if actual != standard["handbookFragments"]:
        errors.append("Gemeinsame Handbuchtexte weichen von der Vorlage ab.")
    readme = (root / "README.md").read_text(encoding="utf-8-sig")
    actual = {key: digest(value.encode("utf-8")) for key, value in fragments(readme).items()}
    if actual != standard["readmeFragments"]:
        errors.append("Gemeinsame README-Texte weichen von der Vorlage ab.")
    check_structure(root, handbook)
    if errors:
        raise ValueError("\n".join(errors))
    return len(standard["files"])


if __name__ == "__main__":
    print(f"PunishSystem-Vorlage geprüft: {check()} gemeinsame Dateien, Handbuchoberfläche und Dokumentationsbausteine.")
