package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import kotlin.uuid.Uuid

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
        id = Uuid.parse("dddd0000-0000-0000-0000-00000000%04x".format(seed)),
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
