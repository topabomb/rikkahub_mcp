package net.weero.measix.pilot.service.runtime

import me.rerere.common.configuration.ConfigurationReference

import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextPayload
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService

/**
 * runtime / 命令测试共享的最小 canonical candidate：与真实 START 捕获同源，直接由
 * [ConversationDisclosureSnapshotService] 渲染，保证测试携带的永远是合法 envelope。
 *
 * [memoryContents] 决定 memory section 行，因此是唯一影响 content bytes 的入参；相同
 * memoryContents 渲染出逐字相同的 candidate（baseline 判等测试据此构造"变/不变"）。
 */
internal fun disclosureCandidate(
    memoryContents: List<String> = emptyList(),
    seed: Long = 1L,
): String {
    val assistant = Assistant(
        id = ConfigurationReference.parse("dddd0000-0000-0000-0000-00000000%04x".format(seed)),
        name = "Disclosure Test Assistant",
        localTools = emptyList(),
    )
    return ConversationDisclosureSnapshotService.render(
        ConversationDisclosureSnapshotService.Candidate(
            assistant = assistant,
            allAssistants = listOf(assistant),
            memories = memoryContents.mapIndexed { index, content -> AssistantMemory(index + 1, content) },
        ),
    )
}

/** 每个 seed 渲染出逐字稳定、彼此可区分的 candidate；baseline 判等用例据此构造"变/不变"。 */
internal fun stableCandidate(seed: Long): String =
    disclosureCandidate(memoryContents = listOf("baseline-$seed"))

/** Historical bytes are wrapped as provenance, never upgraded to the current wire format. */
internal fun disclosurePayload(content: String): ConversationContextPayload = ConversationContextPayload(
    source = ConversationContextSource.Disclosure(namespace = null),
    body = ConversationContextBody.Inline(content),
)

internal fun net.weero.measix.pilot.data.model.ConversationModelContextEntry.inlineContextText(): String =
    (payload.body as ConversationContextBody.Inline).text

internal fun net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity.inlineContextText(): String =
    (net.weero.measix.pilot.data.model.ConversationContextCodec.decode(payload).body as ConversationContextBody.Inline).text

internal fun historicalContextRow(
    ownerNodeId: String,
    ownerMessageId: String,
    anchorNodeId: String,
    anchorMessageId: String,
    content: String,
): net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity =
    net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity(
        id = java.util.UUID.nameUUIDFromBytes("conversation-context/$ownerNodeId/$ownerMessageId/0".encodeToByteArray()).toString(),
        ownerNodeId = ownerNodeId,
        ownerMessageId = ownerMessageId,
        anchorNodeId = anchorNodeId,
        anchorMessageId = anchorMessageId,
        occurrence = 0,
        stepId = null,
        sourceKind = "disclosure",
        payload = net.weero.measix.pilot.data.model.ConversationContextCodec.encode(disclosurePayload(content)),
    )
