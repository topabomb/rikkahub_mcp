"""Plain Android string projections and a single atomic XML mutation protocol."""

import os
import re
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from lxml import etree


class ResourceError(ValueError):
    """The resource cannot be read or changed without losing information."""


def describe_error(error: BaseException) -> str:
    parts, seen = [], set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        parts.append(f"{type(error).__name__}: {error}")
        parts.extend(getattr(error, "__notes__", ()))
        error = error.__cause__ or error.__context__
    return " <- ".join(parts)


def normalize_value(value: str) -> str:
    """Values are XML-decoded Android text, not serialized XML entities."""
    if not isinstance(value, str):
        raise ResourceError("String value must be text")
    # Android uses enclosing quotes to preserve whitespace. Keep those delimiters.
    quoted = len(value) >= 2 and value.startswith('"') and value.endswith('"')
    if quoted:
        trailing_slashes = len(value[:-1]) - len(value[:-1].rstrip("\\"))
        quoted = trailing_slashes % 2 == 0
    body = value[1:-1] if quoted else value
    output = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "\\":
            if index + 1 == len(body):
                raise ResourceError("Trailing Android escape")
            escaped = body[index + 1]
            if escaped == "u":
                digits = body[index + 2:index + 6]
                if len(digits) != 4 or not all(c in "0123456789abcdefABCDEF" for c in digits):
                    raise ResourceError("Invalid Android Unicode escape")
                output.append(body[index:index + 6])
                index += 6
                continue
            if escaped not in "ntr\\\"'@?":
                raise ResourceError(f"Unsupported Android escape: \\{escaped}")
            output.append(body[index:index + 2])
            index += 2
            continue
        output.append("\\" + char if char in "\"'" else char)
        index += 1
    result = "".join(output)
    if quoted:
        result = '"' + result + '"'
    try:
        etree.Element("string").text = result
    except ValueError as error:
        raise ResourceError(str(error)) from error
    return result


_FORMAT = re.compile(
    r"%(?:(?P<index>\d+)\$)?(?P<flags>[-#+ 0,(<]*)\d*(?:\.\d+)?"
    r"(?P<conversion>[tT][a-zA-Z]|[a-zA-Z%])"
)


def _format_bindings(text: str):
    """Track Java Formatter arguments; ordinary indexing is independent of explicit indexes."""
    bindings, literals = [], []
    ordinary, previous = 0, None
    explicit_only = True
    for match in _FORMAT.finditer(text):
        token = match.group()
        if match["conversion"] in ("%", "n"):
            literals.append(token)
            continue
        if "<" in match["flags"]:
            if previous is None:
                raise ResourceError("Format placeholder has no previous argument for relative reference")
            index = previous
            explicit_only = False
        elif match["index"] is not None:
            index = int(match["index"])
            if index < 1:
                raise ResourceError("Format placeholder argument index must be positive")
        else:
            ordinary += 1
            index = ordinary
            explicit_only = False
        bindings.append((index, token))
        previous = index
    # Relative and mixed forms are order-sensitive. Only explicit indexes can reorder freely.
    return (Counter(bindings) if explicit_only else bindings), Counter(literals)


def validate_translation(source: str, value: str) -> str:
    result = normalize_value(value)
    if _format_bindings(source) != _format_bindings(result):
        raise ResourceError("Format placeholders changed (count, argument binding, order or type)")
    def newlines(text):
        return text.count("\n") + len(re.findall(r"(?<!\\)(?:\\\\)*\\n", text))
    if newlines(source) != newlines(result):
        raise ResourceError("Newline count changed")
    return result


@dataclass
class ResourceDocument:
    tree: etree._ElementTree
    values: dict[str, str]
    translatable: set[str]
    unsupported: dict[str, str]
    original: bytes | None

    def allows_translation(self, key: str) -> bool:
        return key not in self.unsupported and (key not in self.values or key in self.translatable)


