"""Generate the current Android wire types from the pinned Core OpenAPI (PyYAML 6).

Run from any directory. Ordinary Android builds use the checked-in output and do
not depend on Python, PyYAML, or a sibling checkout.
"""
from pathlib import Path
import hashlib
import json
import re
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/test/resources/contracts/platform/client-control.openapi.yaml"
OUTPUT = ROOT / "app/src/main/java/net/weero/measix/pilot/data/enterprise/PlatformWire.kt"
SCHEMAS = yaml.safe_load(SOURCE.read_text(encoding="utf-8"))["components"]["schemas"]
ROOTS = ["Discovery", "EnrollmentExchangeRequest", "EnrollmentExchangeResponse",
         "RefreshRequest", "RefreshResponse", "ManagedState", "Bootstrap",
         "ManagedSnapshot", "ManagedAppliedReport", "PortalGrant", "Problem"]
definitions = {}


def quote(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def resolved(schema):
    return resolved(SCHEMAS[schema["$ref"].split("/")[-1]]) if "$ref" in schema else schema


def kotlin_type(schema, name):
    if "$ref" in schema:
        return kotlin_type(resolved(schema), schema["$ref"].split("/")[-1])
    kind = schema.get("type")
    if kind == "array":
        return f'List<{kotlin_type(schema["items"], name + "Item")}>'
    if kind == "string" and "enum" in schema:
        if all(re.fullmatch(r"[A-Z][A-Z0-9_]*", x) for x in schema["enum"]):
            definitions.setdefault(name, "@Serializable\ninternal enum class Platform" + name + " { " + ", ".join(schema["enum"]) + " }")
            return "Platform" + name
    if kind == "object":
        if "properties" not in schema:
            raise ValueError(f"Object without fixed fields: {name}")
        if name not in definitions:
            definitions[name] = ""
            properties = schema["properties"]
            required = schema.get("required", [])
            sensitive = any(k in properties for k in ["accessToken", "refreshToken", "ticket"]) or name == "EnrollmentExchangeRequest"
            fields, validations = [], []
            for key, value in properties.items():
                field_type = kotlin_type(value, name + key[0].upper() + key[1:])
                optional = key not in required
                fields.append(f'    val {key}: {field_type}' + ("? = null," if optional else ","))
                validations.extend(checks(value, key, field_type, optional, name))
            body = "@Serializable\ninternal data class Platform" + name + "(\n" + "\n".join(fields) + "\n)"
            if validations or sensitive:
                body += " {\n"
                if validations:
                    body += "    init {\n" + "\n".join(validations) + "\n    }\n"
                if sensitive:
                    body += f'    override fun toString(): String = "Platform{name}(redacted)"\n'
                body += "}"
            definitions[name] = body
        return "Platform" + name
    return {"string": "String", "integer": "Long", "number": "Double", "boolean": "Boolean"}[kind]


def checks(schema, key, field_type, optional, owner):
    schema = resolved(schema)
    predicates = []
    target = "it" if optional else key
    if schema.get("type") == "string":
        if "minLength" in schema:
            predicates.append(f'{target}.codePointCount(0, {target}.length) >= {schema["minLength"]}')
        if "maxLength" in schema:
            predicates.append(f'{target}.codePointCount(0, {target}.length) <= {schema["maxLength"]}')
        if "pattern" in schema:
            predicates.append(f'Regex({quote(schema["pattern"])}).containsMatchIn({target})')
        if schema.get("format") == "date-time":
            predicates.append(f'platformWireTimestamp({target})')
    for bound, operator in [("minimum", ">="), ("maximum", "<=")]:
        if bound in schema:
            if schema.get("exclusive" + bound.capitalize()):
                operator = operator[0]
            predicates.append(f'{target} {operator} {schema[bound]}')
    if schema.get("type") == "number":
        predicates.append(f'{target}.isFinite()')
    if "minItems" in schema:
        predicates.append(f'{target}.size >= {schema["minItems"]}')
    if schema.get("uniqueItems"):
        predicates.append(f'{target}.distinct().size == {target}.size')
    if "enum" in schema and not field_type.startswith("Platform"):
        values = ", ".join(quote(x) if isinstance(x, str) else str(x) + "L" for x in schema["enum"])
        predicates.append(f'{target} in setOf({values})')
    item_checks = []
    if schema.get("type") == "array":
        nested = checks(schema["items"], "item", field_type[5:-1], False, owner + "_" + key)
        if nested:
            item_checks = [f'        {key}{"?" if optional else ""}.forEach {{ item ->'] + ["    " + line for line in nested] + ["        }"]
    if not predicates:
        return item_checks
    condition = " && ".join(predicates)
    if optional:
        condition = f'{key}?.let {{ {condition} }} != false'
    return [f'        require({condition}) {{ "invalid_platform_{owner}_{key}" }}'] + item_checks


for root in ROOTS:
    kotlin_type(SCHEMAS[root], root)
header = f'''// Generated by tools/generate-enterprise-wire.py; edit the Core schema, then regenerate.
// Source SHA256: {hashlib.sha256(SOURCE.read_bytes()).hexdigest()}
package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.Serializable

'''
payload = (header + "\n\n".join(definitions.values()) + "\n").replace("\n", "\r\n").encode("utf-8")
if sys.argv[1:] == ["--check"]:
    if OUTPUT.read_bytes() != payload:
        raise SystemExit("PlatformWire.kt differs from the pinned Core schema; regenerate it")
elif sys.argv[1:]:
    raise SystemExit("usage: generate-enterprise-wire.py [--check]")
else:
    OUTPUT.write_bytes(payload)
