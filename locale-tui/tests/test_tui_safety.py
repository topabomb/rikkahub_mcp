import asyncio

import pytest
from textual.app import App
from textual.widgets import DataTable
from textual.worker import WorkerCancelled

from screens.translation_table import TranslationTableScreen
from services.translator import AITranslator
from services.xml_parser import StringsXmlParser
from test_batch import FakeClient
from test_cli import seed


class TableApp(App):
    def __init__(self, screen):
        super().__init__()
        self.table_screen = screen

    def on_mount(self):
        self.push_screen(self.table_screen)


def create_screen(config, monkeypatch):
    screen = TranslationTableScreen(config, config.modules[0])
    notices = []
    monkeypatch.setattr(screen, "notify", lambda message, **kwargs: notices.append(str(message)))
    return screen, notices


async def test_tui_translates_only_eligible_missing_and_saves_explicitly(config, monkeypatch):
    seed(config, "values", '''<resources><string name="a">A</string><string name="empty">Empty</string>
<string name="protected" translatable="false">Protected</string><string name="target_protected">T</string></resources>''')
    target = seed(config, "values-zh", '''<resources><!-- context --><string name="empty"></string>
<string name="target_protected" translatable="false">Fixed</string></resources>''')
    config.languages = config.languages[:2]
    before = target.read_bytes()
    async def respond(request):
        assert '"protected"' not in request["messages"][0]["content"]
        assert '"empty"' not in request["messages"][0]["content"]
        return {"a": "好"}
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        await screen.action_translate_missing().wait()
        assert target.read_bytes() == before
        assert screen.has_unsaved_changes
        screen.action_save_all()
        assert not screen.has_unsaved_changes
        assert StringsXmlParser.parse(target) == {"a": "好", "empty": "", "target_protected": "Fixed"}
        assert b"<!-- context -->" in target.read_bytes()
    assert client.closed


async def test_partial_save_retains_only_failed_file_drafts_and_retry(config, monkeypatch):
    seed(config, "values", '<resources><string name="a">A</string></resources>')
    zh = seed(config, "values-zh", '<resources><!-- keep --><string name="a">old</string></resources>')
    ja = seed(config, "values-ja", '<resources><string name="a">old</string></resources>')
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        screen.on_edit_complete({"key": "a", "translations": {"values": "A", "values-zh": "新", "values-ja": "新しい"}})
        original = StringsXmlParser.update_entries
        def fail_ja(path, *args, **kwargs):
            if path == ja:
                raise OSError("disk full on Japanese file")
            return original(path, *args, **kwargs)
        monkeypatch.setattr(StringsXmlParser, "update_entries", fail_ja)
        screen.action_save_all()
        assert screen.dirty_values("values-zh") == {}
        assert screen.dirty_values("values-ja") == {"a": "新しい"}
        assert StringsXmlParser.parse(zh)["a"] == "新"
        assert StringsXmlParser.parse(ja)["a"] == "old"
        assert "disk full" in notices[-1] and "All changes saved" not in notices[-1]
        monkeypatch.setattr(StringsXmlParser, "update_entries", original)
        screen.action_save_all()
        assert not screen.has_unsaved_changes
        assert StringsXmlParser.parse(ja)["a"] == "新しい"