class StringsXmlParser:
    @staticmethod
    def read(file_path: Path) -> ResourceDocument:
        try:
            original = file_path.read_bytes() if file_path.exists() else None
            if original is not None:
                root = etree.fromstring(original, etree.XMLParser(
                    resolve_entities=False, no_network=True, remove_blank_text=False,
                ))
                tree = root.getroottree()
                if tree.docinfo.doctype:
                    raise ResourceError("DOCTYPE is not supported")
            else:
                root = etree.Element("resources")
                tree = etree.ElementTree(root)
            if root.tag != "resources":
                raise ResourceError("Expected <resources> root")
            values, translatable, unsupported = {}, set(), {}
            for node in root:
                key = node.get("name")
                if not key:
                    continue
                if node.tag == "string":
                    if key in values or key in unsupported:
                        raise ResourceError(f"Duplicate or ambiguous string name: {key}")
                    if len(node):
                        unsupported[key] = "inline XML/xliff is not editable"
                    else:
                        values[key] = node.text or ""
                        if node.get("translatable", "true").lower() != "false":
                            translatable.add(key)
                elif node.tag in ("plurals", "array", "string-array"):
                    unsupported[key] = "plurals and arrays are not editable"
            return ResourceDocument(tree, values, translatable, unsupported, original)
        except (OSError, etree.Error, ValueError) as error:
            raise ResourceError(f"{file_path}: {type(error).__name__}: {error}") from error

    @staticmethod
    def parse(file_path: Path) -> dict[str, str]:
        return StringsXmlParser.read(file_path).values

    @staticmethod
    def update_entries(file_path: Path, entries: dict[str, str], *, delete=(), expected=None,
                       translation_source: tuple[Path, dict[str, str]] | None = None) -> None:
        """Patch only selected nodes; failed parse/validation/replace leaves original bytes intact.

        expected contains original values for edited keys (None means absent), preventing a
        stale TUI draft or a slow translation from overwriting a concurrent selected-key edit.
        translation_source identifies automatic drafts and their original source text; manual
        edits omit it. Both source and target eligibility are checked by this same writer.
        """
        if not entries and not delete:
            return
        document = StringsXmlParser.read(file_path)
        source_document = None
        if translation_source is not None:
            source_path, source_values = translation_source
            source_document = StringsXmlParser.read(source_path)
            for key, value in source_values.items():
                if key not in entries:
                    raise ResourceError(f"{file_path}: {key}: automatic draft has no target value")
                if key not in source_document.values or not source_document.allows_translation(key):
                    raise ResourceError(f"{source_path}: {key}: translation source missing or protected; refresh before retry")
                if source_document.values[key] != value:
                    raise ResourceError(f"{source_path}: {key}: translation source changed; refresh before retry")
                if not document.allows_translation(key):
                    raise ResourceError(f"{file_path}: {key}: translation target became protected; refresh before retry")
        root = document.tree.getroot()
        nodes = {node.get("name"): node for node in root if node.tag == "string"}
        for key in set(entries) | set(delete):
            if key in document.unsupported:
                raise ResourceError(f"{file_path}: {key}: {document.unsupported[key]}")
            if expected is not None and key in expected and document.values.get(key) != expected[key]:
                raise ResourceError(f"{file_path}: {key}: resource changed since it was read; refresh before retry")
        for key, value in entries.items():
            if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", key):
                raise ResourceError(f"Invalid Android string key: {key}")
            normalized = normalize_value(value)
            node = nodes.get(key)
            if node is None:
                # Insert using the surrounding sibling indentation without reformatting existing nodes.
                whitespace = [root.text, *(child.tail for child in root)]
                indent = next((match.group(1) for text in whitespace if text and
                               (match := re.search(r"\n([ \t]+)$", text))), "  ")
                closing = root[-1].tail if len(root) else root.text
                if len(root):
                    root[-1].tail = "\n" + indent
                else:
                    root.text = "\n" + indent
                node = etree.SubElement(root, "string", name=key)
                node.tail = closing if closing and not closing.strip() and "\n" in closing else "\n"
            node.text = normalized
        for key in delete:
            if key in nodes:
                root.remove(nodes[key])
        file_path.parent.mkdir(parents=True, exist_ok=True)
        staging = None
        failure = None
        try:
            with tempfile.NamedTemporaryFile(dir=file_path.parent, prefix=".strings-", suffix=".tmp", delete=False) as stream:
                staging = Path(stream.name)
                payload = etree.tostring(document.tree, encoding="utf-8", xml_declaration=True).rstrip(b"\r\n") + b"\n"
                # Preserve the file's newline convention; new files use repository CRLF.
                if document.original is None or b"\r\n" in document.original:
                    payload = payload.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            StringsXmlParser.read(staging)
            current = file_path.read_bytes() if file_path.exists() else None
            if current != document.original:
                raise ResourceError(f"{file_path}: file changed during save")
            if source_document is not None:
                current_source = source_path.read_bytes() if source_path.exists() else None
                if current_source != source_document.original:
                    raise ResourceError(f"{source_path}: translation source changed during save")
            os.replace(staging, file_path)
        except BaseException as error:
            failure = error
            raise
        finally:
            if staging is not None:
                try:
                    staging.unlink(missing_ok=True)
                except OSError as cleanup:
                    if failure is None:
                        raise
                    failure.add_note(f"Staging cleanup failed: {type(cleanup).__name__}: {cleanup}")

    @staticmethod
    def update_entry(file_path: Path, key: str, value: str) -> None:
        StringsXmlParser.update_entries(file_path, {key: value})

    @staticmethod
    def delete_entry(file_path: Path, key: str) -> bool:
        document = StringsXmlParser.read(file_path)
        if key in document.unsupported:
            raise ResourceError(f"{file_path}: {key}: {document.unsupported[key]}")
        if key not in document.values:
            return False
        StringsXmlParser.update_entries(file_path, {}, delete=(key,), expected={key: document.values[key]})
        return True
