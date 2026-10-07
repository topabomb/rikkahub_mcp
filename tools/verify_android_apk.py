"""Check final APK versions, signatures and hashes for the existing release workflow."""
import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

from android_contract import ROOT, require, sha256, validate_contracts


def declared_version():
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', text, re.M)
    require(len(names) == len(codes) == 1, "Ambiguous Gradle product version")
    return names[0], int(codes[0])


def check_tag(version, tag):
    require(not tag or tag == "v" + version, "Tag differs from Gradle/APK versionName")


def run(command):
    result = subprocess.run([str(c) for c in command], capture_output=True, text=True)
    require(result.returncode == 0, "Command failed: " + str(command[0]) + "\n" + result.stdout + result.stderr)
    return result.stdout.strip()


def sdk_tool(sdk, name):
    suffix = ".bat" if os.name == "nt" else ""
    directory = "cmdline-tools" if name == "apkanalyzer" else "build-tools"
    pattern = "*/bin/" + name + suffix if name == "apkanalyzer" else "*/" + name + suffix
    paths = list((sdk / directory).glob(pattern))
    require(paths, "Android SDK tool missing: " + name)
    return sorted(paths, key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", p.as_posix())])[-1]


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
    normalized = {digest.replace(":", "").lower() for digest in digests}
    require(len(normalized) == 1, "APK signature schemes disagree or certificate SHA-256 is missing")
    digest = normalized.pop()
    require(re.fullmatch(r"[a-f0-9]{64}", digest), "Invalid signing certificate SHA-256")
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")})
    require(abis in (["arm64-v8a"], ["x86_64"], ["arm64-v8a", "x86_64"]), "Unexpected APK native ABI set")
    return metadata, {"path": apk.as_posix(), "sha256": sha256(apk.read_bytes()),
                      "abi": abis[0] if len(abis) == 1 else "universal", "variant": variant,
                      "signingCertificateSha256": "sha256:" + digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-version", action="store_true")
    parser.add_argument("--tag")
    parser.add_argument("--variant", choices=("debug", "release"), default="release")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")))
    parser.add_argument("--apk", action="append", type=Path)
    args = parser.parse_args()
    version, code = declared_version()
    check_tag(version, args.tag)
    if args.check_version:
        print(version)
        return
    require(args.apk and args.sdk, "--apk and --sdk/ANDROID_HOME are required")
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    application_id = re.findall(r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', gradle, re.M)
    require(len(application_id) == 1, "Ambiguous Gradle applicationId")
    expected = {"applicationId": application_id[0], "versionName": version, "versionCode": code}
    inspected = [inspect_apk(apk, args.sdk, expected, args.variant) for apk in args.apk]
    artifacts = [item[1] for item in inspected]
    require(len({item["abi"] for item in artifacts}) == len(artifacts), "Duplicate APK ABI")
    contract = validate_contracts()
    support = re.search(r"val supportedPlatformContracts = listOf\(([^)]+)\)", gradle)
    require(support and re.fullmatch(r"\d+(?:,\s*\d+)*", support[1]), "Cannot read Android-owned contract support")
    contract["supportedPlatformContractVersions"] = [int(value.strip()) for value in support[1].split(",")]
    print(json.dumps({**inspected[0][0], "expectedBuildContract": contract, "artifacts": artifacts}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
