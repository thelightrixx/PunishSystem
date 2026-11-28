"""Prüft die gemeinsame Vorlage und überträgt Änderungen aus PunishSystem."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import uuid
from documentation import check_structure, fragments, replace_fragments

FILES = [
    ".editorconfig", ".gitattributes", ".gitignore", ".mvn/jvm.config", "build_all_jars.bat",
    ".github/workflows/build.yml", ".github/workflows/pages.yml",
    "scripts/build.ps1", "scripts/bump-build.ps1", "scripts/project_tools.py", "scripts/test_project_tools.py",
    "scripts/check-version.py", "scripts/check-docs.py", "scripts/prepare-release.py", "scripts/set-version.py",
    "scripts/check-standard.py", "scripts/sync-standard.py",
    "scripts/documentation.py",
    "scripts/test-tools.ps1",
]


def digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def blocks(text):
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", text, re.S)
    style = re.search(r"<style[^>]*>(.*?)</style>", text, re.S)
    if not style or len(scripts) != 2 or "/* PROJECT CONTENT */" not in scripts[-1]:
        raise ValueError("Das Handbuch hat nicht den erwarteten Aufbau aus Stilblock und zwei Skripten.")
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
        raise ValueError("PunishSystem fehlt. Mit --workspace den gemeinsamen Plugin-Ordner angeben.")
    reference_handbook = (reference / "docs/index.html").read_text(encoding="utf-8-sig")
    source_blocks = blocks(reference_handbook)
    source_fragments = fragments(reference_handbook)
    check_structure(reference, reference_handbook)
    readme_fragments = fragments((reference / "README.md").read_text(encoding="utf-8-sig"))
    contents = {}
    for relative in FILES:
        path = reference / relative
        if path.is_symlink() or not path.resolve().is_relative_to(reference.resolve()):
            raise ValueError("Gemeinsame Dateien müssen innerhalb von PunishSystem liegen.")
        contents[relative] = path.read_bytes()
    standard = {"reference": "PunishSystem", "schema": 2,
                "files": {relative: digest(data) for relative, data in contents.items()},
                **{key: digest(data.encode("utf-8")) for key, data in
                   zip(("handbookCss", "handbookBoot", "handbookEngine"), source_blocks)},
                "handbookFragments": {key: digest(value.encode("utf-8"))
                                      for key, value in source_fragments.items()},
                "readmeFragments": {key: digest(value.encode("utf-8"))
                                    for key, value in readme_fragments.items()}}
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
            raise ValueError("Ein Projektordner ist ein symbolischer Link.")
        projects.append(project.name)
        for relative, data in contents.items():
            path = project / relative
            if path.is_symlink() or not path.resolve().is_relative_to(project.resolve()):
                raise ValueError("Ziel liegt außerhalb des Projekts: " + str(path))
            if not path.exists() or digest(path.read_bytes()) != digest(data):
                changes.append(project.name + "/" + relative)
                updates.append((path, data))
        docs = project / "docs/index.html"
        text = docs.read_text(encoding="utf-8-sig")
        blocks(text)  # Validate every destination before any write.
        updated = replace_fragments(replace_blocks(text, source_blocks), source_fragments)
        if updated != text:
            changes.append(project.name + "/docs/index.html (shared interface)")
            updates.append((docs, updated.encode("utf-8")))
        readme = project / "README.md"
        text = readme.read_text(encoding="utf-8-sig")
        updated = replace_fragments(text, readme_fragments)
        if updated != text:
            changes.append(project.name + "/README.md (gemeinsame Texte)")
            updates.append((readme, updated.encode("utf-8")))
    if write:
        for path, data in updates:
            atomic_write(path, data)
    elif changes:
        raise ValueError("Abweichungen von der Vorlage:\n" + "\n".join(changes))
    print(f"PunishSystem-Vorlage {'übertragen' if write else 'geprüft'}: {len(projects)} Projekte, {len(changes)} Abweichungen.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--write", action="store_true", help="Gemeinsame Dateien und Handbuchoberfläche aus PunishSystem übernehmen.")
    parser.add_argument("--check", action="store_true", help="Nur prüfen (Vorgabe).")
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("Entweder --write oder --check verwenden.")
    sync(args.workspace, args.write)
