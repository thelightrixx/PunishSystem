"""Prüft Versionen, Handbuch und Release-Dateien nach der PunishSystem-Vorlage."""
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
import subprocess
import sys
import tempfile
import uuid
from urllib.parse import unquote, urlsplit
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
    settings = json.loads((root / "scripts/project.json").read_text(encoding="utf-8-sig"))
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", settings.get("name", "")):
        raise ValueError("Ungültiger Projektname in scripts/project.json.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/" + re.escape(settings["name"]), settings.get("repository", "")):
        raise ValueError("Projektname und Repository in scripts/project.json passen nicht zusammen.")
    artifacts = settings.get("artifacts", [])
    modules = [item.get("module", "") for item in artifacts]
    if not modules or len(set(modules)) != len(modules):
        raise ValueError("Die Artefaktliste fehlt oder enthält ein Modul mehrfach.")
    for item in artifacts:
        if not re.fullmatch(r"[a-z][a-z0-9.-]*", item["module"]) or item.get("metadata") not in {
            "plugin.yml", "paper-plugin.yml", "velocity-plugin.json"
        }:
            raise ValueError("Ungültiges Release-Modul oder Metadatenformat.")
    if any(not VERSION.fullmatch(v) for v in settings.get("retiredVersions", [])):
        raise ValueError("Zurückgezogene Versionen müssen vMajor.Minor.Patch entsprechen.")
    return settings


def version(root: Path = ROOT) -> str:
    value = ET.parse(root / "pom.xml").findtext("m:version", namespaces=NS)
    if value is None or VERSION.fullmatch(value) is None:
        raise ValueError("pom.xml muss eine stabile Version im Format vMajor.Minor.Patch enthalten.")
    return value


def field(text: str, key: str) -> str:
    match = re.search(r"(?m)^" + re.escape(key) + r":\s*([^\r\n#]+)", text)
    return match.group(1).strip().strip("\"'") if match else ""


