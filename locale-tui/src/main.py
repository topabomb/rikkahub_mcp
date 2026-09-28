#!/usr/bin/env python3
"""Android Locale Manager CLI and TUI entry point."""
import asyncio
import functools
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import click
from app import LocaleTuiApp
from config import Config
from models.entry import entries_from_documents
from services.translator import AITranslator, select_entries
from services.xml_parser import ResourceError, StringsXmlParser, describe_error, normalize_value, validate_translation


def guard(function):
    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except click.ClickException:
            raise
        except Exception as error:
            raise click.ClickException(describe_error(error)) from error
    return wrapped


def load_config() -> Config:
    return Config.load(Path(__file__).parent.parent / "config.yml")


def resource_directory(config, module):
    config.validate()
    selected = next((item for item in config.modules if item.name == module), None) if module else config.modules[0]
    if selected is None:
        raise click.BadParameter(f"Unknown module: {module}", param_hint="--module")
    path = config.project_root / selected.res_path
    if not path.is_dir():
        raise ResourceError(f"Resource directory does not exist: {path}")
    return path


def target_languages(config, requested):
    source = config.get_source_language().code
    targets = list(dict.fromkeys(requested)) if requested else [lang.code for lang in config.languages if not lang.is_source]
    for code in targets:
        if code not in config.get_language_codes() or code == source:
            raise click.BadParameter(f"Invalid target language: {code}", param_hint="--lang")
    return targets


def read_documents(directory, codes, source_code, *, require_source=True):
    if require_source and not (directory / source_code / "strings.xml").is_file():
        raise ResourceError(f"Source file missing: {directory / source_code / 'strings.xml'}")
    documents = {code: StringsXmlParser.read(directory / code / "strings.xml") for code in dict.fromkeys([source_code, *codes])}
    for code, document in documents.items():
        for key, reason in document.unsupported.items():
            click.echo(f"Skipped {code}/{key}: {reason}", err=True)
    return documents


async def translate(config, selected):
    translator = AITranslator(config)
    try:
        return await translator.translate_many(selected)
    finally:
        await translator.close()


def publish_result(directory, documents, source, result):
    failures = dict(result.failures)
    count = 0
    for code, values in result.successes.items():
        path = directory / code / "strings.xml"
        try:
            StringsXmlParser.update_entries(path, values,
                expected={key: documents[code].values.get(key) for key in values},
                translation_source=(directory / source / "strings.xml",
                                    {key: documents[source].values[key] for key in values}))
            count += len(values)
        except (OSError, ResourceError) as error:
            failures.update({(code, key): error for key in values})
    click.echo(f"Saved {count} translation(s); failed {len(failures)}")
    for (code, key), error in failures.items():
        click.echo(f"{code}/{key}: {describe_error(error)}", err=True)
    if failures:
        raise click.ClickException("Some translations were not saved; successful items were preserved")


@click.group(invoke_without_command=True)
@click.pass_context
@guard
def cli(ctx):
    """Android string resources. Without a subcommand, launch the TUI."""
    if ctx.invoked_subcommand is None:
        LocaleTuiApp(load_config()).run()


@cli.command("test-connection")
@guard
def test_connection():
    """Explicitly test the configured real AI service."""
    async def run():
        translator = AITranslator(load_config())
        try:
            return await translator.test_connection()
        finally:
            await translator.close()
    try:
        click.echo(asyncio.run(run()) or "Connected")
    except Exception as error:
        raise click.ClickException(describe_error(error)) from error


def batch_options(function):
    for decorator in (
        click.option("--module", "-m"),
        click.option("--lang", "-l", multiple=True, help="Target code; repeat to select several languages"),
        click.option("--dry-run", is_flag=True, help="List selected keys without creating an API client or writing"),
        click.option("--concurrency", type=click.IntRange(min=1)),
        click.option("--retries", type=click.IntRange(min=1), help="Maximum attempts per failed batch, including the first"),
        click.option("--batch-size", type=click.IntRange(min=1)),
    ):
        function = decorator(function)
    return function


