"""Run native consumers against one preserved APK and one deployed Core payload.

The plan selects existing opt-in native tests and their non-secret arguments. It
must cover the adopted contract's live scenarios; fixtures are not a live plan.
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit
from pathlib import Path

from android_contract import ROOT, read_json, require, sha256, validate_contracts
from produce_android_release import clean_commit, inspect_apk, check_tag, snapshot_support, android_support

IDENTITY = "net.weero.measix.pilot.data.enterprise.AndroidReleaseIdentityAndroidTest"
LISTENER = "net.weero.measix.pilot.data.enterprise.AndroidConsumerJUnitListener"
HASH = re.compile(r"sha256:[a-f0-9]{64}")
SNAPSHOT_CLASS = "net.weero.measix.pilot.data.enterprise.PlatformSnapshotCompatibilityLiveAndroidTest"
RUNTIME_CLASS = "net.weero.measix.pilot.data.enterprise.PlatformModelLiveAndroidTest"
PORTAL_CLASS = "net.weero.measix.pilot.service.portal.PortalCoreConsumerLiveAndroidTest"
WORKSPACE_TEST = "net.weero.measix.pilot.ui.pages.remoteworkspace.RemoteWorkspaceLiveAndroidTest#realFileRoundTripConditionsLifecycleAndNativePage"
REQUIRED_RUNTIME = {
    "enrolledPlatformRejectsStaleGenerationBeforeForwarding",
    "enrolledPlatformTranscribesKnownSpeechThroughPublishedAsr",
    "enrolledPlatformDefaultSpeechReachesActualPlaybackAndCompletes",
    "enrolledPlatformSystemSpeechUsesDeviceEngineAndCompletes",
    "enrolledPlatformMcpDiscoversAndPersistsPublicCatalog",
    "enrolledPlatformModelExecutesStreamingAndAuxiliaryThroughOriginalOwner",
}
REQUIRED_SNAPSHOT = {"join-v4", "reopen-v4", "sync-v5", "reopen-v5", "reject-v3", "reject-v6", "reject-v7", "recover-v5"}


def validate_arguments(arguments, allowed):
    require(isinstance(arguments, dict) and set(arguments) <= set(allowed), "Unsupported native consumer arguments")
    for key, value in arguments.items():
        require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_./:-]+", value), "Invalid native consumer argument")


def validate_plan(plan):
    require(set(plan) == {"snapshot", "runtime", "portal"} and all(isinstance(v, list) and v for v in plan.values()), "Live plan needs snapshot/runtime/portal")
    snapshots, runtime = set(), set()
    workspace = False
    for check_id, steps in plan.items():
        for step in steps:
            require(isinstance(step, dict) and set(step) <= {"class", "arguments", "scenario"}, "Invalid live plan step")
            test = step.get("class", "")
            arguments = step.get("arguments", {})
            if check_id == "snapshot":
                require(test == SNAPSHOT_CLASS + "#verifiesRequestedLiveScenario" and step.get("scenario") in REQUIRED_SNAPSHOT,
                        "Snapshot evidence requires the complete original-consumer scenario matrix")
                validate_arguments(arguments, {"snapshotCompatibilityInput"})
                require(arguments.get("snapshotCompatibilityInput"), "Snapshot private input required")
                require(step["scenario"] not in snapshots, "Duplicate Snapshot scenario")
                snapshots.add(step["scenario"])
            elif check_id == "runtime":
                if test == WORKSPACE_TEST:
                    validate_arguments(arguments, {"remoteWorkspaceInput"})
                    require(arguments.get("remoteWorkspaceInput"), "Workspace private input required")
                    workspace = True
                    continue
                prefix = RUNTIME_CLASS + "#"
                require(test.startswith(prefix) and test[len(prefix):] in REQUIRED_RUNTIME, "Runtime evidence requires actual Core resource consumers")
                validate_arguments(arguments, {"platformLive"})
                require(arguments.get("platformLive") == "true", "Runtime live opt-in required")
                runtime.add(test[len(prefix):])
            else:
                require(test == PORTAL_CLASS, "Portal evidence requires the package's actual Portal/native consumer suite")
                validate_arguments(arguments, set())
                source = ROOT / "app/src/androidTest/java" / (PORTAL_CLASS.replace(".", "/") + ".kt")
                require(source.is_file(), "Portal Core native consumer suite is not implemented; compatibility remains unverified")
    require(snapshots == REQUIRED_SNAPSHOT and runtime == REQUIRED_RUNTIME and workspace, "Required live consumer coverage incomplete")


def pinned_file(base, name):
    require(isinstance(name, str) and name and not Path(name).is_absolute() and "\\" not in name,
            "Artifact path must be relative")
    root = Path(base).resolve(strict=True)
    target = (root / name).resolve(strict=True)
    require(target != root and root in target.parents, "Artifact escapes record directory")
    return target


def checked_file(base, item):
    require(isinstance(item, dict) and HASH.fullmatch(str(item.get("sha256"))), "Invalid artifact SHA-256")
    path = pinned_file(base, item.get("path"))
    require(path.is_file() and sha256(path.read_bytes()) == item["sha256"], "Artifact hash mismatch: " + item["path"])
    return path


def validate_junit(raw):
    require(raw.strip() and not re.search(br"<!DOCTYPE|<!ENTITY", raw, re.I), "Empty or unsafe JUnit")
    root = ET.fromstring(raw)
    require(root.tag in ("testsuite", "testsuites"), "Invalid JUnit root")
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    require(suites and list(root.iter("testcase")), "JUnit has no executed testcases")
    for suite in suites:
        counts = {key: suite.get(key) for key in ("tests", "failures", "errors", "skipped")}
        require(all(v is not None and v.isdigit() for v in counts.values()), "Incomplete JUnit counters")
        require(int(counts["tests"]) > 0 and all(int(counts[key]) == 0 for key in ("failures", "errors", "skipped")),
                "JUnit contains failures/errors/skips or zero tests")
        require(int(counts["tests"]) == len(list(suite.iter("testcase"))), "JUnit tests counter differs from events")
    require(not any(list(root.iter(tag)) for tag in ("failure", "error", "skipped")), "JUnit failure/error/skip event")
    return root


def core_identity(release_file):
    release = read_json(release_file)
    require(release.get("publicationStatus") in ("UNVERIFIED_CANDIDATE", "VERIFIED_PREVIEW"), "Invalid Core candidate status")
    root = release_file.parent
    entries = []
    for name in ("bin", "assets", "deploy"):
        directory = root / name
        require(directory.is_dir() and not directory.is_symlink(), "Missing or symlinked Core payload: " + name)
        for file in directory.rglob("*"):
            require(not file.is_symlink(), "Symlink in Core payload")
            if file.is_file():
                entries.append(file.relative_to(root).as_posix() + "\0" + sha256(file.read_bytes()))
    require(entries and sha256("\n".join(sorted(entries)).encode()) == release.get("buildHash"), "Core payload buildHash mismatch")
    source = release.get("source", {})
    result = {"version": release.get("version"), "sourceCommit": source.get("coreCommit"),
              "architectureCommit": source.get("architectureCommit"), "portalCommit": source.get("portalCommit"),
              "baselineHash": release.get("baselineHash"), "buildHash": release.get("buildHash")}
    require(all(re.fullmatch(r"[a-f0-9]{40,64}", str(result[key])) for key in
                ("sourceCommit", "architectureCommit", "portalCommit")), "Invalid Core source commits")
    require(HASH.fullmatch(str(result["baselineHash"])), "Invalid Core baselineHash")
    return result


def validate_release(file, sdk):
    record = read_json(file)
    require(record.get("formatVersion") == 1 and record.get("product") == "MEASIX Android" and
            record.get("sourceDirty") is False and record.get("sourceCommit") == clean_commit(), "Stale/dirty Android source")
    check_tag(record["versionName"], record["status"], record.get("tag"))
    directory = pinned_file(file.parent, record["contracts"]["directory"])
    identity = validate_contracts(directory)
    for key in ("platformContractVersion", "coreBaselineVersion", "baselineHash"):
        require(record.get(key) == identity[key] == validate_contracts()[key], "Release/current material mismatch: " + key)
    require(record.get("snapshotSchemaVersions") == snapshot_support(), "Snapshot support differs from source")
    require(record.get("supportedPlatformContractVersions") == android_support(), "Android contract support differs from source")
    for key, relative in (("platformManifest", "platform/manifest.json"), ("portalManifest", "portal/manifest.json")):
        require(checked_file(file.parent, record["contracts"][key]) == (directory / relative).resolve(), "Manifest path mismatch")
    wire = checked_file(file.parent, record["contracts"]["wire"])
    require(wire.read_bytes().replace(b"\r\n", b"\n") == (ROOT / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformWire.kt").read_bytes().replace(b"\r\n", b"\n"),
            "Original generated wire differs from current fixed source")
    for item in record["artifacts"]:
        apk = checked_file(file.parent, item)
        expected = dict(record, applicationId=record["applicationId"].removesuffix(".debug"))
        metadata, actual, _ = inspect_apk(apk, sdk, expected, item["variant"])
        require(all(actual[key] == item[key] for key in actual), "Final APK differs from original record")
    return record


def native_run(adb, serial, package, test, arguments, identity, log):
    require(re.fullmatch(r"net\.weero\.measix\.pilot\.[A-Za-z0-9_.]+(?:#[A-Za-z0-9_]+)?", test), "Invalid native consumer class")
    source = ROOT / "app/src/androidTest/java" / (test.split("#")[0].replace(".", "/") + ".kt")
    require(source.is_file(), "Native consumer does not belong to fixed source")
    command = [str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test,
               "-e", "listener", LISTENER, "-e", "androidReleaseIdentity", identity]
    validate_arguments({k: v for k, v in arguments.items() if k != "corePortalAssets"},
                       {"platformLive", "snapshotCompatibilityInput", "consumerExpectedScenario", "remoteWorkspaceInput", "coreConsumerOrigin", "coreConsumerDeploymentId"})
    require("corePortalAssets" not in arguments or re.fullmatch(r"[A-Za-z0-9+/=]+", arguments["corePortalAssets"]), "Invalid Portal asset identity")
    for key, value in arguments.items():
        command.extend(["-e", key, value])
    command.append(package + ".test/androidx.test.runner.AndroidJUnitRunner")
    # Stream raw output to an exclusive file, including failures and timeouts.
    with log.open("xb") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=900)
        except BaseException:
            process.kill()
            process.wait()
            raise
    raw = log.read_bytes()
    require(code == 0 and b"INSTRUMENTATION_FAILED" not in raw and b"FAILURES!!!" not in raw and
            re.search(br"OK \(\d+ tests?\)", raw), "Native instrumentation failed; original log preserved")
    reports = re.findall(br"MEASIX_JUNIT_BASE64=([A-Za-z0-9+/=]+)", raw)
    require(len(reports) == 1, "Missing actual native JUnit report")
    xml = base64.b64decode(reports[0], validate=True)
    root = validate_junit(xml)
    require(any(case.get("classname") == test.split("#")[0] for case in root.iter("testcase")), "JUnit does not contain requested consumer")
    return command, root


def execute(args):
    source_commit = clean_commit()
    release_hash = sha256(args.android_release.read_bytes())
    core_release_hash = sha256(args.core_release.read_bytes())
    release = validate_release(args.android_release, args.sdk)
    artifact = next((item for item in release["artifacts"] if item["sha256"] == args.apk_sha256), None)
    require(artifact, "Selected APK is absent from original release")
    original = checked_file(args.android_release.parent, artifact)
    core = core_identity(args.core_release)
    origin = urlsplit(args.core_origin)
    require(origin.scheme in ("http", "https") and origin.hostname and not origin.username and not origin.password and
            not origin.query and not origin.fragment and not origin.path and
            re.fullmatch(r"[A-Za-z0-9:/._-]+", args.core_origin), "Explicit deployed Core origin required")
    require(re.fullmatch(r"[A-Za-z0-9._-]{1,256}", args.deployment_id), "Explicit deployed Core deploymentId required")
    deployed = read_json(args.core_release)
    from android_contract import validate_identity
    validate_identity(deployed)
    require(deployed["platformContractVersion"] in release["supportedPlatformContractVersions"] and
            release["platformContractVersion"] in deployed["supportedPlatformContractVersions"], "Core/Android contract unsupported")
    plan = read_json(args.plan)
    validate_plan(plan)
    require(not args.output.exists(), "Evidence output occupied; preserve original reports")
    args.output.mkdir(parents=True, exist_ok=False)
    identity = dict(release, **{key: artifact[key] for key in ("variant", "signingCertificateSha256")}, apkSha256=artifact["sha256"])
    encoded = base64.b64encode(json.dumps(identity).encode()).decode()
    installation = subprocess.run([str(args.adb), "-s", args.serial, "install", "-r", str(original)], capture_output=True)
    (args.output / "installation.log").write_bytes(installation.stdout + installation.stderr)
    require(installation.returncode == 0 and b"Success" in installation.stdout, "Fixed APK installation failed")
    checks = []
    # Identity before and after consumers proves the target was never replaced during the run.
    jobs = {"identity": [{"class": IDENTITY, "arguments": {}}], **plan}
    portal_assets = {file.relative_to(args.core_release.parent / "assets/portal").as_posix(): sha256(file.read_bytes())
                     for file in (args.core_release.parent / "assets/portal").rglob("*")
                     if file.is_file() and file.suffix in (".js", ".css")}
    require(portal_assets and any(name.endswith(".js") for name in portal_assets), "Core package has no actual Portal scripts")
    for check_id, steps in jobs.items():
        suite = ET.Element("testsuites")
        commands, logs = [], []
        for index, step in enumerate(steps):
            require(check_id == "identity" or step["class"].split("#")[0].endswith("LiveAndroidTest"), "Consumer evidence requires opt-in live tests")
            log = args.output / (check_id + "-" + str(index) + ".log")
            arguments = dict(step.get("arguments", {}))
            if check_id != "identity":
                arguments.update(coreConsumerOrigin=args.core_origin, coreConsumerDeploymentId=args.deployment_id)
            if check_id == "snapshot":
                arguments["consumerExpectedScenario"] = step["scenario"]
            if check_id == "portal":
                arguments["corePortalAssets"] = base64.b64encode(json.dumps(portal_assets).encode()).decode()
            command, xml = native_run(args.adb, args.serial, release["applicationId"], step["class"], arguments, encoded, log)
            suite.append(xml)
            commands.append(subprocess.list2cmdline(command))
            logs.append(log.read_bytes())
        report = args.output / (check_id + ".xml")
        report.write_bytes(ET.tostring(suite, encoding="utf-8", xml_declaration=True))
        log = args.output / (check_id + ".log")
        log.write_bytes(b"\n".join(logs))
        checks.append({"id": check_id, "command": "\n".join(commands), "exitCode": 0,
                       "report": {"path": report.name, "sha256": sha256(report.read_bytes())},
                       "log": {"path": log.name, "sha256": sha256(log.read_bytes())}})
    command, xml = native_run(args.adb, args.serial, release["applicationId"], IDENTITY, {}, encoded, args.output / "identity-final.log")
    identity_check = checks[0]
    root = ET.parse(args.output / "identity.xml").getroot()
    root.append(xml)
    (args.output / "identity.xml").write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
    with (args.output / "identity.log").open("ab") as stream:
        stream.write((args.output / "identity-final.log").read_bytes())
    identity_check["command"] += "\n" + subprocess.list2cmdline(command)
    for kind in ("report", "log"):
        identity_check[kind]["sha256"] = sha256((args.output / identity_check[kind]["path"]).read_bytes())
    require(clean_commit() == source_commit and core_identity(args.core_release) == core, "Source/Core payload changed during run")
    require(sha256(args.android_release.read_bytes()) == release_hash and
            sha256(args.core_release.read_bytes()) == core_release_hash, "Original release record changed during run")
    validate_release(args.android_release, args.sdk)
    evidence = {"formatVersion": 1, "suite": "android-core-consumer", "result": "PASS", "sourceDirty": False,
                "core": core, "android": {"releaseHash": release_hash, "sourceCommit": source_commit,
                                           "apkSha256": artifact["sha256"]}, "checks": checks}
    (args.output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(args.output / "evidence.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-release", type=Path, required=True)
    parser.add_argument("--core-release", type=Path, required=True, help="release.json from the actual deployed bin/assets/deploy payload")
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--core-origin", required=True, help="Origin from the actual deployment record, without a trailing slash")
    parser.add_argument("--deployment-id", required=True, help="Deployment identity from that same deployment")
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--serial", required=True, help="Dedicated test device; installation preserves its data")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")))
    parser.add_argument("--adb", type=Path)
    args = parser.parse_args()
    require(args.sdk, "--sdk/ANDROID_HOME required")
    args.adb = args.adb or args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    execute(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
