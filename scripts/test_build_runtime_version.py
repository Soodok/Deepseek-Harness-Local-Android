#!/usr/bin/env python3
"""Test version forwarding at the Android runtime collector boundary.

Run: python3 scripts/test_build_runtime_version.py
Uses the unmodified build script, but a collector that records its inputs and
exits before any downloads, npm, ZIP creation, or Android runtime execution.
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


DEFAULT_VERSION = "0.2.1-rc.2"
SOURCE = Path(__file__).resolve().with_name("build-runtime.sh")
BASH = shutil.which("bash")


class RuntimeVersionTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="dsh-runtime-version-")).resolve()
        self.assertFalse(self.root.is_symlink())
        self.assertEqual(self.root.parent, Path(tempfile.gettempdir()).resolve())

    def tearDown(self):
        # Only remove the absolute, owned temporary directory created above.
        self.assertFalse(self.root.is_symlink())
        self.assertEqual(self.root.resolve(), self.root)
        self.assertEqual(self.root.parent, Path(tempfile.gettempdir()).resolve())
        shutil.rmtree(self.root)

    def check_version(self, abi, inherited, cli, expected):
        self.assertIsNotNone(BASH, "Bash is required")
        case = self.root / abi
        scripts, tools, tmp, home = (case / name for name in ("scripts", "tools", "tmp", "home"))
        for directory in (scripts, tools, tmp, home):
            directory.mkdir(parents=True)
        build = scripts / "build-runtime.sh"
        shutil.copyfile(SOURCE, build)
        self.assertEqual(build.read_bytes(), SOURCE.read_bytes())
        collector = scripts / "collect-termux-runtime.sh"
        collector.write_text(
            f'#!{BASH}\n'
            f'printf "%s\\n" "${{DSH_VERSION:-{DEFAULT_VERSION}}}" "$2" > "$TEST_CAPTURE"\n'
            'exit 73\n', encoding="utf-8")
        # Filesystem-only adapters: Android toybox lacks realpath -m, and the
        # production EXIT trap must only delete an owned fixture workdir.
        helpers = {
            "realpath": (
                'assert sys.argv[1] == "-m" and len(sys.argv) == 3\n'
                'target = Path(sys.argv[2]).resolve()\n'
                'assert target.is_relative_to(Path(os.environ["TEST_CASE_ROOT"]).resolve())\n'
                'print(target)\n'),
            "rm": (
                'assert len(sys.argv) == 3 and sys.argv[1] == "-rf"\n'
                'raw = Path(sys.argv[2])\n'
                'assert not raw.is_symlink()\n'
                'target = raw.resolve(strict=True)\n'
                'assert target.parent == Path(os.environ["TMPDIR"]).resolve(strict=True)\n'
                'shutil.rmtree(target)\n'),
        }
        for name, body in helpers.items():
            helper = tools / name
            helper.write_text(
                f'#!{sys.executable}\nimport os, sys, shutil\nfrom pathlib import Path\n' + body,
                encoding="utf-8")
            helper.chmod(0o755)
        capture = case / "collector-input.txt"
        env = {
            "PATH": str(tools) + os.pathsep + os.environ["PATH"],
            "HOME": str(home), "TMPDIR": str(tmp), "OPENSSL_CONF": "/dev/null",
            "TEST_CAPTURE": str(capture), "TEST_CASE_ROOT": str(case),
        }
        if "LD_LIBRARY_PATH" in os.environ:
            env["LD_LIBRARY_PATH"] = os.environ["LD_LIBRARY_PATH"]
        if inherited is not None:
            env["DSH_VERSION"] = inherited
        args = [BASH, str(build), "--abi", abi, "--out", str(case / "runtime.zip")]
        if cli is not None:
            args.extend(["--dsh-version", cli])
        result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 73, result.stdout + result.stderr)
        self.assertIn("dsh version:  " + expected, result.stdout)
        self.assertEqual(capture.read_text().splitlines(),
                         [expected, {"arm64-v8a": "aarch64", "x86_64": "x86_64"}[abi]])
        self.assertFalse((case / "runtime.zip").exists())

    def test_cli_without_inherited_version(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi):
                self.check_version(abi, None, "0.0.0-fixture-cli", "0.0.0-fixture-cli")

    def test_environment_without_cli(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi):
                self.check_version(abi, "0.0.0-fixture-env", None, "0.0.0-fixture-env")

    def test_cli_overrides_environment(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi):
                self.check_version(abi, "0.0.0-fixture-env", "0.0.0-fixture-cli", "0.0.0-fixture-cli")

    def test_existing_default_is_preserved(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi):
                self.check_version(abi, None, None, DEFAULT_VERSION)


if __name__ == "__main__":
    unittest.main(verbosity=2)
