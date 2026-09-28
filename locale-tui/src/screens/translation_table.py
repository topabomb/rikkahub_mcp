"""Translation table screen."""

from __future__ import annotations

from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.screen import Screen
from textual.widgets import Header, Footer, DataTable, Input, Static, ProgressBar
from textual.containers import Container, Horizontal, Vertical
from textual.binding import Binding
from textual import work

from models.entry import TranslationEntry, entries_from_documents
from services.xml_parser import StringsXmlParser, ResourceError, describe_error, normalize_value, validate_translation
from services.translator import AITranslator
from services.dead_entry_finder import DeadEntryFinder

if TYPE_CHECKING:
    from config import Config, ModuleConfig


class TranslationTableScreen(Screen):
    """Translation table screen."""

    BINDINGS = [
        Binding("escape", "go_back", "Back"),
        Binding("t", "translate_missing", "Translate"),
        Binding("d", "toggle_dead_filter", "Dead Filter"),
        Binding("m", "toggle_missing_filter", "Missing Filter"),
        Binding("slash", "focus_search", "Search"),
        Binding("delete", "delete_entry", "Delete"),
        Binding("s", "save_all", "Save"),
        Binding("enter", "edit_entry", "Edit"),
        Binding("r", "refresh", "Refresh"),
    ]

    def __init__(self, config: "Config", module: "ModuleConfig"):
        super().__init__()
        self.config = config
        self.module = module
        self.entries: list[TranslationEntry] = []
        self.filtered_entries: list[TranslationEntry] = []
        self.show_dead_only = False
        self.show_missing_only = False
        self.search_query = ""
        self.documents = {}
        self.baselines = {}
        # Generated drafts retain provenance across refresh/failure until saved or explicitly edited.
        self.automatic_sources: dict[tuple[str, str], str] = {}
        self.load_failed = True
        self.translating = False
        self.discard_confirmed = False

    @property
    def has_unsaved_changes(self):
        return any(self.dirty_values(code) for code in self.config.get_language_codes())

    def dirty_values(self, code):
        baseline = self.baselines.get(code, {})
        return {entry.key: entry.get_translation(code) for entry in self.entries
                if entry.get_translation(code) is not None and entry.get_translation(code) != baseline.get(entry.key)}

    def file_path(self, code):
        return self.config.project_root / self.module.res_path / code / "strings.xml"

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(
            Vertical(
                # Status bar
                Horizontal(
                    Static(f"Module: [bold]{self.module.name}[/bold]", id="module-name"),
                    Static("", id="status"),
                    Static("", id="filter-status"),
                    id="status-bar",
                ),
                # Search box
                Input(placeholder="Search entries... (press / to focus)", id="search"),
                # Progress bar (hidden)
                ProgressBar(total=100, show_eta=False, id="progress"),
                # Translation table
                DataTable(id="table", cursor_type="row", zebra_stripes=True),
                id="content",
            ),
            id="main-container",
        )
        yield Footer()

    def on_mount(self) -> None:
        """Initialize data when screen loads."""
        # Hide progress bar
        self.query_one("#progress", ProgressBar).display = False

        # Setup table columns
        table = self.query_one("#table", DataTable)
        table.add_column("Key", key="key", width=30)

        for lang in self.config.languages:
            col_name = lang.name if len(lang.name) <= 15 else lang.code
            table.add_column(col_name, key=lang.code, width=25)

        # Load data
        self.load_entries()

        # Focus table by default
        table.focus()

    def load_entries(self) -> None:
        """Load all translation entries."""
        source = self.config.get_source_language().code
        try:
            if not self.file_path(source).is_file():
                raise ResourceError(f"Source file missing: {self.file_path(source)}")
            documents = {lang.code: StringsXmlParser.read(self.file_path(lang.code)) for lang in self.config.languages}
            entries = entries_from_documents(documents, source)
        except (OSError, ResourceError) as error:
            self.load_failed = True
            self.notify(f"Read failed: {describe_error(error)}", severity="error")
            return
        baselines = {code: dict(document.values) for code, document in documents.items()}
        by_key = {entry.key: entry for entry in entries}
        # Refresh may recover a failed read, but must never discard an existing draft.
        for code in self.config.get_language_codes():
            for key, value in self.dirty_values(code).items():
                if key not in by_key:
                    by_key[key] = TranslationEntry(key, source_code=source)
                by_key[key].set_translation(code, value)
                baselines[code][key] = self.baselines.get(code, {}).get(key)
        self.documents = documents
        self.baselines = baselines
        self.entries = sorted(by_key.values(), key=lambda entry: entry.key)
        self.load_failed = False
        for code, document in documents.items():
            for key, reason in document.unsupported.items():
                self.notify(f"Not edited: {code}/{key}: {reason}")

        # Mark dead entries
        if self.module.source_patterns:
            finder = DeadEntryFinder(self.config.project_root)
            dead_count = finder.mark_dead_entries(
                self.entries, self.module.source_patterns
            )
            self.notify(f"Found {dead_count} dead entries")

        self.apply_filters()
        self.update_status()

    def apply_filters(self) -> None:
        """Apply search and filter conditions."""
        self.filtered_entries = self.entries.copy()

        # Apply dead filter
        if self.show_dead_only:
            self.filtered_entries = [e for e in self.filtered_entries if e.is_dead]

        # Apply missing filter
        if self.show_missing_only:
            lang_codes = self.config.get_language_codes()
            self.filtered_entries = [
                e for e in self.filtered_entries
                if e.has_missing_translations(lang_codes)
            ]

        # Apply search
        if self.search_query:
            query = self.search_query.lower()
            self.filtered_entries = [
                e
                for e in self.filtered_entries
                if query in e.key.lower()
                or any(query in (v or "").lower() for v in e.translations.values())
            ]

        self.refresh_table()

    def refresh_table(self) -> None:
        """Refresh table display."""
        table = self.query_one("#table", DataTable)
        table.clear()

        for entry in self.filtered_entries:
            row_data = [entry.key]
            for lang in self.config.languages:
                value = entry.translations.get(lang.code, "")
                # Highlight missing translations
                if value is None and entry.can_translate(lang.code):
                    row_data.append("[red]MISSING[/red]")
                elif entry.is_dead:
                    row_data.append(f"[dim]{value or ''}[/dim]")
                else:
                    # Truncate long values for display
                    display_value = value or ""
                    if len(display_value) > 30:
                        display_value = display_value[:27] + "..."
                    row_data.append(display_value)

            table.add_row(*row_data, key=entry.key)

    def update_status(self) -> None:
        """Update status bar."""
        total = len(self.entries)
        missing = sum(
            1
            for e in self.entries
            if e.has_missing_translations(self.config.get_language_codes())
        )
        dead = sum(1 for e in self.entries if e.is_dead)

        status = f"Total: {total} | Missing: {missing} | Dead: {dead}"
        if self.has_unsaved_changes:
            status += " | [yellow]Unsaved[/yellow]"

        self.query_one("#status", Static).update(status)

        # Update filter status
        filter_text = []
        if self.show_dead_only:
            filter_text.append("[cyan]Dead Only[/cyan]")
        if self.show_missing_only:
            filter_text.append("[cyan]Missing Only[/cyan]")
        if self.search_query:
            filter_text.append(f"[cyan]Search: {self.search_query}[/cyan]")

        self.query_one("#filter-status", Static).update(" | ".join(filter_text))

    def on_input_changed(self, event: Input.Changed) -> None:
        """Search box content changed."""
        if event.input.id == "search":
            self.search_query = event.value
            self.apply_filters()
            self.update_status()

    def action_go_back(self) -> None:
        """Go back to previous screen."""
        if self.has_unsaved_changes and not self.discard_confirmed:
            self.notify(
                "You have unsaved changes! Press 's' to save or 'escape' again to discard."
            )
            self.discard_confirmed = True
        else:
            self.workers.cancel_all()
            self.app.pop_screen()

    def action_focus_search(self) -> None:
        """Focus search box."""
        self.query_one("#search", Input).focus()

    def action_toggle_dead_filter(self) -> None:
        """Toggle dead entry filter."""
        self.show_dead_only = not self.show_dead_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Dead filter: {'ON' if self.show_dead_only else 'OFF'}")

    def action_toggle_missing_filter(self) -> None:
        """Toggle missing translation filter."""
        self.show_missing_only = not self.show_missing_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Missing filter: {'ON' if self.show_missing_only else 'OFF'}")

    def action_edit_entry(self) -> None:
        """Edit current selected entry."""
        if self.translating or self.load_failed:
            self.notify("Wait for translation or refresh the failed resource read", severity="warning")
            return
        from widgets.edit_modal import EditModal

        table = self.query_one("#table", DataTable)
        if not table.row_count:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry = next((e for e in self.entries if e.key == row_key.value), None)

        if entry:
            self.app.push_screen(
                EditModal(entry, self.config.languages), callback=self.on_edit_complete
            )

    def on_edit_complete(self, result: dict | None) -> None:
        """Edit complete callback."""
        if result:
            entry_key = result["key"]
            entry = next((e for e in self.entries if e.key == entry_key), None)
            if entry:
                updates = {}
                source_code = self.config.get_source_language().code
                source = result["translations"].get(source_code, entry.get_translation(source_code))
                try:
                    for lang_code, value in result["translations"].items():
                        previous = entry.get_translation(lang_code)
                        if value == previous or (previous is None and value == ""):
                            continue
                        if entry_key in self.documents[lang_code].unsupported:
                            raise ResourceError(f"{lang_code}/{entry_key}: unsupported XML is not editable")
                        updates[lang_code] = (validate_translation(source, value)
                                              if lang_code != source_code and source is not None
                                              else normalize_value(value))
                except (ValueError, ResourceError) as error:
                    self.notify(f"Invalid value: {describe_error(error)}", severity="error")
                    return
                for lang_code, value in updates.items():
                    entry.set_translation(lang_code, value)
                    self.automatic_sources.pop((lang_code, entry_key), None)
                self.discard_confirmed = False
                self.refresh_table()
                self.update_status()
                self.notify(f"Updated: {entry_key}")

    def action_delete_entry(self) -> None:
        """Delete current selected entry."""
        if self.translating or self.load_failed:
            self.notify("Wait for translation or refresh the failed resource read", severity="warning")
            return
        table = self.query_one("#table", DataTable)
        if not table.row_count:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry_key = row_key.value

        entry = next(item for item in self.entries if item.key == entry_key)
        failures = []
        for lang in self.config.languages:
            try:
                StringsXmlParser.delete_entry(self.file_path(lang.code), entry_key)
                self.baselines[lang.code].pop(entry_key, None)
                entry.translations[lang.code] = None
                self.automatic_sources.pop((lang.code, entry_key), None)
            except (OSError, ResourceError) as error:
                failures.append(f"{lang.code}/{entry_key}: {describe_error(error)}")

        # Delete from memory
        if not failures:
            self.entries = [e for e in self.entries if e.key != entry_key]
        self.apply_filters()
        self.update_status()
        self.notify("\n".join(failures) if failures else f"Deleted: {entry_key}",
                    severity="error" if failures else "information")

    @work()
    async def action_translate_missing(self) -> None:
        """Translate all missing entries."""
        if self.translating or self.load_failed:
            self.notify("Wait for translation or refresh the failed resource read", severity="warning")
            return
        self.translating = True
        translator = None
        progress = self.query_one("#progress", ProgressBar)
        progress.display = True

        try:
            translator = AITranslator(self.config)
            # Collect entries needing translation
            entries_to_translate = [
                e
                for e in self.entries
                if e.has_missing_translations(self.config.get_language_codes())
            ]

            if not entries_to_translate:
                self.notify("No missing translations found!")
                progress.display = False
                return

            self.notify(f"Translating {len(entries_to_translate)} entries...")

            def update_progress(
                lang_code: str, current: int, total: int, message: str
            ) -> None:
                progress.update(progress=(current / total) * 100)
                self.query_one("#status", Static).update(message)

            sources = {entry.key: entry.get_translation(entry.source_code) for entry in entries_to_translate}
            result = await translator.translate_all_missing(
                entries_to_translate,
                self.config.get_language_codes(),
                progress_callback=update_progress,
            )
            for code, values in result.successes.items():
                for key in values:
                    self.automatic_sources[code, key] = sources[key]

            self.discard_confirmed = False
            self.refresh_table()
            self.update_status()
            self.notify(f"Translated {result.count}; failed {len(result.failures)}. Press Save to write changes.")
            for (code, key), error in result.failures.items():
                self.notify(f"{code}/{key}: {describe_error(error)}", severity="error")

        except Exception as e:
            self.notify(f"Translation failed: {describe_error(e)}", severity="error")
        finally:
            progress.display = False
            self.translating = False
            if translator is not None:
                await translator.close()

    def action_save_all(self) -> None:
        """Save all changes."""
        if self.translating or self.load_failed:
            self.notify("Wait for translation or refresh the failed resource read", severity="warning")
            return
        failures = []
        source_code = self.config.get_source_language().code
        # Publish edited source drafts before checking translations derived from those drafts.
        for lang in sorted(self.config.languages, key=lambda item: item.code != source_code):
            translations = self.dirty_values(lang.code)
            if translations:
                try:
                    source_values = {key: self.automatic_sources[lang.code, key] for key in translations
                                     if (lang.code, key) in self.automatic_sources}
                    StringsXmlParser.update_entries(self.file_path(lang.code), translations,
                        expected={key: self.baselines[lang.code].get(key) for key in translations},
                        translation_source=(self.file_path(source_code), source_values)
                        if source_values else None)
                    self.baselines[lang.code].update(translations)
                    for key in translations:
                        self.automatic_sources.pop((lang.code, key), None)
                except (OSError, ResourceError) as error:
                    failures.append(f"{lang.code}: {describe_error(error)}")
        self.discard_confirmed = False
        self.update_status()
        self.notify("\n".join(failures) if failures else "All changes saved!",
                    severity="error" if failures else "information")

    def action_refresh(self) -> None:
        """Refresh data."""
        if self.translating:
            self.notify("Wait for translation before refreshing", severity="warning")
            return
        self.load_entries()
        if not self.load_failed:
            self.notify("Data refreshed; existing drafts preserved")