def run_batch(module, lang, dry_run, concurrency, retries, batch_size, *, keys=(), regex=None, retranslate=False):
    if retranslate and not keys and regex is None:
        raise click.UsageError("retranslate requires --key or --regex")
    try:
        pattern = re.compile(regex) if regex is not None else None
    except re.error as error:
        raise click.BadParameter(str(error), param_hint="--regex") from error
    config = load_config()
    for name, value in (("concurrency", concurrency), ("retries", retries), ("batch_size", batch_size)):
        if value is not None:
            setattr(config, name, value)
    directory = resource_directory(config, module)
    targets = target_languages(config, lang)
    source = config.get_source_language().code
    documents = read_documents(directory, targets, source)
    entries = entries_from_documents(documents, source)
    selected = select_entries(entries, targets, retranslate=retranslate, keys=keys, pattern=pattern)
    for code, values in selected.items():
        click.echo(f"{code}: {len(values)} selected")
        if dry_run:
            for key in values:
                click.echo(f"  {key}")
    if dry_run or not any(selected.values()):
        return
    publish_result(directory, documents, source, asyncio.run(translate(config, selected)))


@cli.command("translate-missing")
@batch_options
@guard
def translate_missing(**options):
    """Translate absent plain strings; preserve explicit empty and protected entries."""
    run_batch(**options)


@cli.command("retranslate")
@batch_options
@click.option("--key", "keys", multiple=True, help="Existing key; repeatable and combined with --regex")
@click.option("--regex", help="Select existing keys by regular expression")
@guard
def retranslate(**options):
    """Retranslate selected existing targets only."""
    run_batch(**options, retranslate=True)


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option("--module", "-m")
@click.option("--skip-translate", is_flag=True)
@guard
def add(key, value, module, skip_translate):
    """Add/update source text; translate missing targets through the same batch pipeline."""
    config = load_config()
    directory = resource_directory(config, module)
    source = config.get_source_language().code
    targets = target_languages(config, ()) if not skip_translate else []
    documents = read_documents(directory, targets, source, require_source=False)
    value = normalize_value(value)
    StringsXmlParser.update_entry(directory / source / "strings.xml", key, value)
    click.echo(f"Saved {source}/{key}")
    if targets:
        documents[source] = StringsXmlParser.read(directory / source / "strings.xml")
        entries = [entry for entry in entries_from_documents(documents, source) if entry.key == key]
        selected = select_entries(entries, targets)
        if any(selected.values()):
            publish_result(directory, documents, source, asyncio.run(translate(config, selected)))


@cli.command("set")
@click.argument("key")
@click.argument("value")
@click.option("--lang", "-l")
@click.option("--module", "-m")
@guard
def set_value(key, value, lang, module):
    """Manually patch one string, without calling an AI service."""
    config = load_config()
    directory = resource_directory(config, module)
    source = config.get_source_language().code
    lang = lang or source
    if lang not in config.get_language_codes():
        raise click.BadParameter(f"Unknown language: {lang}", param_hint="--lang")
    documents = read_documents(directory, [lang], source)
    if lang != source:
        if key not in documents[source].values:
            raise ResourceError(f"No editable source string for {key}")
        value = validate_translation(documents[source].values[key], value)
    StringsXmlParser.update_entry(directory / lang / "strings.xml", key, value)
    click.echo(f"Saved {lang}/{key}")


@cli.command("list-keys")
@click.option("--module", "-m")
@guard
def list_keys(module):
    config = load_config()
    directory = resource_directory(config, module)
    source = config.get_source_language().code
    document = read_documents(directory, [], source)[source]
    for key, value in document.values.items():
        click.echo(f"{key}: {value[:60]}")


def main():
    cli()


if __name__ == "__main__":
    main()
