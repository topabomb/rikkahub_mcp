---
name: locale-tui-localization
description: Use this skill when users request i18n/localization updates for Android string resources, especially when adding new keys or translating via locale-tui.
---

# Locale TUI Localization

Use the existing locale-tui resource owner for Android string edits and translation. User instructions about scope, offline work or skipping translation take precedence.

## Workflow

1. Determine the requested module, keys and languages from the task; the configured targets are the source of available choices.
2. For batch work, run `translate-missing --dry-run` or `retranslate --key/--regex --dry-run` first. Dry-run creates no API client and requires no key.
3. Translate only the authorized selection. Automatic translation uses the configured glossary for each target language. Use `--skip-translate` or `set` when translation is not requested or not allowed.
4. Check the per-language/key result and exit code. Partial failures preserve successful files and return nonzero; do not report complete success. Never print or commit credentials.
5. Inspect selected resource changes and run appropriate resource compilation/lint. Report actual changes and any unexecuted real-service or device checks.

## Commands

```bash
uv run --directory locale-tui --python 3.12 src/main.py add greeting "Welcome" -m app
uv run --directory locale-tui --python 3.12 src/main.py add greeting "Welcome" -m app --skip-translate
uv run --directory locale-tui --python 3.12 src/main.py set greeting "欢迎" -m app -l values-zh
uv run --directory locale-tui --python 3.12 src/main.py translate-missing -m app --dry-run
uv run --directory locale-tui --python 3.12 src/main.py retranslate -m app --key greeting --dry-run
uv run --directory locale-tui --python 3.12 --group dev pytest -q
```

`--lang` is repeatable for batch commands. `--key` and `--regex` form a union for retranslation and match only existing target entries; no selection or no match must never trigger an unrestricted rewrite. Positive `--batch-size`, `--concurrency` and `--retries` may override config.

## Resource and test boundaries

- `add` saves the source and translates only missing targets; existing translations require explicit `retranslate`.
- An explicit empty value is a valid present string. `translatable=false` on source or target blocks automatic translation. Unsupported inline XML/xliff, plurals and arrays are reported and left intact.
- Pass XML-decoded Android text (`&`, not an already encoded `&amp;`). Keep placeholders and newline semantics. The shared XML writer preserves unrelated nodes and attributes and publishes each file through staging/replace; cross-language writes are not one transaction.
- Keep Java Formatter argument bindings: ordinary/relative placeholders retain consuming order; purely explicit numbered placeholders may reorder. `%%` and `%n` consume no argument.
- TUI translation changes memory only. Save commits dirty entries, keeps failed drafts, and does not overwrite corrupt files. Delete is an explicit immediate action through the same writer.
- Automatic drafts retain source text across refresh. The shared writer rechecks source text and source/target translation eligibility before publication; protection changes reject the save without discarding drafts. Explicitly edited target values become manual drafts, still subject to XML and stale-value checks. Reopen to discard obsolete generated drafts before translating a permanently changed source.
- Tests are offline by default. Real API tests require separate explicit authorization and `--run-live`; never use real translation tests as a substitute for local XML safety tests.
