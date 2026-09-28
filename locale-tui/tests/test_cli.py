import asyncio
import json
from pathlib import Path

from click.testing import CliRunner
import pytest

import main
from services.translator import AITranslator
from services.xml_parser import StringsXmlParser
from test_batch import FakeClient


def seed(config, code, text):
    path = config.project_root / "res" / code / "strings.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


@pytest.fixture
def runner(config, monkeypatch):
    monkeypatch.setattr(main, "load_config", lambda: config)
    return CliRunner()


def test_dry_run_has_no_client_writes_or_key_requirement(config, runner, monkeypatch):
    path = seed(config, "values", '<resources><string name="a">A</string><string name="b">B</string></resources>')
    target = seed(config, "values-zh", '<resources><string name="b"></string></resources>')
    before = {file: file.read_bytes() for file in config.project_root.rglob("*") if file.is_file()}
    def forbidden(*args, **kwargs):
        raise AssertionError("Dry-run created translator")
    monkeypatch.setattr(main, "AITranslator", forbidden)
    result = runner.invoke(main.cli, ["translate-missing", "-l", "values-zh", "--dry-run"])
    assert result.exit_code == 0, result.output
    assert "values-zh: 1 selected" in result.output and "  a" in result.output
    assert {file: file.read_bytes() for file in config.project_root.rglob("*") if file.is_file()} == before
    assert not (config.project_root / "res" / "values-ja").exists()


def test_retranslate_union_existing_only_and_no_matches_do_not_create_client(config, runner, monkeypatch):
    seed(config, "values", '<resources><string name="a">A</string><string name="b">B</string><string name="c">C</string></resources>')
    seed(config, "values-zh", '<resources><string name="a">旧</string><string name="b"></string></resources>')
    result = runner.invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a", "--regex", "b|c", "--dry-run"])
    assert result.exit_code == 0 and "2 selected" in result.output
    assert "  c" not in result.output
    monkeypatch.setattr(main, "AITranslator", lambda *args: (_ for _ in ()).throw(AssertionError("no match created client")))
    result = runner.invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "unknown"])
    assert result.exit_code == 0 and "0 selected" in result.output


@pytest.mark.parametrize("args", [
    ["retranslate"], ["retranslate", "--regex", "["],
    ["translate-missing", "-m", "missing"], ["translate-missing", "-l", "invalid"],
    ["translate-missing", "-l", "values"], ["set", "a", "A", "-l", "../../outside"],
    *[["translate-missing", flag, value] for flag in ("--batch-size", "--concurrency", "--retries") for value in ("0", "-1")],
])
def test_cli_rejects_invalid_selection_and_limits(config, runner, args):
    seed(config, "values", '<resources><string name="a">A</string></resources>')
    result = runner.invoke(main.cli, args)
    assert result.exit_code != 0


def test_partial_batch_saves_success_once_and_returns_nonzero(config, runner, monkeypatch):
    seed(config, "values", '<resources><string name="good">Hello %s</string><string name="bad">Count %d</string></resources>')
    target = seed(config, "values-zh", '<resources><!-- context --></resources>')
    async def respond(request):
        return {"good": "你好 %s", "bad": "missing format"}
    client = FakeClient(respond)
    monkeypatch.setattr(main, "AITranslator", lambda cfg: AITranslator(cfg, client))
    result = runner.invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code != 0 and "values-zh/bad" in result.output
    assert StringsXmlParser.parse(target) == {"good": "你好 %s"}
    assert b"<!-- context -->" in target.read_bytes()
    assert client.closed


def test_batch_rejects_swapped_ordinary_arguments_but_saves_numbered_reordering(config, runner, monkeypatch):
    seed(config, "values", '<resources><string name="bad">%s has %d items</string><string name="good">%1$s has %2$d items</string></resources>')
    target = seed(config, "values-zh", '<resources/>')
    async def respond(request):
        return {"bad": "%d items for %s", "good": "%2$d items for %1$s"}
    client = FakeClient(respond)
    monkeypatch.setattr(main, "AITranslator", lambda cfg: AITranslator(cfg, client))
    result = runner.invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert result.exit_code != 0 and "argument binding" in result.output
    assert StringsXmlParser.parse(target) == {"good": "%2$d items for %1$s"}


@pytest.mark.parametrize("protected_code", ["values", "values-zh"])
def test_batch_rechecks_source_and_target_protection_after_translation(config, runner, monkeypatch, protected_code):
    seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources><string name="a">old</string></resources>')
    async def respond(request):
        value = "A" if protected_code == "values" else "old"
        seed(config, protected_code, f'<resources><string name="a" translatable="false">{value}</string></resources>')
        return {"a": "generated"}
    client = FakeClient(respond)
    monkeypatch.setattr(main, "AITranslator", lambda cfg: AITranslator(cfg, client))
    result = runner.invoke(main.cli, ["retranslate", "-l", "values-zh", "--key", "a"])
    assert result.exit_code != 0 and "protected" in result.output
    assert StringsXmlParser.parse(target)["a"] == "old"


def test_cancelled_cli_does_not_publish_successful_earlier_batches(config, runner, monkeypatch):
    config.batch_size = 1
    config.concurrency = 1
    seed(config, "values", '<resources><string name="a">A</string><string name="b">B</string></resources>')
    target = seed(config, "values-zh", '<resources/>')
    before = target.read_bytes()
    async def respond(request):
        if len(client.calls) == 1:
            return {"a": "好"}
        raise asyncio.CancelledError()
    client = FakeClient(respond)
    monkeypatch.setattr(main, "AITranslator", lambda cfg: AITranslator(cfg, client))
    with pytest.raises(asyncio.CancelledError):
        runner.invoke(main.cli, ["translate-missing", "-l", "values-zh"])
    assert target.read_bytes() == before


def test_bad_target_xml_fails_before_client_or_source_add_write(config, runner, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    seed(config, "values-zh", '<resources>broken')
    before = source.read_bytes()
    monkeypatch.setattr(main, "AITranslator", lambda *args: (_ for _ in ()).throw(AssertionError("created")))
    result = runner.invoke(main.cli, ["add", "b", "B"])
    assert result.exit_code != 0 and "XMLSyntaxError" in result.output
    assert source.read_bytes() == before


def test_manual_set_and_add_use_safe_writer_without_client(config, runner, monkeypatch):
    source = seed(config, "values", '<resources><!-- note --><string name="a">A</string></resources>')
    monkeypatch.setattr(main, "AITranslator", lambda *args: (_ for _ in ()).throw(AssertionError("created")))
    assert runner.invoke(main.cli, ["add", "quote", "Don't & forget", "--skip-translate"]).exit_code == 0
    assert StringsXmlParser.parse(source)["quote"] == r"Don\'t & forget"
    assert b"<!-- note -->" in source.read_bytes()
    result = runner.invoke(main.cli, ["set", "a", "", "-l", "values-zh"])
    assert result.exit_code == 0, result.output
    assert StringsXmlParser.parse(config.project_root / "res/values-zh/strings.xml") == {"a": ""}


def test_add_uses_same_glossary_and_validation_pipeline(config, runner, monkeypatch):
    seed(config, "values", '<resources/>')
    async def respond(request):
        text = request["messages"][0]["content"]
        assert ("提示词" in text) == text.startswith("Chinese")
        return {"prompt": "Localized %s"}
    client = FakeClient(respond)
    monkeypatch.setattr(main, "AITranslator", lambda cfg: AITranslator(cfg, client))
    result = runner.invoke(main.cli, ["add", "prompt", "Prompt %s"])
    assert result.exit_code == 0, result.output
    assert len(client.calls) == 2 and client.closed
