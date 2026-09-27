package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.ai.transformers.renderPromptPlaceholders
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.model.SystemContextKind

/** Presentation reads the saved source and body only; it never resolves today's configuration. */
internal fun projectConversationContextContent(
    source: ConversationContextSource,
    text: String,
    opening: EnterpriseStarterOpeningSnapshot? = null,
): ConversationContextPresentationUiModel = when (source) {
    is ConversationContextSource.Disclosure -> projectDisclosure(source, text)
    is ConversationContextSource.System -> ConversationContextPresentationUiModel(source.contributions.map { contribution ->
        ConversationContextSectionUiModel(
            category = ConversationContextCategory.SYSTEM,
            title = contribution.name.takeIf { contribution.kind == SystemContextKind.TOOL || contribution.kind == SystemContextKind.PROMPT_RULE },
            text = renderPromptPlaceholders(contribution.template, contribution.variables),
            systemOwner = when (contribution.kind) {
                SystemContextKind.DOMAIN -> when (contribution.name) {
                    "conversation_override" -> ConversationContextSystemOwner.CONVERSATION
                    "enterprise_opening" -> ConversationContextSystemOwner.ENTERPRISE_OPENING
                    else -> ConversationContextSystemOwner.DOMAIN
                }
                SystemContextKind.APPLICATION -> if (contribution.name == "disclosure_interpretation")
                    ConversationContextSystemOwner.CONTEXT_SYNC else ConversationContextSystemOwner.APPLICATION
                SystemContextKind.TOOL -> ConversationContextSystemOwner.TOOL
                SystemContextKind.WORKSPACE -> ConversationContextSystemOwner.WORKSPACE
                SystemContextKind.PROMPT_RULE -> ConversationContextSystemOwner.PROMPT_RULE
            },
        )
    })
    ConversationContextSource.Starter -> projectStarterContext(requireNotNull(opening), includeSystem = false)
    else -> ConversationContextPresentationUiModel(listOf(ConversationContextSectionUiModel(
        category = when (source) {
            is ConversationContextSource.PromptRule -> ConversationContextCategory.PROMPT_RULE
            is ConversationContextSource.MessageTime -> ConversationContextCategory.TIME
            is ConversationContextSource.Attachment -> ConversationContextCategory.ATTACHMENT
            is ConversationContextSource.Preset -> ConversationContextCategory.PRESET
            is ConversationContextSource.HistorySummary -> ConversationContextCategory.HISTORY_SUMMARY
            else -> error("unsupported_context_source")
        },
        title = when (source) {
            is ConversationContextSource.PromptRule -> source.definition.name
            is ConversationContextSource.Attachment -> source.name
            else -> null
        },
        text = text,
    )))
}

internal fun projectStarterContext(
    opening: EnterpriseStarterOpeningSnapshot,
    includeSystem: Boolean = true,
): ConversationContextPresentationUiModel = ConversationContextPresentationUiModel(buildList {
    if (includeSystem) add(ConversationContextSectionUiModel(
        category = ConversationContextCategory.SYSTEM,
        scope = ConversationContextScope.READ_ONLY,
        text = opening.systemPrompt,
        systemOwner = ConversationContextSystemOwner.ENTERPRISE_OPENING,
    ))
    opening.initialContexts.forEach { block -> add(ConversationContextSectionUiModel(
        category = ConversationContextCategory.ENTERPRISE_BACKGROUND,
        title = block.title,
        scope = ConversationContextScope.READ_ONLY,
        text = block.content,
    )) }
})

private fun projectDisclosure(source: ConversationContextSource.Disclosure, text: String): ConversationContextPresentationUiModel =
    ConversationContextPresentationUiModel(ConversationDisclosureSnapshotService.readSections(text).map { (section, value) ->
        val state = value.getValue("rows").jsonArray.map { it.jsonArray }
        val byId = state.associateBy { it[0].jsonPrimitive.content }
        val reason = when (source.reasons[section]) {
            ContextAdmissionReason.INITIAL -> ConversationContextReason.INITIAL
            ContextAdmissionReason.EXTERNAL_STATE -> ConversationContextReason.EXTERNAL
            ContextAdmissionReason.BASELINE_RESTORE -> ConversationContextReason.RESTORE
            null -> ConversationContextReason.HISTORICAL
        }
        val change = source.changes?.get(section)
        fun row(value: JsonArray, before: JsonArray? = null, kind: ConversationContextChangeKind? = null): ConversationContextRowUiModel {
            fun content(value: JsonArray, includeName: Boolean = false): String =
                value.drop(if (section == DisclosureSection.SUB_ASSISTANTS && !includeName) 2 else 1)
                    .joinToString("\n") { it.jsonPrimitive.content }
            return ConversationContextRowUiModel(
                id = value[0].jsonPrimitive.content,
                title = if (section == DisclosureSection.SUB_ASSISTANTS) value[1].jsonPrimitive.content else null,
                before = before?.let { content(it, includeName = section == DisclosureSection.SUB_ASSISTANTS && it[1] != value[1]) },
                after = if (kind == ConversationContextChangeKind.REMOVED) null else content(value),
                change = kind,
            )
        }
        val rows = if (change == null) state.map { row(it) } else buildList {
            change.addedIds.forEach { id -> add(row(byId.getValue(id), kind = ConversationContextChangeKind.ADDED)) }
            change.updatedBefore.forEach { before -> add(row(byId.getValue(before[0].jsonPrimitive.content), before,
                ConversationContextChangeKind.MODIFIED)) }
            change.removedBefore.forEach { before -> add(row(before, before, ConversationContextChangeKind.REMOVED)) }
        }
        val orderedSurvivors = change?.orderBefore.orEmpty().filter { it in byId }
        val orderIds = orderedSurvivors.toSet()
        val updatedBefore = change?.updatedBefore.orEmpty().associateBy { it[0].jsonPrimitive.content }
        ConversationContextSectionUiModel(
            category = section.contextCategory(),
            scope = when (section) {
                DisclosureSection.MEMORY -> when (value["scope"]?.jsonPrimitive?.content) {
                    "global" -> ConversationContextScope.SHARED
                    "local" -> ConversationContextScope.ASSISTANT
                    "disabled" -> ConversationContextScope.DISABLED
                    else -> ConversationContextScope.UNKNOWN
                }
                DisclosureSection.SUB_ASSISTANTS -> ConversationContextScope.ASSISTANT
                DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> ConversationContextScope.READ_ONLY
            },
            reason = reason,
            rows = rows,
            exactChanges = change != null,
            attributes = if (change != null) change.attributesBefore.map { (name, before) ->
                ConversationContextAttributeUiModel(name, before.content, (value.getValue(name) as JsonPrimitive).content)
            } else listOf("enabled", "scope", "mode").mapNotNull { name ->
                (value[name] as? JsonPrimitive)?.let { ConversationContextAttributeUiModel(name, null, it.content) }
            },
            orderBefore = orderedSurvivors.map { id -> row(updatedBefore[id] ?: byId.getValue(id)) },
            orderAfter = state.filter { it[0].jsonPrimitive.content in orderIds }.map { row(it) },
        )
    })
