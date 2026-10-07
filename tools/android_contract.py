"""Validate pinned Core materials. No sibling checkout or network is used."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "app/src/test/resources/contracts"
IDENTITY_FIELDS = ("platformContractVersion", "supportedPlatformContractVersions",
                   "coreBaselineVersion", "baselineHash")
PORTAL_SOURCES = {
    "portal-contract.openapi.json": "api/portal/portal-contract.openapi.json",
    "client-feed.schemas.json": "api/portal/client-feed.schemas.json",
    "native-vectors.json": "api/fixtures/portal/native-vectors.json",
    "feed-vectors.json": "api/fixtures/portal/feed-vectors.json",
    "platform-v1.json": "api/fixtures/enrollment/platform-v1.json",
    "cases.json": "api/fixtures/enrollment/cases.json",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def lf_hash(raw):
    return sha256(raw.replace(b"\r\n", b"\n"))


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key: " + key)
            result[key] = value
        return result
    value = json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique)
    require(isinstance(value, dict), "JSON object required: " + str(path))
    return value


def validate_identity(value):
    primary = value.get("platformContractVersion")
    require(type(primary) is int and primary > 0, "Invalid platformContractVersion")
    supported = value.get("supportedPlatformContractVersions")
    require(isinstance(supported, list) and supported and
            all(type(v) is int and v > 0 for v in supported) and
            supported == sorted(set(supported)) and primary in supported,
            "Invalid supportedPlatformContractVersions")
    require(isinstance(value.get("coreBaselineVersion"), str) and
            re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]{0,63}", value["coreBaselineVersion"]),
            "Invalid coreBaselineVersion")


def validate_contracts(directory=CONTRACTS):
    directory = Path(directory)
    baseline_file = directory / "platform/protocol-baseline.json"
    baseline = read_json(baseline_file)
    validate_identity(baseline)
    identity = {key: baseline[key] for key in IDENTITY_FIELDS if key != "baselineHash"}
    identity["baselineHash"] = lf_hash(baseline_file.read_bytes())
    client = read_json(directory / "platform/manifest.json")
    portal = read_json(directory / "portal/manifest.json")
    for label, manifest in (("Client", client), ("Portal", portal)):
        validate_identity(manifest)
        for key in IDENTITY_FIELDS:
            require(manifest.get(key) == identity[key], label + ": " + key + " differs from baseline")
    source_hash = lf_hash((directory / "platform/client-control.openapi.yaml").read_bytes())
    require(client.get("generated") is True and
            client.get("format") == "openapi-3.0.3-client-schema-only" and
            client.get("source") == "../api/client/client-control.openapi.yaml",
            "Invalid Client export source/format")
    require(client.get("sourceHash") == source_hash and
            baseline.get("documents", {}).get("api/client/client-control.openapi.yaml") == source_hash,
            "Client sourceHash differs from schema/baseline")
    require(type(portal.get("bridgeVersion")) is int and portal["bridgeVersion"] == 3,
            "Invalid Portal bridgeVersion")
    artifacts = portal.get("artifacts")
    require(isinstance(artifacts, dict) and set(artifacts) == set(PORTAL_SOURCES),
            "Incomplete Portal artifacts")
    for name, source in PORTAL_SOURCES.items():
        item = artifacts[name]
        require(isinstance(item, dict) and item.get("source") == source,
                "Invalid Portal source: " + name)
        require("sha256:" + str(item.get("sha256")) == sha256((directory / "portal" / name).read_bytes()),
                "Portal artifact hash mismatch: " + name)
    return identity


if __name__ == "__main__":
    print(json.dumps(validate_contracts(), indent=2))