def slug(text: str) -> str:
    text = text.lower().translate(str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"}))
    return re.sub(r"[^a-z0-9]+", "-", text).strip("-") or "abschnitt"


class Handbook(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.pages = {}
        self.ids = set()
        self.links = []
        self.errors = []
        self.external_assets = []
        self.targets = {}
        self.stack = []
        self.page = None
        self.references = []
        self.aliases = {}
        self.local_links = []
        self.anchor_aliases = {}

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        node = {"tag": tag, "attrs": attrs, "text": [], "page": self.page, "position": self.getpos()}
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append(node)
        if attrs.get("id"):
            identifier = attrs["id"]
            if identifier in self.ids:
                self.errors.append("HTML-ID mehrfach vergeben: " + identifier)
            self.ids.add(identifier)
        if tag == "section" and "page" in attrs.get("class", "").split():
            identifier = attrs.get("data-id", "")
            if not re.fullmatch("[a-z0-9-]+", identifier) or identifier in self.pages:
                self.errors.append("Handbuchseite fehlt oder ist mehrfach vergeben: " + identifier)
            self.pages[identifier] = attrs.get("data-title", "")
            self.page = node["page"] = identifier
            self.targets.setdefault(identifier, [])
            self.anchor_aliases[identifier] = attrs.get("data-anchor-aliases", "").split()
            for alias in attrs.get("data-aliases", "").split():
                if not re.fullmatch("[a-z0-9-]+", alias) or alias in self.aliases:
                    self.errors.append("Ungültiger oder mehrfacher Kapitelalias: " + alias)
                self.aliases[alias] = identifier
        if self.page and (tag in {"h2", "h3", "details"} or "cmd-card" in attrs.get("class", "").split()):
            self.targets[self.page].append(node)
        if tag == "pre" and attrs.get("data-source"):
            self.references.append(node)
        if tag == "a" and attrs.get("href", "").startswith("#/"):
            self.links.append(attrs["href"][2:])
        if tag == "a" and attrs.get("href"):
            target = urlsplit(attrs["href"])
            if not target.scheme and not target.netloc and target.path:
                self.local_links.append(unquote(target.path))
        if tag in {"script", "img", "iframe", "audio", "video", "source"} and attrs.get("src"):
            if not attrs["src"].startswith("data:"):
                self.external_assets.append(attrs["src"])
        if tag == "link" and attrs.get("rel") == "stylesheet" and attrs.get("href"):
            self.external_assets.append(attrs["href"])

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_data(self, data):
        for index, node in enumerate(self.stack):
            if node["tag"] == "details":
                if any(child["tag"] == "summary" for child in self.stack[index + 1:]):
                    node["text"].append(data)
            else:
                node["text"].append(data)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index]["tag"] == tag:
                del self.stack[index:]
                break
        self.page = next((n["page"] for n in reversed(self.stack) if n["tag"] == "section"), None)

    def anchors(self):
        """Die Oberfläche vergibt zuerst Überschriften-, dann Detail- und Befehlsanker."""
        result = {}
        for page, nodes in self.targets.items():
            used = result[page] = set()
            order = lambda n: 0 if n["tag"] in {"h2", "h3"} else 1 if n["tag"] == "details" else 2
            for node in sorted(nodes, key=order):
                attrs = node["attrs"]
                text = attrs.get("data-cmd", "").lstrip("/") if order(node) == 2 else "".join(node["text"])
                base = attrs.get("data-anchor") or slug(text or "eintrag")
                anchor, suffix = base, 2
                while anchor in used:
                    anchor, suffix = f"{base}-{suffix}", suffix + 1
                used.add(anchor)
                node["anchor"] = anchor
        for page, entries in self.anchor_aliases.items():
            seen = set()
            targets = set(result[page])
            for entry in entries:
                pair = entry.split(":")
                if (len(pair) != 2 or not re.fullmatch("[a-z0-9-]+", pair[0]) or
                        pair[0] in seen or pair[0] in result[page] or
                        (pair[1] and pair[1] not in targets)):
                    self.errors.append("Ungültige Abschnittsweiterleitung: " + entry)
                    continue
                seen.add(pair[0])
                result[page].add(pair[0])
        return result


def check_docs(root: Path = ROOT) -> int:
    name = config(root)["name"]
    source = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    parser = Handbook()
    parser.feed(source)
    readme = (root / "README.md").read_text(encoding="utf-8-sig")
    parser.links.extend(re.findall(r'\]\(docs/index\.html#/(.*?)\)', readme))
    anchors = parser.anchors()
    missing = {"ueberblick", "installation", "schnellstart", "befehle", "betrieb", "changelog"} - parser.pages.keys()
    parser.errors += ["Handbuchseite fehlt: " + page for page in sorted(missing)]
    parser.errors += ["Kapitelalias kollidiert mit einer Seite: " + alias
                      for alias in parser.aliases if alias in parser.pages]
    for link in parser.links:
        parts = [unquote(part) for part in link.split("/")]
        parts[0] = parser.aliases.get(parts[0], parts[0])
        if len(parts) > 2 or parts[0] not in parser.pages or (len(parts) == 2 and parts[1] not in anchors[parts[0]]):
            parser.errors.append("Ungültiger Abschnittslink: #/" + link)
    for link in parser.local_links:
        path = root / "docs" / link
        if not path.resolve().is_relative_to((root / "docs").resolve()) or not path.is_file():
            parser.errors.append("Lokaler Handbuchverweis fehlt oder liegt außerhalb von docs/: " + link)
    parser.errors += ["Externe Handbuchressource: " + asset for asset in parser.external_assets]
    for identifier in ("searchBtn", "themeBtn", "sidebar", "main"):
        if identifier not in parser.ids:
            parser.errors.append("Bedienelement im Handbuch fehlt: " + identifier)
    if 'id="q"' not in source:
        parser.errors.append("Das Suchfeld im Handbuch fehlt.")
    if name + " Handbuch" not in source:
        parser.errors.append("Der Handbuchtitel passt nicht zum Projekt.")
    if re.search(r"@(?:VERSION|PAGES|STYLE|SCRIPT|YAML:[a-z]+)@", source):
        parser.errors.append("Nicht ersetzter Platzhalter im Handbuch.")
    if re.search(r"url\(\s*['\"]?https?://", source, re.IGNORECASE):
        parser.errors.append("Externe CSS-Ressource im Offline-Handbuch.")
    if re.search(r"@import\s", source, re.I):
        parser.errors.append("CSS-Import im Offline-Handbuch.")
    for path in (root / "README.md", root / "docs/index.html"):
        text = path.read_text(encoding="utf-8-sig")
        if "\ufffd" in text or re.search(r"(?:Ã[\u0080-\u00bf]|Â[\u0080-\u00bf]|â€)", text):
            parser.errors.append("Beschädigter UTF-8-Text in " + path.name)
    for node in parser.references:
        path = root / node["attrs"]["data-source"]
        if not path.resolve().is_relative_to(root.resolve()) or path.is_symlink() or not path.is_file():
            parser.errors.append("Konfigurationsquelle fehlt: " + str(path))
        elif "".join(node["text"]).strip() != path.read_text(encoding="utf-8-sig").strip():
            parser.errors.append("Veraltete Standardkonfiguration im Handbuch: " + str(path))
    if parser.errors:
        raise ValueError("\n".join(parser.errors))
    return len(parser.pages)


def check_versions(root: Path = ROOT, tag: str | None = None) -> str:
    settings = config(root)
    name = settings["name"]
    current = version(root)
    if current in settings.get("retiredVersions", []):
        raise ValueError("Diese Version wurde zurückgezogen und darf nicht erneut veröffentlicht werden: " + current)
    if tag is not None and tag != current:
        raise ValueError(f"Tag {tag} passt nicht zur Projektversion {current}.")
    modules = module_names(root)
    for module in modules:
        pom = ET.parse(root / module / "pom.xml")
        if pom.findtext("m:parent/m:version", namespaces=NS) != current:
            raise ValueError("Abweichende Parent-Version in " + module)
        module_version = pom.findtext("m:version", namespaces=NS)
        if module_version and module_version != current:
            raise ValueError("Abweichende Modulversion in " + module)
    for item in settings["artifacts"]:
        if item["module"] not in modules:
            raise ValueError("Unbekanntes Release-Modul: " + item["module"])
    readme = (root / "README.md").read_text(encoding="utf-8-sig")
    docs = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    changelog = re.search(r'<section\b[^>]*data-id="changelog"[^>]*>(.*?)</section>', docs, re.S)
    first = re.search(r'<h2\b[^>]*>([^<]+)</h2>(.*?)(?=<h2\b|$)', changelog[1], re.S) if changelog else None
    required = {
        "README heading": f"# {name} {current}" in readme.splitlines(),
        "README release link": f"https://github.com/{settings['repository']}/releases/tag/{current}" in readme,
        "handbook version": f'<span class="ver">{current}</span>' in docs,
        "neuester Changelog-Eintrag": first is not None and first[1].strip() == current,
        "local agent rules excluded": "AGENTS.md" in (root / ".gitignore").read_text(encoding="utf-8").splitlines(),
    }
    failures = [key for key, valid in required.items() if not valid]
    if failures:
        raise ValueError("Widersprüchliche Projektangaben: " + ", ".join(failures))
    date = re.search(r'<time\s+datetime="(\d{4}-\d{2}-\d{2})"', first[2])
    if tag:
        from datetime import date as calendar_date
        if not date:
            raise ValueError("Im aktuellen Changelog-Eintrag fehlt das Veröffentlichungsdatum.")
        try:
            calendar_date.fromisoformat(date[1])
        except ValueError:
            raise ValueError("Ungültiges Veröffentlichungsdatum im Changelog.") from None
    return current


def module_names(root: Path) -> list[str]:
    modules = [m.text or "" for m in ET.parse(root / "pom.xml").findall("m:modules/m:module", NS)]
    if len(modules) != len(set(modules)) or any(not re.fullmatch(r"[a-z][a-z0-9.-]*", m) for m in modules):
        raise ValueError("Ungültige oder doppelte Modulpfade in pom.xml.")
    for module in modules:
        path = root / module
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Ein Modul zeigt aus dem Projekt heraus: " + module)
    return modules


def jar_path(root: Path, artifact: dict, current: str) -> Path:
    name = config(root)["name"]
    module = artifact["module"]
    return root / module / "target" / f"{name}-{current}-{module}.jar"


def check_jar(path: Path, artifact: dict, current: str, settings: dict) -> dict:
    if not path.is_file():
        raise ValueError("Gebaute JAR fehlt: " + str(path))
    with zipfile.ZipFile(path) as archive:
        entries = archive.namelist()
        if len(entries) != len(set(entries)) or archive.testzip() is not None:
            raise ValueError("Beschädigte oder doppelte Archiveinträge in " + path.name)
        for entry in entries:
            parts = entry.replace("\\", "/").split("/")
            if entry.startswith(("/", "\\")) or ".." in parts or re.match(r"^[A-Za-z]:", entry):
                raise ValueError("Unsicherer Archivpfad in " + path.name + ": " + entry)
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
            raise ValueError("Lokale Dateien sind versehentlich enthalten in " + path.name)
        for entry in settings.get("requiredEntries", []):
            if entry not in entries:
                raise ValueError(f"{path.name}: required entry is missing: {entry}")
        forbidden = tuple(settings.get("forbiddenPrefixes", [])) + ("org/bukkit/", "com/velocitypowered/", "org/junit/", "org/mockito/")
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
    sources = source_files(root)
    dist = root / "dist"
    tools = root / ".tools"
    for path in (dist, tools):
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Die Release-Ausgabe muss innerhalb des Projekts liegen: " + str(path))
    tools.mkdir(exist_ok=True)
    # Erst vollständig vorbereiten. Ein fehlerhafter neuer Build darf die letzte Übergabe nicht löschen.
    with tempfile.TemporaryDirectory(prefix="release-", dir=tools) as directory:
        stage = Path(directory)
        output = stage / "dist"
        output.mkdir()
        sums = []
        locals_to_publish = []
        for item, checked in zip(settings["artifacts"], checks, strict=True):
            source = jar_path(root, item, current)
            destination = output / source.name
            shutil.copyfile(source, destination)
            if hashlib.sha256(destination.read_bytes()).hexdigest() != checked["sha256"]:
                raise ValueError("Eine JAR wurde während der Release-Vorbereitung verändert.")
            sums.append(checked["sha256"] + "  " + destination.name)
            local = root / f"{settings['name']}-{current}-({item['module']}).jar"
            if local.is_symlink():
                raise ValueError("Die lokale JAR-Kopie ist ein symbolischer Link: " + str(local))
            locals_to_publish.append((destination, local))
        archive_path = output / f"{settings['name']}-{current}-sources.zip"
        write_sources(archive_path, root, current, settings["name"], sources)
        sums.append(hashlib.sha256(archive_path.read_bytes()).hexdigest() + "  " + archive_path.name)
        write_text(output / "SHA256SUMS.txt", "\n".join(sums) + "\n")
        write_text(output / "RELEASE_NOTES.md", release_notes(root, settings["name"], current))
        write_text(output / "VERIFICATION.json", json.dumps(checks, indent=2) + "\n")
        previous = stage / "previous-dist"
        backups = []
        for _, local in locals_to_publish:
            backup = stage / local.name
            if local.exists():
                shutil.copyfile(local, backup)
            backups.append((local, backup))
        had_dist = dist.exists()
        published = False
        try:
            if had_dist:
                dist.replace(previous)
            output.replace(dist)
            published = True
            for staged, local in locals_to_publish:
                temporary = local.with_name(local.name + "." + uuid.uuid4().hex + ".tmp")
                try:
                    shutil.copyfile(dist / staged.name, temporary)
                    temporary.replace(local)
                finally:
                    temporary.unlink(missing_ok=True)
        except OSError:
            for local, backup in backups:
                if backup.exists():
                    backup.replace(local)
                elif published:
                    local.unlink(missing_ok=True)
            if published:
                dist.replace(output)
            if previous.exists():
                previous.replace(dist)
            raise
    return checks


def release_notes(root: Path, name: str, current: str) -> str:
    docs = (root / "docs/index.html").read_text(encoding="utf-8-sig")
    changelog = re.search(r'<section\b[^>]*data-id="changelog"[^>]*>(.*?)</section>', docs, re.S)
    if changelog is None:
        raise ValueError("Der Changelog fehlt.")
    entries = re.split(r"<h2\b[^>]*>[^<]*</h2>", changelog.group(1))
    if len(entries) < 2:
        raise ValueError("Der aktuelle Release-Eintrag fehlt.")
    parser = ReleaseText()
    parser.feed(entries[1])
    notes = re.sub(r"\n[ \t]+", "\n", "".join(parser.parts).strip())
    notes = re.sub(r"\n{3,}", "\n\n", notes)
    return f"# {name} {current}\n\n{notes}\n"


def write_sources(path: Path, root: Path, current: str, name: str, files=None) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in files if files is not None else source_files(root):
            info = zipfile.ZipInfo(f"{name}-{current}/" + source.relative_to(root).as_posix(),
                                   date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, source.read_bytes())


def source_files(root: Path = ROOT) -> list[Path]:
    """Nimmt auch Binärressourcen und SQL-Dateien mit; lokale Daten gehören nicht ins Quellarchiv."""
    root_files = {"pom.xml", "README.md", "LICENSE", "THIRD_PARTY.md", "build_all_jars.bat",
                  ".editorconfig", ".gitattributes", ".gitignore"}
    modules = module_names(root)
    directories_allowed = {".mvn", ".github", "scripts", "docs", *modules}
    candidates = [root / name for name in root_files if (root / name).is_file()]
    for directory in sorted(directories_allowed):
        base = root / directory
        if base.is_symlink() or not base.resolve().is_relative_to(root.resolve()):
            raise ValueError("Quellordner ist verlinkt oder liegt außerhalb des Projekts: " + str(base))
        for current, directories, names in os.walk(base, followlinks=False):
            directories[:] = sorted(d for d in directories if d not in {
                "target", "dist", "__pycache__", "node_modules", "test-server", ".tools", ".git", ".secrets", ".idea", ".vscode"
            })
            for child in directories:
                if (Path(current) / child).is_symlink():
                    raise ValueError("Verlinkte Quellordner werden nicht ins Archiv aufgenommen.")
            for name in sorted(names):
                path = Path(current) / name
                if name == "AGENTS.md" or name.startswith(".env") or path.suffix in {".log", ".tmp", ".bak", ".pyc"}:
                    continue
                if path.is_symlink():
                    raise ValueError("Verlinkte Quelldatei: " + str(path))
                candidates.append(path)
    if (root / ".git").exists():
        result = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                check=True, capture_output=True)
        included = {os.fsdecode(path) for path in result.stdout.split(b"\0") if path}
        candidates = [p for p in candidates if p.relative_to(root).as_posix() in included]
    for path in candidates:
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Die Quellarchiv-Datei liegt außerhalb des Projekts: " + str(path))
    return sorted(candidates, key=lambda path: path.relative_to(root).as_posix())


