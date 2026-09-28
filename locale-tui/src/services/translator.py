"""Shared bounded translation pipeline for CLI and the in-memory TUI."""
import asyncio
import json
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from openai import AsyncOpenAI
from services.xml_parser import validate_translation

if TYPE_CHECKING:
    from config import Config


class TranslationError(Exception):
    """An API or response validation failure."""


@dataclass
class TranslationResult:
    successes: dict[str, dict[str, str]] = field(default_factory=dict)
    failures: dict[tuple[str, str], Exception] = field(default_factory=dict)

    @property
    def count(self) -> int:
        return sum(len(values) for values in self.successes.values())


def select_entries(entries, languages, *, retranslate=False, keys=(), pattern=None):
    """CLI and TUI share eligibility, including absent versus explicitly empty text."""
    selected = {}
    for code in languages:
        values = {}
        for entry in entries:
            if not entry.can_translate(code):
                continue
            exists = entry.get_translation(code) is not None
            if retranslate:
                if not exists or not (entry.key in keys or (pattern and pattern.search(entry.key))):
                    continue
            elif exists:
                continue
            values[entry.key] = entry.get_translation(entry.source_code)
        selected[code] = values
    return selected


class AITranslator:
    def __init__(self, config: "Config", client=None):
        config.validate()
        self.config = config
        self._client = client

    @property
    def client(self):
        if self._client is None:
            if not self.config.openai_api_key:
                raise TranslationError("OPENAI_API_KEY is required for translation")
            self._client = AsyncOpenAI(api_key=self.config.openai_api_key, base_url=self.config.openai_base_url,
                                       max_retries=0)
        return self._client

    async def close(self):
        if self._client is not None:
            await self._client.close()

    async def test_connection(self):
        response = await self.client.chat.completions.create(
            model=self.config.translation_model,
            messages=[{"role": "user", "content": "Reply exactly with OK to confirm the connection."}],
            temperature=0, max_tokens=16,
        )
        if not response.choices:
            raise TranslationError("No choices in API response")
        return response.choices[0].message.content

    def build_prompt(self, entries, language):
        code = next((lang.code for lang in self.config.languages if language in (lang.code, lang.name)), None)
        if code is None:
            raise TranslationError(f"Unknown language: {language}")
        terms = self.config.glossary.get(code, {})
        glossary = json.dumps(terms, ensure_ascii=False)
        prompt = self.config.translation_prompt.format(
            target_language=self.config.get_language_name(code),
            source_strings=json.dumps(entries, ensure_ascii=False, indent=2),
            glossary=glossary,
        )
        if "{glossary}" not in self.config.translation_prompt and terms:
            prompt += "\nTerminology for this target language (JSON):\n" + glossary
        return prompt

    async def translate_batch(self, entries: dict[str, str], target_language: str) -> dict[str, str]:
        try:
            response = await self.client.chat.completions.create(
                model=self.config.translation_model,
                messages=[{"role": "user", "content": self.build_prompt(entries, target_language)}],
                temperature=1.0,
            )
            content = response.choices[0].message.content
            if not content:
                raise TranslationError("Empty response from API")
            content = content.strip()
            if content.startswith("```") and content.endswith("```"):
                content = "\n".join(content.splitlines()[1:-1])
            result = json.loads(content)
            if not isinstance(result, dict) or any(key not in entries for key in result):
                raise TranslationError("Response must be an object containing only requested keys")
            return result
        except Exception as error:
            raise TranslationError(f"{type(error).__name__}: {error}") from error

    async def translate_many(self, selected, progress_callback=None) -> TranslationResult:
        """Return per-key outcomes; cancellation joins siblings before returning to the caller."""
        self.config.validate()
        for code in selected:
            if code not in self.config.get_language_codes() or code == self.config.get_source_language().code:
                raise TranslationError(f"Invalid target language: {code}")
        result = TranslationResult()
        semaphore = asyncio.Semaphore(self.config.concurrency)
        completed = {code: 0 for code in selected}

        async def translate(code, batch):
            pending = dict(batch)
            async with semaphore:
                for _ in range(self.config.retries):
                    try:
                        values = await self.translate_batch(pending, code)
                        for key, source in list(pending.items()):
                            try:
                                if key not in values:
                                    raise TranslationError("Response omitted key")
                                if source and isinstance(values[key], str) and not values[key].strip():
                                    raise TranslationError("Translation is empty")
                                value = validate_translation(source, values[key])
                            except Exception as error:
                                result.failures[code, key] = error
                            else:
                                result.successes.setdefault(code, {})[key] = value
                                result.failures.pop((code, key), None)
                                del pending[key]
                    except Exception as error:
                        for key in pending:
                            result.failures[code, key] = error
                    if not pending:
                        break
                completed[code] += len(batch)
                if progress_callback:
                    progress_callback(code, completed[code], len(selected[code]), f"{code}: {completed[code]}/{len(selected[code])}")

        tasks = []
        async with asyncio.TaskGroup() as group:
            for code, values in selected.items():
                keys = list(values)
                for offset in range(0, len(keys), self.config.batch_size):
                    tasks.append(group.create_task(translate(code, {key: values[key] for key in keys[offset:offset + self.config.batch_size]})))
        if any(task.cancelled() for task in tasks):
            raise asyncio.CancelledError()
        return result

    async def translate_all_missing(self, entries, target_languages, progress_callback=None) -> TranslationResult:
        targets = [code for code in target_languages if code != self.config.get_source_language().code]
        result = await self.translate_many(select_entries(entries, targets), progress_callback)
        by_key = {entry.key: entry for entry in entries}
        for code, values in result.successes.items():
            for key, value in values.items():
                by_key[key].set_translation(code, value)
        return result
