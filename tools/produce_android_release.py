"""Produce a preserved APK bundle from clean source and Android SDK metadata."""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

from android_contract import ROOT, CONTRACTS, read_json, require, sha256, validate_contracts

BUILD_IDENTITY_FIELDS = ("sourceCommit", "sourceDirty", "applicationId", "versionName", "versionCode",
                         "platformContractVersion", "supportedPlatformContractVersions", "coreBaselineVersion", "baselineHash")


def validate_build_identity(actual, expected):
    require(isinstance(actual, dict) and actual.get("sourceDirty") is False and
            expected.get("sourceDirty") is False, "APK must have been built from clean fixed source")
    require(re.fullmatch(r"[a-f0-9]{40,64}", str(expected.get("sourceCommit"))), "Missing build source commit")
    require(all(key in actual and actual[key] == expected.get(key) for key in BUILD_IDENTITY_FIELDS),
            "APK build identity differs from fixed source/build-info")


def run(command, cwd=ROOT):
    result = subprocess.run([str(c) for c in command], cwd=cwd, capture_output=True, text=True)
    require(result.returncode == 0, "Command failed: " + str(command[0]) + "\n" + result.stdout + result.stderr)
    return result.stdout.strip()


def clean_commit(root=ROOT):
    require(not run(["git", "status", "--porcelain", "--untracked-files=all"], root),
            "Release/evidence requires clean fixed Android source (including untracked files)")
    commit = run(["git", "rev-parse", "HEAD"], root)
    require(re.fullmatch(r"[a-f0-9]{40,64}", commit), "Invalid Android source commit")
    # A dirty submodule also changes the build without changing the parent commit.
    require(not any(line.startswith(("+", "-", "U")) for line in
                    run(["git", "submodule", "status", "--recursive"], root).splitlines()),
            "Submodule differs from fixed source")
    return commit


def declared_version(root=ROOT):
    text = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', text, re.M)
    require(len(names) == len(codes) == 1, "Ambiguous Gradle product version")
    return names[0], int(codes[0])


def android_support(root=ROOT):
    text = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r"val supportedPlatformContracts = listOf\(([^)]+)\)", text)
    require(match and re.fullmatch(r"\d+(?:,\s*\d+)*", match[1]), "Cannot read Android-owned contract support")
    return [int(value.strip()) for value in match[1].split(",")]


def check_tag(version, status, tag):
    require(status in ("candidate", "released"), "Invalid publication status")
    require(not tag or tag == "v" + version, "Tag differs from Gradle/APK versionName")
    require(status != "released" or tag == "v" + version, "Formal release requires v<versionName> tag")


def sdk_tool(sdk, name):
    suffix = ".bat" if os.name == "nt" else ""
    if name == "apkanalyzer":
        paths = list((sdk / "cmdline-tools").glob("*/bin/" + name + suffix))
    else:
        paths = list((sdk / "build-tools").glob("*/" + name + suffix))
    require(paths, "Android SDK tool missing: " + name)
    return sorted(paths, key=lambda p: [int(x) if x.isdigit() else x for x in
                                      re.split(r"(\d+)", p.as_posix())])[-1]


def inspect_apk(apk, sdk, expected, variant):
    analyzer, signer = sdk_tool(sdk, "apkanalyzer"), sdk_tool(sdk, "apksigner")
    metadata = {key: run([analyzer, "manifest", command, apk]) for key, command in (
        ("applicationId", "application-id"), ("versionName", "version-name"), ("versionCode", "version-code"))}
    require(metadata["versionCode"].isdigit(), "Invalid APK versionCode")
    metadata["versionCode"] = int(metadata["versionCode"])
    application_id = expected["applicationId"] + (".debug" if variant == "debug" else "")
    require(metadata == {"applicationId": application_id, "versionName": expected["versionName"],
                         "versionCode": expected["versionCode"]}, "Final APK differs from Gradle product identity")
    certificates = run([signer, "verify", "--verbose", "--print-certs", apk])
    signer_count = re.search(r"^Number of signers: (\d+)$", certificates, re.M)
    require(signer_count and signer_count[1] == "1", "Expected exactly one verified APK signer")
    digests = re.findall(r"^(?:Signer #\d+|V\d+(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([a-fA-F0-9:]+)$", certificates, re.M)
    # Recent SDKs print each verified signature scheme separately for the same signer.
    normalized = {digest.replace(":", "").lower() for digest in digests}
    require(len(normalized) == 1, "APK signature schemes disagree or certificate SHA-256 is missing")
    digest = normalized.pop()
    require(re.fullmatch(r"[a-f0-9]{64}", digest), "Invalid signing certificate SHA-256")
    with zipfile.ZipFile(apk) as archive:
        identity_path = "assets/android-build-identity.json"
        require(archive.namelist().count(identity_path) == 1, "APK build identity missing or duplicated")
        validate_build_identity(json.loads(archive.read(identity_path).decode("utf-8")), expected)
        abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")})
    require(abis in (["arm64-v8a"], ["x86_64"], ["arm64-v8a", "x86_64"]), "Unexpected APK native ABI set")
    return metadata, {"sha256": sha256(apk.read_bytes()), "abi": abis[0] if len(abis) == 1 else "universal",
                      "variant": variant, "signingCertificateSha256": "sha256:" + digest}, certificates


