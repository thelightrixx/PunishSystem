"""Regression tests for release identity and offline handbook validation."""
from pathlib import Path
import tempfile
import unittest
import zipfile

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


if __name__ == "__main__":
    unittest.main()
