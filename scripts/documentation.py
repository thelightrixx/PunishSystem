"""Gemeinsame Textbausteine und Kapitelregeln für die Offline-Handbücher."""
from pathlib import Path
import re


PAGES = {
    "ueberblick": ("Überblick und Wegweiser", "Start"),
    "installation": ("Installation", "Start"),
    "schnellstart": ("Schnellstart", "Start"),
    "befehle": ("Befehlsreferenz", "Befehlsreferenz"),
    "config-uebersicht": ("Dateien und Zuständigkeiten", "Konfiguration und Rechte"),
    "rechte": ("Berechtigungen", "Konfiguration und Rechte"),
    "speicher": ("Speicherung und Sicherungen", "Betrieb"),
    "betrieb": ("Betrieb und Updates", "Betrieb"),
    "changelog": ("Changelog", "Betrieb"),
    "fehlerbehebung": ("Fehlerbehebung", "Hilfe"),
    "faq": ("Häufige Fragen", "Hilfe"),
    "projektstandard": ("Entwicklung", "Projekt"),
    "lizenz": ("Lizenzen", "Projekt"),
}
README_HEADINGS = (
    "Einrichten", "Benutzen", "Betrieb und Updates", "Nachlesen",
    "Download und eigener Build", "Lizenz",
)
HANDBOOK_FRAGMENTS = {
    "overview-cards", "navigation-help", "wayfinder", "offline-help",
    "command-syntax", "development-guide", "interface-license", "font-licenses",
}
README_FRAGMENTS = {"readme-help", "readme-build", "readme-update-note"}
FRAGMENT = re.compile(
    r"<!-- SHARED:([a-z-]+) -->(.*?)<!-- /SHARED:\1 -->", re.S
)


def fragments(text: str) -> dict[str, str]:
    result = {}
    for match in FRAGMENT.finditer(text):
        name, content = match.groups()
        if name in result:
            raise ValueError("Gemeinsamer Handbuchtext mehrfach vorhanden: " + name)
        result[name] = content
    markers = re.findall(r"<!-- /?SHARED:([a-z-]+) -->", text)
    if len(markers) != 2 * len(result):
        raise ValueError("Unvollständige Markierung eines gemeinsamen Handbuchtexts.")
    return result


def replace_fragments(text: str, source: dict[str, str]) -> str:
    if fragments(text).keys() != source.keys():
        raise ValueError("Die gemeinsamen Handbuchtexte stimmen nicht mit der Vorlage überein.")
    return FRAGMENT.sub(lambda match: "<!-- SHARED:" + match[1] + " -->" +
                        source[match[1]] + "<!-- /SHARED:" + match[1] + " -->", text)


def check_structure(root: Path, handbook: str) -> None:
    """Prüft die gemeinsamen Einstiege, ohne fachliche Zusatzkapitel vorzugeben."""
    sections = {}
    errors = []
    for match in re.finditer(r"<section\b([^>]*)>(.*?)</section>", handbook, re.S):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', match[1]))
        sections[attrs.get("data-id")] = (attrs, match[2])
    for identifier, (title, group) in PAGES.items():
        if identifier not in sections:
            errors.append("Gemeinsames Handbuchkapitel fehlt: " + identifier)
            continue
        attrs, body = sections[identifier]
        if attrs.get("data-title") != title or attrs.get("data-group") != group:
            errors.append("Kapiteltitel oder Navigationsgruppe weicht ab: " + identifier)
        headings = re.findall(r"<h1\b[^>]*>(.*?)</h1>", body, re.S)
        expected = root.name + " Handbuch" if identifier == "ueberblick" else title
        if headings != [expected]:
            errors.append("Kapitelüberschrift weicht ab: " + identifier)
    readme = (root / "README.md").read_text(encoding="utf-8-sig")
    if fragments(handbook).keys() != HANDBOOK_FRAGMENTS:
        errors.append("Die gemeinsamen Handbuchbausteine sind unvollständig.")
    if fragments(readme).keys() != README_FRAGMENTS:
        errors.append("Die gemeinsamen README-Bausteine sind unvollständig.")
    if [identifier for identifier in sections if identifier in PAGES] != list(PAGES):
        errors.append("Reihenfolge der gemeinsamen Handbuchkapitel weicht ab.")
    if f'<html lang="de" data-project="{root.name}"' not in handbook:
        errors.append("Die Handbuchpalette ist nicht dem Projekt zugeordnet.")
    if re.findall(r"^## (.+)$", readme, re.M) != list(README_HEADINGS):
        errors.append("Aufbau der README weicht von der gemeinsamen Vorlage ab.")
    if errors:
        raise ValueError("\n".join(errors))