async def test_failed_refresh_preserves_draft_blocks_save_and_retry_does_not_discard(config, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources><string name="a">old</string></resources>')
    source_original = source.read_bytes()
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        screen.on_edit_complete({"key": "a", "translations": {"values-zh": "draft"}})
        source.write_bytes(b'<resources>broken')
        screen.action_refresh()
        assert screen.load_failed and screen.dirty_values("values-zh") == {"a": "draft"}
        screen.action_save_all()
        assert StringsXmlParser.parse(target)["a"] == "old"
        source.write_bytes(source_original)
        screen.action_refresh()
        assert not screen.load_failed and screen.dirty_values("values-zh") == {"a": "draft"}
        screen.action_save_all()
        assert StringsXmlParser.parse(target)["a"] == "draft"


async def test_cancelled_tui_translation_never_writes_or_mutates_draft(config, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    entered, exited = asyncio.Event(), asyncio.Event()
    async def respond(request):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            exited.set()
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        worker = screen.action_translate_missing()
        await entered.wait()
        worker.cancel()
        with pytest.raises(WorkerCancelled):
            await worker.wait()
        await pilot.pause()
        assert exited.is_set() and client.closed
        assert not screen.translating and not screen.has_unsaved_changes
        assert not (config.project_root / "res/values-zh/strings.xml").exists()
        assert not any("Translation failed" in message for message in notices)


async def test_delete_partial_failure_keeps_remaining_row_for_retry(config, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources><string name="a">old</string></resources>')
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        original = StringsXmlParser.delete_entry
        def fail_target(path, key):
            if path == target:
                raise OSError("delete failed")
            return original(path, key)
        monkeypatch.setattr(StringsXmlParser, "delete_entry", fail_target)
        screen.action_delete_entry()
        assert StringsXmlParser.parse(source) == {}
        assert len(screen.entries) == 1
        assert screen.entries[0].get_translation("values-zh") == "old"
        assert "delete failed" in notices[-1]
        monkeypatch.setattr(StringsXmlParser, "delete_entry", original)
        screen.action_delete_entry()
        assert screen.entries == [] and StringsXmlParser.parse(target) == {}


@pytest.mark.parametrize("source_xml, diagnostic", [
    ('<resources><string name="a" translatable="false">A</string></resources>', "source missing or protected"),
    ('<resources/>', "source missing or protected"),
    ('<resources><string name="a">Changed</string></resources>', "source changed"),
])
async def test_automatic_draft_rechecks_source_preserves_after_refresh_and_retries(config, monkeypatch, source_xml, diagnostic):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources><!-- keep --></resources>')
    config.languages = config.languages[:2]
    original_source, original_target = source.read_bytes(), target.read_bytes()
    async def respond(request):
        return {"a": "generated"}
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        await screen.action_translate_missing().wait()
        source.write_text(source_xml, encoding="utf-8")
        screen.action_refresh()
        screen.action_save_all()
        assert target.read_bytes() == original_target
        assert screen.dirty_values("values-zh") == {"a": "generated"}
        assert screen.automatic_sources == {("values-zh", "a"): "A"}
        assert diagnostic in notices[-1]
        source.write_bytes(original_source)
        screen.action_refresh()
        screen.action_save_all()
        assert StringsXmlParser.parse(target)["a"] == "generated"
        assert not screen.has_unsaved_changes and not screen.automatic_sources


async def test_protected_automatic_target_retains_only_failed_language_and_explicit_edit_can_save(config, monkeypatch):
    seed(config, "values", '<resources><string name="a">A</string></resources>')
    zh = seed(config, "values-zh", '<resources/>')
    ja = seed(config, "values-ja", '<resources/>')
    async def respond(request):
        return {"a": "generated"}
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        await screen.action_translate_missing().wait()
        zh.write_text('<resources><string name="a" translatable="false">external</string></resources>')
        before = zh.read_bytes()
        screen.action_save_all()
        assert zh.read_bytes() == before
        assert "target became protected" in notices[-1]
        assert StringsXmlParser.parse(ja)["a"] == "generated"
        assert screen.dirty_values("values-ja") == {}
        assert screen.automatic_sources == {("values-zh", "a"): "A"}
        # Reopen after an external value conflict, then explicitly edit the protected resource.
    reopened, _ = create_screen(config, monkeypatch)
    async with TableApp(reopened).run_test() as pilot:
        await pilot.pause()
        reopened.on_edit_complete({"key": "a", "translations": {"values-zh": "manual"}})
        reopened.action_save_all()
        assert StringsXmlParser.parse(zh)["a"] == "manual"
        assert not StringsXmlParser.read(zh).allows_translation("a")


async def test_source_draft_saves_before_generated_target_regardless_of_language_order(config, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources/>')
    config.languages = [config.languages[1], config.languages[0]]
    async def respond(request):
        assert "Edited source" in request["messages"][0]["content"]
        return {"a": "generated"}
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, notices = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        screen.on_edit_complete({"key": "a", "translations": {"values": "Edited source"}})
        await screen.action_translate_missing().wait()
        screen.action_save_all()
        assert StringsXmlParser.parse(source)["a"] == "Edited source"
        assert StringsXmlParser.parse(target)["a"] == "generated"
        assert not screen.has_unsaved_changes


async def test_explicitly_revised_generated_draft_becomes_manual(config, monkeypatch):
    source = seed(config, "values", '<resources><string name="a">A</string></resources>')
    target = seed(config, "values-zh", '<resources/>')
    config.languages = config.languages[:2]
    async def respond(request):
        return {"a": "generated"}
    client = FakeClient(respond)
    monkeypatch.setattr("screens.translation_table.AITranslator", lambda cfg: AITranslator(cfg, client))
    screen, _ = create_screen(config, monkeypatch)
    async with TableApp(screen).run_test() as pilot:
        await pilot.pause()
        await screen.action_translate_missing().wait()
        assert screen.automatic_sources == {("values-zh", "a"): "A"}
        source.write_text('<resources><string name="a" translatable="false">A</string></resources>')
        screen.on_edit_complete({"key": "a", "translations": {"values-zh": "manual"}})
        assert not screen.automatic_sources
        screen.action_save_all()
        assert StringsXmlParser.parse(target)["a"] == "manual"
