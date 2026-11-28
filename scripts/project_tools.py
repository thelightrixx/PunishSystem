"""Shared project, handbook and release checks derived from PunishSystem."""
from __future__ import annotations

import argparse
import hashlib
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import shutil
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\Z")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def config(root: Path = ROOT) -> dict:
    return json.loads((root / "scripts/project.json").read_text(encoding="utf-8-sig"))


def version(root: Path = ROOT) -> str:
    value = ET.parse(root / "pom.xml").findtext("m:version", namespaces=NS)
    if value is None or VERSION.fullmatch(value) is None:
        raise ValueError("Expected a stable version vMAJOR.MINOR.PATCH in pom.xml.")
    return value


def field(text: str, key: str) -> str:
    match = re.search(r"(?m)^" + re.escape(key) + r":\s*([^\r\n#]+)", text)
    return match.group(1).strip().strip("\"'") if match else ""


class Handbook(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.pages = {}
        self.ids = set()
        self.links = []
        self.errors = []
        self.external_assets = []

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if attrs.get("id"):
            identifier = attrs["id"]
            if identifier in self.ids:
                self.errors.append("Duplicate HTML id: " + identifier)
            self.ids.add(identifier)
        if tag == "section" and "page" in attrs.get("class", "").split():
            identifier = attrs.get("data-id", "")
            if not re.fullmatch("[a-z0-9-]+", identifier) or identifier in self.pages:
                self.errors.append("Invalid or duplicate handbook page: " + identifier)
            self.pages[identifier] = attrs.get("data-title", "")
        if tag == "a" and attrs.get("href", "").startswith("#/"):
            self.links.append(attrs["href"][2:].split("/")[0])
        if tag in {"script", "img", "iframe", "audio", "video", "source"} and attrs.get("src"):
            if not attrs["src"].startswith("data:"):
                self.external_assets.append(attrs["src"])
        if tag == "link" and attrs.get("rel") == "stylesheet" and attrs.get("href"):
            self.external_assets.append(attrs["href"])


def check_docs(root: Path = ROOT) -> int:
    name = config(root)["name"]
    source = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    parser = Handbook()
    parser.feed(source)
    missing = {"ueberblick", "installation", "schnellstart", "befehle", "betrieb", "changelog"} - parser.pages.keys()
    parser.errors += ["Missing handbook page: " + page for page in sorted(missing)]
    parser.errors += ["Broken page link: " + link for link in parser.links if link not in parser.pages]
    parser.errors += ["External handbook asset: " + asset for asset in parser.external_assets]
    for identifier in ("searchBtn", "themeBtn", "sidebar", "main"):
        if identifier not in parser.ids:
            parser.errors.append("Missing handbook control: " + identifier)
    if 'id="q"' not in source:
        parser.errors.append("Missing handbook search input.")
    if name + " Handbuch" not in source:
        parser.errors.append("Handbook title does not match the project.")
    if re.search(r"@(?:VERSION|PAGES|STYLE|SCRIPT|YAML:[a-z]+)@", source):
        parser.errors.append("Unexpanded handbook placeholder.")
    if re.search(r"url\(\s*['\"]?https?://", source, re.IGNORECASE):
        parser.errors.append("External CSS asset in the offline handbook.")
    if parser.errors:
        raise ValueError("\n".join(parser.errors))
    return len(parser.pages)


def check_versions(root: Path = ROOT, tag: str | None = None) -> str:
    settings = config(root)
    name = settings["name"]
    current = version(root)
    if tag is not None and tag != current:
        raise ValueError(f"Tag {tag} does not match project version {current}.")
    parent = ET.parse(root / "pom.xml")
    modules = [m.text for m in parent.findall("m:modules/m:module", NS)]
    for module in modules:
        pom = ET.parse(root / module / "pom.xml")
        if pom.findtext("m:parent/m:version", namespaces=NS) != current:
            raise ValueError("Parent version mismatch in " + module)
        module_version = pom.findtext("m:version", namespaces=NS)
        if module_version and module_version != current:
            raise ValueError("Module version mismatch in " + module)
    for item in settings["artifacts"]:
        if item["module"] not in modules:
            raise ValueError("Unknown release module: " + item["module"])
    readme = (root / "README.md").read_text(encoding="utf-8-sig")
    docs = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    required = {
        "README heading": f"# {name} {current}" in readme.splitlines(),
        "README release link": f"https://github.com/{settings['repository']}/releases/tag/{current}" in readme,
        "handbook version": f'<span class="ver">{current}</span>' in docs,
        "changelog version": f'<h2 data-anchor="{current.replace(".", "-")}">{current}</h2>' in docs,
        "local agent rules excluded": "AGENTS.md" in (root / ".gitignore").read_text(encoding="utf-8").splitlines(),
    }
    failures = [key for key, valid in required.items() if not valid]
    if failures:
        raise ValueError("Project consistency failed: " + ", ".join(failures))
    return current


def jar_path(root: Path, artifact: dict, current: str) -> Path:
    name = config(root)["name"]
    module = artifact["module"]
    return root / module / "target" / f"{name}-{current}-{module}.jar"


def check_jar(path: Path, artifact: dict, current: str, settings: dict) -> dict:
    if not path.is_file():
        raise ValueError("Missing built JAR: " + str(path))
    with zipfile.ZipFile(path) as archive:
        entries = archive.namelist()
        if len(entries) != len(set(entries)) or archive.testzip() is not None:
            raise ValueError("Corrupt or duplicate JAR entries in " + path.name)
        metadata_name = artifact["metadata"]
        if metadata_name not in archive.namelist():
            raise ValueError(f"{path.name}: missing {metadata_name}")
        raw = archive.read(metadata_name).decode("utf-8")
        if metadata_name.endswith(".json"):
            metadata = json.loads(raw)
            actual_version = metadata.get("version")
            main = metadata.get("main")
            actual_name = metadata.get("name")
        else:
            actual_version, main, actual_name = (field(raw, key) for key in ("version", "main", "name"))
            expected_api = artifact.get("apiVersion", "1.21.8" if artifact["module"].endswith("1.21") else "26.1")
            if field(raw, "api-version") != expected_api:
                raise ValueError(f"{path.name}: wrong Paper API metadata (expected {expected_api}).")
        if actual_name != artifact.get("pluginName", settings["name"]) or actual_version != current:
            raise ValueError(f"{path.name}: wrong plugin identity/version ({actual_name}, {actual_version}).")
        if not main or main.replace(".", "/") + ".class" not in archive.namelist():
            raise ValueError(f"{path.name}: missing plugin entry point {main}.")
        own_classes = [n for n in archive.namelist() if n.startswith("de/lightrixx/") and n.endswith(".class")]
        for entry in own_classes:
            data = archive.read(entry)
            if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") > 65:
                raise ValueError(f"{path.name}: invalid or newer-than-Java-21 class {entry}.")
        if any(n.startswith((".secrets/", ".git/")) or n == "AGENTS.md" for n in archive.namelist()):
            raise ValueError("Local files accidentally included in " + path.name)
        for entry in settings.get("requiredEntries", []):
            if entry not in entries:
                raise ValueError(f"{path.name}: required entry is missing: {entry}")
        forbidden = tuple(settings.get("forbiddenPrefixes", []))
        if forbidden and any(entry.startswith(forbidden) for entry in entries):
            raise ValueError(f"{path.name}: unisolated platform, library or test classes.")
        for entry, expected in settings.get("libraryVersions", {}).items():
            if entry not in entries or archive.read(entry).decode("utf-8").strip() != expected:
                raise ValueError(f"{path.name}: incorrect embedded library version: {entry}")
    return {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size, "classes": len(own_classes)}


def check_jars(root: Path = ROOT, tag: str | None = None) -> list[dict]:
    current = check_versions(root, tag)
    settings = config(root)
    return [check_jar(jar_path(root, item, current), item, current, settings) for item in settings["artifacts"]]


class ReleaseText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in {"h2", "h3"}:
            self.parts.append("\n\n### ")
        if tag in {"p", "ul", "ol"}:
            self.parts.append("\n\n")
        if tag == "li":
            self.parts.append("\n- ")
        if tag == "code":
            self.parts.append(chr(96))

    def handle_endtag(self, tag):
        if tag in {"h2", "h3"}:
            self.parts.append("\n\n")
        if tag == "code":
            self.parts.append(chr(96))

    def handle_data(self, data):
        self.parts.append(re.sub(r"\s+", " ", data))


def prepare_release(root: Path = ROOT, tag: str | None = None) -> list[dict]:
    checks = check_jars(root, tag)
    check_docs(root)
    settings, current = config(root), version(root)
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    if not dist.resolve().is_relative_to(root.resolve()):
        raise ValueError("Release output directory must stay within the project.")
    # Only generated artifacts belonging to this project are replaced.
    for previous in dist.glob(settings["name"] + "-*.jar"):
        previous.unlink()
    for previous in dist.glob(settings["name"] + "-*-sources.zip"):
        previous.unlink()
    sums = []
    for item, checked in zip(settings["artifacts"], checks, strict=True):
        source = jar_path(root, item, current)
        destination = dist / source.name
        shutil.copyfile(source, destination)
        if hashlib.sha256(destination.read_bytes()).hexdigest() != checked["sha256"]:
            raise ValueError("JAR changed while preparing the release.")
        sums.append(checked["sha256"] + "  " + destination.name)
        local_name = f"{settings['name']}-{current}-({item['module']}).jar"
        shutil.copyfile(source, root / local_name)
    sources = dist / f"{settings['name']}-{current}-sources.zip"
    with zipfile.ZipFile(sources, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in source_files(root):
            info = zipfile.ZipInfo(f"{settings['name']}-{current}/" + source.relative_to(root).as_posix(),
                                   date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, source.read_bytes())
    sums.append(hashlib.sha256(sources.read_bytes()).hexdigest() + "  " + sources.name)
    write_text(dist / "SHA256SUMS.txt", "\n".join(sums) + "\n")
    docs = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    changelog = re.search(r'<section\b[^>]*data-id="changelog"[^>]*>(.*?)</section>', docs, re.S)
    if changelog is None:
        raise ValueError("Changelog is missing.")
    entries = re.split(r"<h2\b[^>]*>[^<]*</h2>", changelog.group(1))
    if len(entries) < 2:
        raise ValueError("Release entry is missing.")
    parser = ReleaseText()
    parser.feed(entries[1])
    notes = re.sub(r"\n[ \t]+", "\n", "".join(parser.parts).strip())
    notes = re.sub(r"\n{3,}", "\n\n", notes)
    write_text(dist / "RELEASE_NOTES.md", f"# {settings['name']} {current}\n\n{notes}\n")
    write_text(dist / "VERIFICATION.json", json.dumps(checks, indent=2) + "\n")
    return checks


def source_files(root: Path = ROOT) -> list[Path]:
    """Whitelist buildable sources; never put local tools, agents or secrets in a release."""
    files = []
    for name in ("pom.xml", "README.md", "LICENSE", "THIRD_PARTY.md", "build_all_jars.bat",
                 ".editorconfig", ".gitattributes", ".gitignore"):
        path = root / name
        if path.is_symlink():
            raise ValueError("Symbolic source file: " + str(path))
        if path.is_file():
            files.append(path)
    modules = [m.text for m in ET.parse(root / "pom.xml").findall("m:modules/m:module", NS)]
    suffixes = {".java", ".xml", ".json", ".yml", ".yaml", ".md", ".html", ".css", ".js", ".py",
                ".ps1", ".bat", ".txt", ".config"}
    for directory in (".mvn", ".github", "scripts", "docs", *modules):
        base = root / directory
        if base.is_symlink() or not base.resolve().is_relative_to(root.resolve()):
            raise ValueError("Symbolic or external source directory: " + str(base))
        for current, directories, names in os.walk(base, followlinks=False):
            for child in directories:
                if (Path(current) / child).is_symlink():
                    raise ValueError("Symbolic source directory.")
            directories[:] = sorted(d for d in directories if not d.startswith(".") and
                                    d not in {"target", "dist", "__pycache__", "node_modules", "test-server"})
            for name in sorted(names):
                path = Path(current) / name
                if name == "AGENTS.md" or name.startswith(".") or path.suffix not in suffixes:
                    continue
                if path.is_symlink():
                    raise ValueError("Symbolic source file: " + str(path))
                files.append(path)
    return sorted(files, key=lambda path: path.relative_to(root).as_posix())


def set_version(root: Path, target: str | None = None, increment: str | None = None) -> str:
    if target and increment:
        raise ValueError("Use either a version or an increment.")
    old = version(root)
    if increment:
        major, minor, patch = map(int, VERSION.fullmatch(old).groups())
        if increment == "minor":
            minor, patch = minor + 1, 0
        elif increment == "patch":
            patch += 1
        else:
            raise ValueError("Only patch and minor increments are automatic.")
        target = f"v{major}.{minor}.{patch}"
    if not target:
        return old
    if VERSION.fullmatch(target) is None:
        raise ValueError("Expected a stable version vMAJOR.MINOR.PATCH.")
    parent = ET.parse(root / "pom.xml")
    paths = [root / "pom.xml"] + [root / m.text / "pom.xml" for m in parent.findall("m:modules/m:module", NS)]
    updates = {}
    for path in paths:
        text = path.read_text(encoding="utf-8-sig")
        pattern = re.compile(r"<version>" + re.escape(old) + r"</version>")
        if not pattern.search(text):
            raise ValueError("Version is missing in " + str(path))
        updates[path] = pattern.sub("<version>" + target + "</version>", text, count=1)
    velocity = root / "velocity/src/main/java/de/lightrixx/punish/velocity/PunishVelocity.java"
    if velocity.exists():
        updates[velocity] = velocity.read_text(encoding="utf-8-sig").replace('version = "' + old + '"', 'version = "' + target + '"')
    for path, text in updates.items():
        write_text(path, text)
    return target


def main(command: str | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--tag")
    parser.add_argument("--jar", action="store_true")
    parser.add_argument("--version")
    parser.add_argument("--increment", choices=("patch", "minor"))
    args = parser.parse_args()
    root = args.root.resolve()
    if command == "docs":
        print(f"Handbook verified: {check_docs(root)} pages, offline assets.")
    elif command == "release":
        print(json.dumps(prepare_release(root, args.tag), indent=2))
    elif command == "version":
        print(set_version(root, args.version, args.increment))
        print("Update README and the handbook/changelog before the next verified build.")
    else:
        print("Version verified: " + check_versions(root, args.tag))
        if args.jar:
            print(json.dumps(check_jars(root, args.tag), indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as failure:
        raise SystemExit(str(failure)) from None