def set_version(root: Path, target: str | None = None, increment: str | None = None) -> str:
    if target and increment:
        raise ValueError("Entweder eine Version oder eine Erhöhung angeben.")
    old = version(root)
    retired = set(config(root).get("retiredVersions", []))
    if increment:
        major, minor, patch = map(int, VERSION.fullmatch(old).groups())
        if increment == "minor":
            minor, patch = minor + 1, 0
        elif increment == "patch":
            patch += 1
        else:
            raise ValueError("Automatisch lassen sich nur Patch und Minor erhöhen.")
        target = f"v{major}.{minor}.{patch}"
        while target in retired:
            patch += 1
            target = f"v{major}.{minor}.{patch}"
    if not target:
        return old
    if VERSION.fullmatch(target) is None:
        raise ValueError("Die Version muss vMajor.Minor.Patch entsprechen.")
    if target in retired:
        raise ValueError("Eine zurückgezogene Version darf nicht erneut verwendet werden: " + target)
    paths = [root / "pom.xml"] + [root / module / "pom.xml" for module in module_names(root)]
    updates = {}
    for path in paths:
        text = path.read_text(encoding="utf-8-sig")
        pattern = re.compile(r"<version>" + re.escape(old) + r"</version>")
        if not pattern.search(text):
            raise ValueError("Versionsangabe fehlt in " + str(path))
        updates[path] = pattern.sub("<version>" + target + "</version>", text, count=1)
    velocity = root / "velocity/src/main/java/de/lightrixx/punish/velocity/PunishVelocity.java"
    if velocity.exists():
        text = velocity.read_text(encoding="utf-8-sig")
        if text.count('version = "' + old + '"') != 1:
            raise ValueError("Die Velocity-Annotation passt nicht zur bisherigen Projektversion.")
        updates[velocity] = text.replace('version = "' + old + '"', 'version = "' + target + '"')
    previous = {path: path.read_text(encoding="utf-8-sig") for path in updates}
    written = []
    try:
        for path, text in updates.items():
            write_text(path, text)
            written.append(path)
    except OSError as failure:
        failed_rollbacks = []
        for path in reversed(written):
            try:
                write_text(path, previous[path])
            except OSError:
                failed_rollbacks.append(str(path))
        if failed_rollbacks:
            raise OSError("Versionsänderung abgebrochen. Diese Dateien konnten nicht zurückgesetzt werden: " + ", ".join(failed_rollbacks)) from failure
        raise
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
        print(f"Handbuch geprüft: {check_docs(root)} Seiten, alle Ressourcen offline verfügbar.")
    elif command == "release":
        print(json.dumps(prepare_release(root, args.tag), indent=2))
    elif command == "version":
        print(set_version(root, args.version, args.increment))
        print("README und Handbuch samt Changelog vor dem nächsten vollständigen Build aktualisieren.")
    else:
        print("Version geprüft: " + check_versions(root, args.tag))
        if args.jar:
            print(json.dumps(check_jars(root, args.tag), indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as failure:
        raise SystemExit(str(failure)) from None
