import asyncio
from pathlib import Path

import pytest
from lxml import etree

from services.xml_parser import ResourceError, StringsXmlParser, normalize_value, validate_translation
from models.entry import entries_from_documents
from services.translator import select_entries


def test_patch_preserves_other_resource_shapes_attributes_order_and_comments(tmp_path):
    path = tmp_path / "strings.xml"
    path.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
  <!-- translator context -->
  <string name="selected" formatted="false" product="default">Old</string>
  <string name="keep" translatable="false">A &amp; B</string>
  <string name="mixed">Hi <xliff:g id="person">%1$s</xliff:g>!</string>
  <plurals name="plural"><item quantity="one">One</item></plurals>
  <string-array name="array"><item>Item</item></string-array>
</resources>''', encoding="utf-8")
    before = StringsXmlParser.read(path)
    untouched = [etree.tostring(node) for node in before.tree.getroot() if node.get("name") != "selected"]
    StringsXmlParser.update_entries(path, {"selected": "New & <value>"})
    after = StringsXmlParser.read(path)
    assert after.values["selected"] == "New & <value>"
    assert after.tree.getroot().find("string").attrib == {"name": "selected", "formatted": "false", "product": "default"}
    assert [etree.tostring(node) for node in after.tree.getroot() if node.get("name") != "selected"] == untouched
    assert after.unsupported.keys() == {"mixed", "plural", "array"}
    assert "keep" not in after.translatable


@pytest.mark.parametrize("operation", ["parse", "update", "delete"])
def test_corrupt_xml_is_not_empty_and_original_bytes_survive(tmp_path, operation):
    path = tmp_path / "strings.xml"
    original = b'<resources><string name="x">broken'
    path.write_bytes(original)
    with pytest.raises(ResourceError) as failure:
        if operation == "parse":
            StringsXmlParser.parse(path)
        elif operation == "update":
            StringsXmlParser.update_entry(path, "x", "new")
        else:
            StringsXmlParser.delete_entry(path, "x")
    assert str(path) in str(failure.value)
    assert failure.value.__cause__ is not None
    assert path.read_bytes() == original


@pytest.mark.parametrize("error", [OSError("replace failed"), asyncio.CancelledError()])
def test_failed_or_cancelled_publication_removes_only_staging(tmp_path, monkeypatch, error):
    path = tmp_path / "strings.xml"
    path.write_bytes(b'<resources><string name="x">old</string></resources>')
    original = path.read_bytes()
    def fail(*args):
        raise error
    monkeypatch.setattr("services.xml_parser.os.replace", fail)
    with pytest.raises(type(error)):
        StringsXmlParser.update_entry(path, "x", "new")
    assert path.read_bytes() == original
    assert list(tmp_path.iterdir()) == [path]


def test_stale_selected_value_does_not_overwrite_concurrent_edit(tmp_path):
    path = tmp_path / "strings.xml"
    StringsXmlParser.update_entry(path, "x", "external")
    original = path.read_bytes()
    with pytest.raises(ResourceError, match="changed since"):
        StringsXmlParser.update_entries(path, {"x": "draft"}, expected={"x": "old"})
    assert path.read_bytes() == original


def test_plain_projection_distinguishes_empty_missing_and_protected(tmp_path):
    source, target = tmp_path / "source.xml", tmp_path / "target.xml"
    source.write_text('''<resources><string name="empty">Text</string><string name="absent">Other</string>
<string name="protected" translatable="false">Secret</string><string name="target_protected">Target</string>
<string name="mixed">Hi <b>friend</b></string><string name="target_mixed">Other</string></resources>''')
    target.write_text('''<resources><string name="empty"></string><string name="target_protected" translatable="false">Fixed</string>
