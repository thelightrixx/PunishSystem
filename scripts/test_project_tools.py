"""Regression tests for release identity and offline handbook validation."""
from pathlib import Path
from unittest import mock
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
import project_tools as tools

from project_tools import Handbook, check_jar, field, source_files, VERSION


class ProjectToolsTest(unittest.TestCase):
    def test_versions_are_stable_and_have_a_lowercase_v(self):
        for invalid in ("1.2.3", "V1.2.3", "v01.2.3", "v1.2.3-beta", "v1.2", "v1.2.3+build"):
            self.assertIsNone(VERSION.fullmatch(invalid))
        self.assertIsNotNone(VERSION.fullmatch("v0.9.0"))

    def test_yaml_metadata_ignores_quotes_and_comments(self):
        self.assertEqual("v1.2.3", field("version: 'v1.2.3' # stable\n", "version"))

    def test_duplicate_pages_and_ids_are_rejected(self):
        parser = Handbook()
        parser.feed('<section class="page" data-id="installation" id="main"></section>' * 2)
        self.assertEqual(2, len(parser.errors))

    def test_external_assets_are_detected(self):
        parser = Handbook()
        parser.feed('<script src="https://example.com/app.js"></script><img src="data:image/png;base64,AAAA">')
        self.assertEqual(["https://example.com/app.js"], parser.external_assets)

    def make_jar(self, directory, version="v1.2.3", api="1.21.8", main=True, major=65):
        path = directory / "Test.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("plugin.yml", f"name: Test\nversion: {version}\nmain: de.lightrixx.Test\napi-version: '{api}'\n")
            if main:
                archive.writestr("de/lightrixx/Test.class", b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big"))
        return path

    def test_wrong_version_api_entrypoint_and_bytecode_are_rejected(self):
        artifact = {"module": "paper-1.21", "metadata": "plugin.yml"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = check_jar(self.make_jar(root), artifact, "v1.2.3", {"name": "Test"})
            self.assertEqual(1, valid["classes"])
            for kwargs in ({"version": "v1.2.2"}, {"api": "26.1"}, {"main": False}, {"major": 69}):
                with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                    check_jar(self.make_jar(root, **kwargs), artifact, "v1.2.3", {"name": "Test"})

    def test_source_archive_excludes_local_data_and_keeps_build_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>paper</module></modules></project>', encoding="utf-8")
            for name in ("scripts/project.json", "paper/src/main/java/Main.java", ".github/workflows/build.yml",
                         "paper/target/Generated.java", ".tools/private.py", "AGENTS.md", "docs/.env",
                         "scripts/__pycache__/private.py", "docs/private.log", ".secrets/private.txt"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture", encoding="utf-8")
            files = {p.relative_to(root).as_posix() for p in source_files(root)}
            self.assertEqual({"pom.xml", "scripts/project.json", "paper/src/main/java/Main.java",
                              ".github/workflows/build.yml"}, files)


class ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>v1.2.3</version><modules><module>paper</module></modules></project>')
        self.write("paper/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><version>v1.2.3</version></parent></project>')
        self.write("scripts/project.json", json.dumps({"name": "Test", "repository": "example/Test", "artifacts": [{"module": "paper", "metadata": "plugin.yml", "apiVersion": "1.21.8"}]}))
        self.write("README.md", "# Test v1.2.3\nhttps://github.com/example/Test/releases/tag/v1.2.3\n")
        self.write(".gitignore", "AGENTS.md\n.tools/\n**/target/\ndocs/private.yml\n")
        self.docs = '<title>Test Handbuch</title><main id="main"><span class="ver">v1.2.3</span><button id="searchBtn"></button><button id="themeBtn"></button><nav id="sidebar"></nav><script>var input = \'id="q"\';</script>'
        for page in ("ueberblick", "installation", "schnellstart", "befehle", "betrieb"):
            self.docs += f'<section class="page" data-id="{page}"><h1>{page}</h1></section>'
        self.docs += '<section class="page" data-id="changelog"><h1>Changelog</h1><h2 data-anchor="v1-2-3">v1.2.3</h2><p><time datetime="2026-11-28">28.11.2026</time></p><ul><li>Eine Korrektur.</li></ul><h2>v1.2.2</h2><p>Alte Änderung.</p></section></main>'
        self.write("docs/index.html", self.docs)
        path = self.root / "paper/target/Test-v1.2.3-paper.jar"
        path.parent.mkdir(parents=True)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("plugin.yml", "name: Test\nversion: v1.2.3\nmain: de.lightrixx.Test\napi-version: '1.21.8'\n")
            archive.writestr("de/lightrixx/Test.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x41")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_consistent_project_tag_and_module_version(self):
        self.assertEqual("v1.2.3", tools.check_versions(self.root, "v1.2.3"))
        with self.assertRaises(ValueError):
            tools.check_versions(self.root, "v1.2.2")
        self.write("paper/pom.xml", (self.root / "paper/pom.xml").read_text().replace("v1.2.3", "v1.2.2"))
        with self.assertRaises(ValueError):
            tools.check_versions(self.root)

    def test_current_changelog_must_be_first(self):
        self.write("docs/index.html", self.docs.replace('<h2 data-anchor=', '<h2>v1.2.2</h2><h2 data-anchor=', 1))
        with self.assertRaises(ValueError):
            tools.check_versions(self.root)

    def test_release_requires_real_calendar_date(self):
        for date in ("", "2026-02-30", "2026-13-01"):
            with self.subTest(date=date):
                self.write("docs/index.html", self.docs.replace("2026-11-28", date))
                with self.assertRaises(ValueError):
                    tools.check_versions(self.root, "v1.2.3")

    def test_links_include_generated_and_explicit_anchors(self):
        body = '<h2>Größe ändern</h2><h3>Größe ändern</h3><details><summary>Mit <code>Code</code></summary></details><div class="cmd-card" data-cmd="/test"></div><h2 data-anchor="fest">Neuer Titel</h2>'
        for anchor in ("groesse-aendern", "groesse-aendern-2", "mit-code", "test", "fest"):
            body += f'<a href="#/betrieb/{anchor}">Link</a>'
        docs = self.docs.replace('<h1>betrieb</h1>', '<h1>betrieb</h1>' + body)
        self.write("docs/index.html", docs)
        self.assertEqual(6, tools.check_docs(self.root))
        for link in ("#/fehlt", "#/betrieb/fehlt", "#/betrieb/fest/zu-viel"):
            self.write("docs/index.html", docs.replace("#/betrieb/fest", link))
            with self.assertRaisesRegex(ValueError, "Abschnittslink"):
                tools.check_docs(self.root)

    def test_external_assets_and_damaged_text(self):
        for extra in ('<script src="https://example.invalid/app.js"></script>', '<style>@import "remote.css";</style>', '\ufffd', 'Ã¼'):
            self.write("docs/index.html", self.docs + extra)
            with self.assertRaises(ValueError):
                tools.check_docs(self.root)

    def test_configuration_reference_matches_source(self):
        self.write("paper/src/main/resources/config.yml", 'message: "<green>Hallo</green>"\n')
        self.write("docs/index.html", self.docs + '<pre data-source="paper/src/main/resources/config.yml">message: "&lt;green&gt;Hallo&lt;/green&gt;"</pre>')
        tools.check_docs(self.root)
        self.write("paper/src/main/resources/config.yml", "message: geändert\n")
        with self.assertRaisesRegex(ValueError, "Standardkonfiguration"):
            tools.check_docs(self.root)

    def test_source_archive_keeps_resources_and_omits_local_data(self):
        keep = {"paper/src/main/resources/icon.png", "paper/src/main/resources/schema.sql", "paper/src/main/resources/LICENSE", "paper/src/main/resources/messages.properties", "paper/src/test/resources/fixture.bin", ".github/workflows/build.yml"}
        exclude = {"paper/target/Generated.java", ".tools/private.py", "AGENTS.md", "docs/.env", "scripts/__pycache__/private.py", "docs/private.log", ".secrets/private.txt"}
        for name in keep | exclude:
            self.write(name, "fixture")
        files = {p.relative_to(self.root).as_posix() for p in tools.source_files(self.root)}
        self.assertTrue(keep <= files, keep - files)
        self.assertFalse(exclude & files)

    def test_git_ignored_files_stay_local(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True, capture_output=True)
        self.write("docs/private.yml", "local")
        self.assertNotIn(self.root / "docs/private.yml", tools.source_files(self.root))

    def test_source_archive_is_reproducible(self):
        first, second = self.root / "first.zip", self.root / "second.zip"
        tools.write_sources(first, self.root, "v1.2.3", "Test")
        os.utime(self.root / "README.md", (1000000000, 1000000000))
        tools.write_sources(second, self.root, "v1.2.3", "Test")
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_invalid_manifest_and_module_paths(self):
        for value in ("../outside", "/outside", "paper/../../outside", "paper"):
            settings = {"name": "Test", "repository": "example/Test", "artifacts": [{"module": value, "metadata": "../../local.yml"}]}
            self.write("scripts/project.json", json.dumps(settings))
            with self.assertRaises(ValueError):
                tools.config(self.root)
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>../outside</module></modules></project>')
        with self.assertRaises(ValueError):
            tools.source_files(self.root)

    def test_failed_preparation_preserves_previous_release(self):
        self.write("dist/previous.jar", "previous")
        self.write("Test-v1.2.3-(paper).jar", "previous-local")
        with mock.patch.object(tools, "write_sources", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                tools.prepare_release(self.root)
        self.assertEqual("previous", (self.root / "dist/previous.jar").read_text())
        self.assertEqual("previous-local", (self.root / "Test-v1.2.3-(paper).jar").read_text())

    def test_failed_publication_rolls_back_dist_and_local_copy(self):
        self.write("dist/previous.jar", "previous")
        self.write("Test-v1.2.3-(paper).jar", "previous-local")
        original = tools.shutil.copyfile
        def fail_local(source, destination):
            if Path(destination).name.startswith("Test-v1.2.3-(paper).jar."):
                raise OSError("local copy failed")
            return original(source, destination)
        with mock.patch.object(tools.shutil, "copyfile", side_effect=fail_local):
            with self.assertRaises(OSError):
                tools.prepare_release(self.root)
        self.assertEqual("previous", (self.root / "dist/previous.jar").read_text())
        self.assertEqual("previous-local", (self.root / "Test-v1.2.3-(paper).jar").read_text())

    def test_bad_tag_creates_no_release_files(self):
        with self.assertRaises(ValueError):
            tools.prepare_release(self.root, "v1.2.2")
        self.assertFalse((self.root / "dist").exists())

    def test_release_files_checksums_and_notes(self):
        self.assertEqual(1, len(tools.prepare_release(self.root, "v1.2.3")))
        for line in (self.root / "dist/SHA256SUMS.txt").read_text().splitlines():
            digest, name = line.split(None, 1)
            self.assertEqual(digest, hashlib.sha256((self.root / "dist" / name.strip()).read_bytes()).hexdigest())
        notes = (self.root / "dist/RELEASE_NOTES.md").read_text(encoding="utf-8")
        self.assertIn("Eine Korrektur.", notes)
        self.assertNotIn("Alte Änderung.", notes)

    def test_retired_version_is_skipped_and_not_reused(self):
        settings = tools.config(self.root)
        settings["retiredVersions"] = ["v1.2.4"]
        self.write("scripts/project.json", json.dumps(settings))
        with self.assertRaises(ValueError):
            tools.set_version(self.root, "v1.2.4")
        self.assertEqual("v1.2.5", tools.set_version(self.root, increment="patch"))

    def test_version_update_preserves_documented_history(self):
        before = (self.root / "docs/index.html").read_bytes()
        self.assertEqual("v1.3.0", tools.set_version(self.root, increment="minor"))
        self.assertEqual(before, (self.root / "docs/index.html").read_bytes())
        self.assertIn("v1.3.0", (self.root / "paper/pom.xml").read_text())

    def test_unsafe_jar_paths_and_bundled_platform_classes(self):
        path = self.root / "paper/target/Test-v1.2.3-paper.jar"
        pristine = path.read_bytes()
        for name in ("../secret", "/absolute", "C:/absolute", "org/bukkit/World.class", "org/junit/Test.class", "org/mockito/Mock.class"):
            with self.subTest(name=name):
                path.write_bytes(pristine)
                with zipfile.ZipFile(path, "a") as archive:
                    archive.writestr(name, "invalid")
                with self.assertRaises(ValueError):
                    tools.check_jars(self.root)

    def test_required_libraries_and_versions(self):
        path = self.root / "paper/target/Test-v1.2.3-paper.jar"
        pristine = path.read_bytes()
        settings = tools.config(self.root)
        settings.update(requiredEntries=["META-INF/LICENSE"], libraryVersions={"META-INF/library_version.txt": "2.14.0"})
        artifact = settings["artifacts"][0]
        for value in (None, "2.13.0", "2.14.0"):
            path.write_bytes(pristine)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("META-INF/LICENSE", "license")
                if value:
                    archive.writestr("META-INF/library_version.txt", value)
            if value == "2.14.0":
                tools.check_jar(path, artifact, "v1.2.3", settings)
            else:
                with self.assertRaises(ValueError):
                    tools.check_jar(path, artifact, "v1.2.3", settings)

    def test_failed_version_update_restores_previous_poms(self):
        original = tools.write_text
        calls = []
        def fail_second(path, text):
            calls.append(path)
            if len(calls) == 2:
                raise OSError("disk full")
            original(path, text)
        before = {p: p.read_bytes() for p in self.root.rglob("pom.xml")}
        with mock.patch.object(tools, "write_text", side_effect=fail_second):
            with self.assertRaises(OSError):
                tools.set_version(self.root, increment="patch")
        for path, data in before.items():
            self.assertEqual(data, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
