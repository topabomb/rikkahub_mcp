"""Failure-oriented tests for the release producers; all APK tools are isolated fixtures."""
import json
import shutil
import tempfile
import unittest
import subprocess
import sys
from pathlib import Path

from android_contract import ROOT, CONTRACTS, validate_contracts
from produce_android_release import check_tag, inspect_apk, validate_build_identity
from run_android_core_consumer import pinned_file, validate_junit, validate_arguments, validate_plan, RUNTIME_CLASS
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
    def test_version_tag_and_debug_release_rules(self):
        check_tag("0.0.20", "candidate", None)
        check_tag("0.0.20", "released", "v0.0.20")
        for status, tag in (("released", None), ("released", "v0.0.21"), ("candidate", "v0.0.21")):
            with self.subTest(status=status, tag=tag), self.assertRaises(ValueError):
                check_tag("0.0.20", status, tag)

    def test_old_apk_cannot_claim_new_clean_source(self):
        expected = dict(validate_contracts(), sourceCommit="a" * 40, sourceDirty=False,
                        applicationId="net.weero.measix.pilot", versionName="0.0.20", versionCode=20)
        validate_build_identity(expected, expected)
        for change in ({"sourceCommit": "b" * 40}, {"sourceDirty": True}, {"versionCode": 21},
                       {"supportedPlatformContractVersions": [1, 2]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_build_identity(dict(expected, **change), expected)

    @patch("produce_android_release.sdk_tool", side_effect=lambda sdk, name: name)
    def test_final_apk_metadata_and_signature_are_required(self, tool):
        expected = dict(validate_contracts(), sourceCommit="a" * 40, sourceDirty=False,
                        applicationId="net.weero.measix.pilot", versionName="0.0.20", versionCode=20)
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/libactual.so", b"fixture")
                archive.writestr("assets/android-build-identity.json", json.dumps(expected))
            def command(args):
                if args[0] == "apksigner":
                    return "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "a" * 64
                return {"application-id": expected["applicationId"], "version-name": "0.0.20", "version-code": "20"}[args[2]]
            with patch("produce_android_release.run", side_effect=command):
                metadata, artifact, _ = inspect_apk(apk, Path(directory), expected, "release")
                self.assertEqual({key: expected[key] for key in ("applicationId", "versionName", "versionCode")}, metadata)
                self.assertEqual("x86_64", artifact["abi"])
                with self.assertRaisesRegex(ValueError, "APK build identity"):
                    inspect_apk(apk, Path(directory), dict(expected, sourceCommit="b" * 40), "release")
            def scheme_command(args):
                if args[0] == "apksigner":
                    return "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + "a" * 64 + "\nV3 Signer: certificate SHA-256 digest: " + "a" * 64
                return command(args)
            with patch("produce_android_release.run", side_effect=scheme_command):
                inspect_apk(apk, Path(directory), expected, "release")
            with patch("produce_android_release.run", side_effect=lambda args: scheme_command(args).replace("signers: 1", "signers: 2")), self.assertRaises(ValueError):
                inspect_apk(apk, Path(directory), expected, "release")
            with patch("produce_android_release.run", return_value="0.0.21"), self.assertRaises(ValueError):
                inspect_apk(apk, Path(directory), expected, "release")
            def unsigned(args):
                if args[0] == "apksigner":
                    raise ValueError("SDK verification failed")
                return command(args)
            with patch("produce_android_release.run", side_effect=unsigned), self.assertRaisesRegex(ValueError, "SDK verification"):
                inspect_apk(apk, Path(directory), expected, "release")

    def test_evidence_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "evidence"
            base.mkdir()
            (Path(directory) / "outside.xml").write_text("outside")
            with self.assertRaises(ValueError):
                pinned_file(base, "../outside.xml")

    def test_native_junit_failures_skips_empty_and_wrong_counts_are_rejected(self):
        valid = b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="actual"/></testsuite>'
        validate_junit(valid)
        invalid = [b"", valid.replace(b'tests="1"', b'tests="0"'), valid.replace(b'tests="1"', b'tests="2"'),
                   valid.replace(b'skipped="0"', b'skipped="1"'), valid.replace(b'failures="0"', b'failures="1"'),
                   valid.replace(b'<testcase name="actual"/>', b'<testcase><failure/></testcase>'),
                   valid.replace(b' errors="0"', b''), b'<!DOCTYPE x>' + valid]
        for raw in invalid:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                validate_junit(raw)

    def test_runner_dry_run_filter_and_sharding_arguments_are_rejected(self):
        for key in ("log", "suiteAssignment", "filter", "notClass", "numShards", "shardIndex", "runnerBuilder", "listener"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_arguments({key: "true"}, {"platformLive"})

    def test_local_live_method_cannot_impersonate_consumer_checks(self):
        step = {"class": RUNTIME_CLASS + "#initialEnterpriseRequestUsesDeclaredSelectionOrExistingSpaceEntry",
                "arguments": {"platformLive": "true"}}
        with self.assertRaises(ValueError):
            validate_plan({key: [step] for key in ("snapshot", "runtime", "portal")})


if __name__ == "__main__":
    unittest.main()