<string name="target_mixed"><b>Keep</b></string></resources>''')
    entries = entries_from_documents({"values": StringsXmlParser.read(source), "values-zh": StringsXmlParser.read(target)}, "values")
    assert select_entries(entries, ["values-zh"]) == {"values-zh": {"absent": "Other"}}
    assert select_entries(entries, ["values-zh"], retranslate=True, keys=("empty", "absent", "protected", "target_protected")) == {"values-zh": {"empty": "Text"}}
    original = target.read_bytes()
    with pytest.raises(ResourceError, match="not editable"):
        StringsXmlParser.update_entry(target, "target_mixed", "Flattened")
    assert target.read_bytes() == original


def test_quotes_backslash_parity_entities_and_newlines(tmp_path):
    assert normalize_value("Don't") == r"Don\'t"
    assert normalize_value(r"Don\'t") == r"Don\'t"
    assert normalize_value("two\\\\'quote") == "two\\\\\\'quote"
    assert normalize_value('"  spaced  "') == '"  spaced  "'
    assert validate_translation("Line\\n%1$s", "行\\n%1$s") == "行\\n%1$s"
    with pytest.raises(ResourceError, match="Newline"):
        validate_translation("Line\\nnext", "Line next")
    for changed in ("%s", "%1$d", "%1$s %1$s"):
        with pytest.raises(ResourceError, match="placeholder"):
            validate_translation("Hello %1$s", changed)
    path = tmp_path / "strings.xml"
    StringsXmlParser.update_entry(path, "text", "A & B < C")
    assert b"&amp;" in path.read_bytes() and b"&amp;amp;" not in path.read_bytes()
    assert StringsXmlParser.parse(path)["text"] == "A & B < C"
    assert validate_translation("Optional", "") == ""  # Explicit manual empty is valid.


def test_delete_uses_atomic_owner_and_keeps_survivor_metadata(tmp_path):
    path = tmp_path / "strings.xml"
    path.write_text('<resources><!-- keep --><string name="x">X</string><string name="y" translatable="false">Y</string></resources>')
    assert StringsXmlParser.delete_entry(path, "x")
    assert not StringsXmlParser.delete_entry(path, "absent")
    document = StringsXmlParser.read(path)
    assert document.values == {"y": "Y"}
    assert not document.translatable
    assert b"<!-- keep -->" in path.read_bytes()


@pytest.mark.parametrize("indent", ["  ", "    ", "\t"])
def test_new_entries_inherit_indentation_without_reformatting_siblings(tmp_path, indent):
    path = tmp_path / "strings.xml"
    original = f'<resources>\n{indent}<!-- keep -->\n{indent}<string name="old" formatted="false">old</string>\n</resources>'
    path.write_text(original, encoding="utf-8")
    StringsXmlParser.update_entries(path, {"new": "new", "next": "next"})
    output = path.read_text(encoding="utf-8")
    assert f'\n{indent}<!-- keep -->\n{indent}<string name="old" formatted="false">old</string>' in output
    assert f'\n{indent}<string name="new">new</string>\n{indent}<string name="next">next</string>\n</resources>' in output


def test_new_document_defaults_to_two_space_indentation(tmp_path):
    path = tmp_path / "strings.xml"
    StringsXmlParser.update_entries(path, {"new": "new"})
    assert '\n  <string name="new">new</string>\n</resources>' in path.read_text(encoding="utf-8")


@pytest.mark.parametrize("source, translated", [
    ("%s has %d items", "%d items for %s"),
    ("%1$s %<s %2$s", "%2$s %<s %1$s"),
    ("%s %<s %d", "%s %d %<s"),
    ("%2$s %s %d", "%s %2$s %d"),
    ("%tY %d", "%d %tY"),
    ("%1$s %2$d", "%1$d %2$s"),
])
def test_format_rejects_changed_argument_binding_or_consuming_order(source, translated):
    with pytest.raises(ResourceError, match="placeholder"):
        validate_translation(source, translated)


@pytest.mark.parametrize("source, translated", [
    ("%1$s has %2$d items", "%2$d items for %1$s"),
    ("%1$tY %2$tm", "%2$tm %1$tY"),
    ("%2$s %s %<s %d %% %n", "%% %2$s %n %s %<s %d"),
    ("%s repeated %<s then %d", "name %s again %<s count %d"),
])
def test_format_preserves_explicit_reordering_and_non_consuming_literals(source, translated):
    assert validate_translation(source, translated) == translated


@pytest.mark.parametrize("value", ["%<s", "%0$s"])
def test_format_rejects_unbound_relative_or_zero_index(value):
    with pytest.raises(ResourceError, match="placeholder"):
        validate_translation(value, value)


def test_automatic_write_rechecks_same_value_target_protection_but_manual_edit_is_allowed(tmp_path):
    source, target = tmp_path / "source.xml", tmp_path / "target.xml"
    source.write_text('<resources><string name="a">A</string></resources>')
    target.write_text('<resources><string name="a" translatable="false">old</string></resources>')
    before = target.read_bytes()
    with pytest.raises(ResourceError, match="target became protected"):
        StringsXmlParser.update_entries(target, {"a": "generated"}, expected={"a": "old"},
                                        translation_source=(source, {"a": "A"}))
    assert target.read_bytes() == before
    StringsXmlParser.update_entries(target, {"a": "manual"}, expected={"a": "old"})
    assert StringsXmlParser.read(target).values["a"] == "manual"
    assert not StringsXmlParser.read(target).allows_translation("a")


def test_source_protection_change_during_staging_rejects_publication_and_cleans_staging(tmp_path, monkeypatch):
    source, target = tmp_path / "source.xml", tmp_path / "target.xml"
    source.write_text('<resources><string name="a">A</string></resources>')
    target.write_text('<resources><string name="a">old</string></resources>')
    before = target.read_bytes()
    original_read = StringsXmlParser.read
    def change_source_at_staging(path):
        document = original_read(path)
        if path.name.startswith(".strings-"):
            source.write_text('<resources><string name="a" translatable="false">A</string></resources>')
        return document
    monkeypatch.setattr(StringsXmlParser, "read", change_source_at_staging)
    with pytest.raises(ResourceError, match="source changed during save"):
        StringsXmlParser.update_entries(target, {"a": "generated"}, expected={"a": "old"},
                                        translation_source=(source, {"a": "A"}))
    assert target.read_bytes() == before
    assert sorted(path.name for path in tmp_path.iterdir()) == ["source.xml", "target.xml"]
