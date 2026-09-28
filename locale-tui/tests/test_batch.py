import asyncio
import json
from types import SimpleNamespace

import pytest

from models.entry import TranslationEntry
from services.translator import AITranslator, TranslationError


class FakeClient:
    def __init__(self, respond):
        self.respond = respond
        self.calls = []
        self.closed = False
        self.chat = SimpleNamespace(completions=self)

    async def create(self, **request):
        self.calls.append(request)
        response = await self.respond(request)
        if isinstance(response, Exception):
            raise response
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(response)))])

    async def close(self):
        self.closed = True


async def test_partial_result_keeps_valid_items_and_reports_missing_invalid_and_io(config):
    async def respond(request):
        prompt = request["messages"][0]["content"]
        if '"network"' in prompt:
            raise OSError("specific transport detail")
        return {"ok": "可以 %1$s", "bad": "wrong %d"}
    config.batch_size = 3
    translator = AITranslator(config, FakeClient(respond))
    result = await translator.translate_many({"values-zh": {"ok": "OK %1$s", "bad": "Count %1$d", "missing": "Missing", "network": "Network"}})
    assert result.successes == {"values-zh": {"ok": "可以 %1$s"}}
    assert set(result.failures) == {("values-zh", "bad"), ("values-zh", "missing"), ("values-zh", "network")}
    assert "specific transport detail" in str(result.failures["values-zh", "network"])
    assert result.failures["values-zh", "network"].__cause__.__class__ is OSError


async def test_retries_only_failed_keys(config):
    config.retries = 2
    async def respond(request):
        if len(client.calls) == 1:
            return {"ok": "好", "retry": "bad %d"}
        assert '"ok"' not in request["messages"][0]["content"]
        return {"retry": "重试 %s"}
    client = FakeClient(respond)
    result = await AITranslator(config, client).translate_many({"values-zh": {"ok": "OK", "retry": "Retry %s"}})
    assert result.count == 2 and not result.failures
    assert len(client.calls) == 2


async def test_cancel_joins_requests_and_does_not_publish_partial_tui_drafts(config):
    entered = asyncio.Event()
    exited = asyncio.Event()
    async def respond(request):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            exited.set()
    entry = TranslationEntry("key", {"values": "Text"})
    task = asyncio.create_task(AITranslator(config, FakeClient(respond)).translate_all_missing([entry], ["values-zh"]))
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert exited.is_set()
    assert entry.translations == {"values": "Text"}


def test_glossary_is_target_specific_and_client_is_lazy(config, monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("client created")
    monkeypatch.setattr("services.translator.AsyncOpenAI", forbidden)
    translator = AITranslator(config)
    assert "提示词" in translator.build_prompt({"p": "Prompt"}, "values-zh")
    assert "提示词" not in translator.build_prompt({"p": "Prompt"}, "values-ja")
    config.translation_prompt = "{target_language}: {source_strings}"
    assert "提示词" in translator.build_prompt({"p": "Prompt"}, "Chinese")


@pytest.mark.parametrize("name", ["batch_size", "concurrency", "retries"])
@pytest.mark.parametrize("value", [0, -1, True])
def test_nonpositive_or_noninteger_limits_fail_before_requests(config, name, value):
    setattr(config, name, value)
    with pytest.raises(ValueError, match=name):
        AITranslator(config)


async def test_concurrency_is_bounded_and_unknown_response_keys_fail(config):
    config.batch_size = 1
    current = peak = 0
    async def respond(request):
        nonlocal current, peak
        current += 1
        peak = max(peak, current)
        await asyncio.sleep(0)
        current -= 1
        return {"unrequested": "Injected"}
    result = await AITranslator(config, FakeClient(respond)).translate_many({"values-zh": {str(n): "Text" for n in range(5)}})
    assert peak <= config.concurrency
    assert not result.successes and len(result.failures) == 5
