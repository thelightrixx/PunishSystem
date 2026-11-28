"""Reject accidental drift of the shared PunishSystem project standard."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def check(root=ROOT):
    standard = json.loads((root / "scripts/standard.json").read_text(encoding="utf-8"))
    errors = []
    for relative, expected in standard["files"].items():
        path = root / relative
        if not path.is_file() or digest(path.read_bytes()) != expected:
            errors.append("Shared standard differs: " + relative)
    handbook = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    style = re.search(r"<style[^>]*>(.*?)</style>", handbook, re.S)
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", handbook, re.S)
    blocks = {"handbookCss": style[1] if style else "",
              "handbookBoot": scripts[0] if scripts else "",
              "handbookEngine": scripts[-1].split("/* PROJECT CONTENT */")[0] if scripts else ""}
    for key, value in blocks.items():
        if digest(value.encode("utf-8")) != standard[key]:
            errors.append("Shared handbook standard differs: " + key)
    if errors:
        raise ValueError("\n".join(errors))
    return len(standard["files"])


if __name__ == "__main__":
    print(f"PunishSystem standard verified: {check()} shared files and the offline handbook interface.")
