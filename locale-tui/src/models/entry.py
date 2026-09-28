"""In-memory translation drafts; XML remains the only persistent resource owner."""
from dataclasses import dataclass, field


@dataclass
class TranslationEntry:
    key: str
    translations: dict[str, str | None] = field(default_factory=dict)
    is_dead: bool = False
    blocked_languages: set[str] = field(default_factory=set)
    source_code: str = "values"

    def get_translation(self, lang_code: str) -> str | None:
        return self.translations.get(lang_code)

    def set_translation(self, lang_code: str, value: str) -> None:
        self.translations[lang_code] = value

    def can_translate(self, code: str) -> bool:
        return (code != self.source_code and self.source_code not in self.blocked_languages
                and code not in self.blocked_languages and self.get_translation(self.source_code) is not None)

    def has_missing_translations(self, lang_codes: list[str]) -> bool:
        return bool(self.get_missing_languages(lang_codes))

    def get_missing_languages(self, lang_codes: list[str]) -> list[str]:
        return [code for code in lang_codes if self.can_translate(code) and self.get_translation(code) is None]


def entries_from_documents(documents, source_code: str) -> list[TranslationEntry]:
    keys = set().union(*(document.values.keys() for document in documents.values()))
    return [TranslationEntry(
        key=key,
        source_code=source_code,
        translations={code: document.values.get(key) for code, document in documents.items()},
        blocked_languages={code for code, document in documents.items() if not document.allows_translation(key)},
    ) for key in sorted(keys)]
