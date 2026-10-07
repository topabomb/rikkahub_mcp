"""Failure-oriented tests for the pinned materials and final APK checks; all APK tools are isolated fixtures."""
import json
import shutil
import tempfile
import unittest
import subprocess
import sys
from pathlib import Path

from android_contract import ROOT, CONTRACTS, validate_contracts
from verify_android_apk import check_tag, inspect_apk
from unittest.mock import patch
import zipfile


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.contracts = Path(self.temp.name) / "contracts"
        shutil.copytree(CONTRACTS, self.contracts)

    def mutate(self, path, transform):
        target = self.contracts / path
        value = json.loads(target.read_text(encoding="utf-8"))
        transform(value)
        target.write_text(json.dumps(value), encoding="utf-8")

    def test_pinned_contract_identity(self):
        identity = validate_contracts(self.contracts)
        self.assertEqual(2, identity["platformContractVersion"])
        self.assertEqual("0.2.0-preview.23", identity["coreBaselineVersion"])

    def test_missing_baseline_is_rejected(self):
        (self.contracts / "platform/protocol-baseline.json").unlink(missing_ok=True)
        with self.assertRaises(FileNotFoundError):
            validate_contracts(self.contracts)

    def test_identity_source_type_and_hash_drift_is_rejected(self):
        cases = [
            ("platform/manifest.json", "platformContractVersion", 1),
            ("portal/manifest.json", "coreBaselineVersion", "wrong"),
            ("portal/manifest.json", "baselineHash", "sha256:" + "0" * 64),
            ("platform/manifest.json", "platformContractVersion", True),
            ("platform/manifest.json", "supportedPlatformContractVersions", [2, 2]),
            ("platform/manifest.json", "source", "../other.yaml"),
            ("platform/manifest.json", "sourceHash", "sha256:" + "0" * 64),
        ]
        for path, key, value in cases:
            with self.subTest(path=path, key=key):
                original = (self.contracts / path).read_bytes()
                self.mutate(path, lambda doc: doc.update({key: value}))
                with self.assertRaises(ValueError):
                    validate_contracts(self.contracts)
                (self.contracts / path).write_bytes(original)

    def test_portal_artifact_corruption_and_source_escape_are_rejected(self):
        self.mutate("portal/manifest.json", lambda doc: doc["artifacts"]["native-vectors.json"].update({"source": "../../outside"}))
        with self.assertRaisesRegex(ValueError, "Invalid Portal source"):
            validate_contracts(self.contracts)
        shutil.copy2(CONTRACTS / "portal/manifest.json", self.contracts / "portal/manifest.json")
        path = self.contracts / "portal/native-vectors.json"
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "artifact hash mismatch"):
            validate_contracts(self.contracts)

    def test_lf_normalization_preserves_baseline_identity(self):
        path = self.contracts / "platform/protocol-baseline.json"
        before = validate_contracts(self.contracts)
        path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n"))
        self.assertEqual(before, validate_contracts(self.contracts))

    def test_wire_generation_has_no_drift_in_lf_or_crlf_checkouts(self):
        root = Path(self.temp.name) / "checkout"
        tools = root / "tools"
        tools.mkdir(parents=True)
        for name in ("android_contract.py", "generate-enterprise-wire.py"):
            shutil.copy2(ROOT / "tools" / name, tools / name)
        shutil.copytree(CONTRACTS, root / "app/src/test/resources/contracts")
        wire = root / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformWire.kt"
        wire.parent.mkdir(parents=True)
        canonical = (ROOT / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformWire.kt").read_bytes().replace(b"\r\n", b"\n")
        for newline in (b"\n", b"\r\n"):
            with self.subTest(newline=newline):
                expected = canonical.replace(b"\n", newline)
                wire.write_bytes(expected)
                for arguments in (["--check"], [], ["--check"]):
                    result = subprocess.run([sys.executable, str(tools / "generate-enterprise-wire.py"), *arguments], capture_output=True, text=True)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(expected, wire.read_bytes())


class ReleaseTest(unittest.TestCase):
    def test_version_tag_mismatch_is_rejected(self):
        check_tag("0.0.20", None)
        check_tag("0.0.20", "v0.0.20")
        with self.assertRaises(ValueError):
            check_tag("0.0.20", "v0.0.21")

    @patch("verify_android_apk.sdk_tool", side_effect=lambda sdk, name: name)
    def test_final_apk_metadata_and_signature_are_required(self, tool):
        expected = {"applicationId": "net.weero.measix.pilot", "versionName": "0.0.20", "versionCode": 20}
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/libactual.so", b"fixture")
            def command(args):
                if args[0] == "apksigner":
                    return "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "a" * 64
                return {"application-id": expected["applicationId"], "version-name": "0.0.20", "version-code": "20"}[args[2]]
            with patch("verify_android_apk.run", side_effect=command):
                metadata, artifact = inspect_apk(apk, Path(directory), expected, "release")
                self.assertEqual({key: expected[key] for key in ("applicationId", "versionName", "versionCode")}, metadata)
                self.assertEqual("x86_64", artifact["abi"])
            def scheme_command(args):
                if args[0] == "apksigner":
                    return "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + "a" * 64 + "\nV3 Signer: certificate SHA-256 digest: " + "a" * 64
                return command(args)
            with patch("verify_android_apk.run", side_effect=scheme_command):
                inspect_apk(apk, Path(directory), expected, "release")
            with patch("verify_android_apk.run", side_effect=lambda args: scheme_command(args).replace("signers: 1", "signers: 2")), self.assertRaises(ValueError):
                inspect_apk(apk, Path(directory), expected, "release")
            with patch("verify_android_apk.run", return_value="0.0.21"), self.assertRaises(ValueError):
                inspect_apk(apk, Path(directory), expected, "release")
            def unsigned(args):
                if args[0] == "apksigner":
                    raise ValueError("SDK verification failed")
                return command(args)
            with patch("verify_android_apk.run", side_effect=unsigned), self.assertRaisesRegex(ValueError, "SDK verification"):
                inspect_apk(apk, Path(directory), expected, "release")


if __name__ == "__main__":
    unittest.main()