def snapshot_support(root=ROOT):
    text = (root / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformSnapshotCompatibility.kt").read_text(encoding="utf-8")
    match = re.search(r"val supportedSchemas: Set<Long> = setOf\(([^)]+)\)", text)
    require(match is not None and re.fullmatch(r"\d+L(?:,\s*\d+L)*", match[1]), "Cannot read actual Snapshot support owner")
    versions = [int(v.strip()[:-1]) for v in match[1].split(",")]
    require(versions == sorted(set(versions)), "Invalid Snapshot support set")
    return versions


def produce(args):
    source_commit = clean_commit()
    identity = validate_contracts()
    expected = read_json(args.build_info)
    require(expected.get("sourceCommit") == source_commit and expected.get("sourceDirty") is False,
            "Stale/dirty Gradle build-info source; rebuild the APK from current clean source")
    require((expected["versionName"], expected["versionCode"]) == declared_version(), "Stale Gradle build-info version")
    for key in ("platformContractVersion", "coreBaselineVersion", "baselineHash"):
        require(expected.get(key) == identity[key], "Stale Gradle build-info: " + key)
    # This is Android's generated support promise, rather than the Core support list.
    from android_contract import validate_identity
    validate_identity(expected)
    require(expected["supportedPlatformContractVersions"] == android_support(), "Stale Android-owned build-info support set")
    check_tag(expected["versionName"], args.status, args.tag)
    require(args.status != "released" or args.variant == "release", "Debug APK can only be a candidate")
    wire = ROOT / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformWire.kt"
    source_hash = read_json(CONTRACTS / "platform/manifest.json")["sourceHash"]
    require("// Core source SHA256 (LF): " + source_hash[7:] in wire.read_text(encoding="utf-8"), "Stale generated wire")
    output = args.output.resolve()
    require(not output.exists(), "Release output occupied; preserve original bundle")
    apks = [Path(path).resolve(strict=True) for path in args.apk]
    require(len({p.name for p in apks}) == len(apks), "Duplicate APK output name")
    inspected = [inspect_apk(apk, args.sdk, expected, args.variant) for apk in apks]
    require(len({item[1]["abi"] for item in inspected}) == len(inspected), "Duplicate ABI/variant")
    # Reserve only after validation; later failures preserve the incomplete output and logs.
    output.mkdir(parents=True, exist_ok=False)
    shutil.copytree(CONTRACTS, output / "contracts")
    shutil.copy2(wire, output / "contracts/PlatformWire.kt")
    (output / "apks").mkdir()
    (output / "verification").mkdir()
    artifacts = []
    for apk, (metadata, artifact, cert_log) in zip(apks, inspected):
        target = output / "apks" / apk.name
        shutil.copy2(apk, target)
        require(sha256(target.read_bytes()) == artifact["sha256"], "APK changed while copying")
        artifact["path"] = target.relative_to(output).as_posix()
        artifacts.append(artifact)
        (output / "verification" / (apk.name + ".signature.txt")).write_text(cert_log + "\n", encoding="utf-8")
    require(clean_commit() == source_commit, "Source changed during APK verification")
    validate_contracts(output / "contracts")
    record = {"formatVersion": 1, "product": "MEASIX Android", "status": args.status,
              "sourceCommit": source_commit, "sourceDirty": False, **inspected[0][0],
              **{key: expected[key] for key in ("platformContractVersion", "supportedPlatformContractVersions",
                                               "coreBaselineVersion", "baselineHash")},
              "snapshotSchemaVersions": snapshot_support(), "artifacts": artifacts,
              "contracts": {"directory": "contracts"}}
    if args.tag:
        record["tag"] = args.tag
    for key, path in (("platformManifest", "contracts/platform/manifest.json"),
                      ("portalManifest", "contracts/portal/manifest.json"), ("wire", "contracts/PlatformWire.kt")):
        record["contracts"][key] = {"path": path, "sha256": sha256((output / path).read_bytes())}
    (output / "android-release.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(output / "android-release.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-version", action="store_true")
    parser.add_argument("--status", choices=("candidate", "released"), default="candidate")
    parser.add_argument("--tag")
    parser.add_argument("--variant", choices=("debug", "release"), default="release")
    parser.add_argument("--build-info", type=Path, default=ROOT / "app/build/release/build-info.json")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")))
    parser.add_argument("--apk", action="append")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.check_version:
        check_tag(declared_version()[0], args.status, args.tag)
        print(declared_version()[0])
    else:
        require(args.apk and args.output and args.sdk, "--apk, --output and --sdk/ANDROID_HOME are required")
        produce(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
