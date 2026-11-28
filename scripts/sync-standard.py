"""Check or explicitly synchronize the shared standard from the sibling PunishSystem repository."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import uuid

FILES = [
    ".editorconfig", ".gitattributes", ".gitignore", ".mvn/jvm.config", "build_all_jars.bat",
    ".github/workflows/build.yml", ".github/workflows/pages.yml",
    "scripts/build.ps1", "scripts/bump-build.ps1", "scripts/project_tools.py", "scripts/test_project_tools.py",
    "scripts/check-version.py", "scripts/check-docs.py", "scripts/prepare-release.py", "scripts/set-version.py",
    "scripts/check-standard.py", "scripts/sync-standard.py",
    "scripts/test-tools.ps1",
]


def digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def blocks(text):
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", text, re.S)
    style = re.search(r"<style[^>]*>(.*?)</style>", text, re.S)
    if not style or len(scripts) != 2 or "/* PROJECT CONTENT */" not in scripts[-1]:
        raise ValueError("Expected the shared offline handbook format.")
    return style[1], scripts[0], scripts[-1].split("/* PROJECT CONTENT */", 1)[0]


def replace_blocks(text, source):
    style, boot, engine = source
    text = re.sub(r"<style[^>]*>.*?</style>", lambda match: "<style>" + style + "</style>", text, count=1, flags=re.S)
    matches = list(re.finditer(r"<script[^>]*>(.*?)</script>", text, re.S))
    custom = matches[-1][1].split("/* PROJECT CONTENT */", 1)[1]
    for match, replacement in reversed([(matches[0], boot), (matches[-1], engine + "/* PROJECT CONTENT */" + custom)]):
        text = text[:match.start()] + "<script>" + replacement + "</script>" + text[match.end():]
    return text


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_bytes(data)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def sync(workspace, write=False):
    workspace = workspace.resolve()
    reference = workspace / "PunishSystem"
    if not (reference / "scripts/project.json").is_file():
        raise ValueError("PunishSystem is missing from the workspace. Use --workspace to select the plugin folder.")
    source_blocks = blocks((reference / "docs/index.html").read_text(encoding="utf-8-sig"))
    contents = {}
    for relative in FILES:
        path = reference / relative
        if path.is_symlink() or not path.resolve().is_relative_to(reference.resolve()):
            raise ValueError("Shared files must stay within PunishSystem.")
        contents[relative] = path.read_bytes()
    standard = {"reference": "PunishSystem", "schema": 1,
                "files": {relative: digest(data) for relative, data in contents.items()},
                **{key: digest(data.encode("utf-8")) for key, data in
                   zip(("handbookCss", "handbookBoot", "handbookEngine"), source_blocks)}}
    contents["scripts/standard.json"] = (json.dumps(standard, indent=2) + "\n").encode("utf-8")
    changes = []
    updates = []
    projects = []
    for project in sorted(workspace.iterdir()):
        manifest = project / "scripts/project.json"
        if not manifest.is_file():
            continue
        if json.loads(manifest.read_text(encoding="utf-8"))["reference"] != "PunishSystem":
            continue
        if project.is_symlink():
            raise ValueError("Symbolic project directory.")
        projects.append(project.name)
        for relative, data in contents.items():
            path = project / relative
            if path.is_symlink() or not path.resolve().is_relative_to(project.resolve()):
                raise ValueError("External destination: " + str(path))
            if not path.exists() or digest(path.read_bytes()) != digest(data):
                changes.append(project.name + "/" + relative)
                updates.append((path, data))
        docs = project / "docs/index.html"
        text = docs.read_text(encoding="utf-8-sig")
        blocks(text)  # Validate every destination before any write.
        updated = replace_blocks(text, source_blocks)
        if updated != text:
            changes.append(project.name + "/docs/index.html (shared interface)")
            updates.append((docs, updated.encode("utf-8")))
    if write:
        for path, data in updates:
            atomic_write(path, data)
    elif changes:
        raise ValueError("Standard differs:\n" + "\n".join(changes))
    print(f"PunishSystem standard {'synchronized' if write else 'verified'} across {len(projects)} projects; {len(changes)} differences.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--write", action="store_true", help="Apply the PunishSystem interface and shared files.")
    parser.add_argument("--check", action="store_true", help="Check only (default).")
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("Use --write or --check, not both.")
    sync(args.workspace, args.write)
