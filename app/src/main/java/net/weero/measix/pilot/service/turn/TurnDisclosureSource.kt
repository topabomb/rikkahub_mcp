package net.weero.measix.pilot.service.turn

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.DisclosureNamespace
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService

/** Captured address and Seed, with authorized current facts sampled only at a new request boundary. */
internal class TurnDisclosureSource(
    val namespace: DisclosureNamespace,
    private val capturedAssistant: Assistant,
    private val seeds: List<Pair<ConfigurationReference.Enterprise, String>>,
    private val readConfiguration: suspend () -> ResolvedConfiguration,
    private val readMemory: suspend () -> List<AssistantMemory>,
    private val validateMemory: suspend () -> Unit,
) {
    fun withInstalledTools(bindings: Map<String, net.weero.measix.pilot.data.ai.tools.ToolExecutionBinding>): TurnDisclosureSource =
        TurnDisclosureSource(namespace, capturedAssistant.copy(localTools = capturedAssistant.localTools.filter { option ->
            when (option) {
                net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantManagement -> bindings.values.any {
                    it.executionIdentity == net.weero.measix.pilot.data.model.DisclosureBuiltinTool.ASSISTANT_MANAGE.executionIdentity
                }
                net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantDelegation -> "assistant_call" in bindings
                else -> true
            }
        }), seeds, readConfiguration, readMemory, validateMemory)

    suspend fun requireReadable() {
        val configuration = readConfiguration()
        requireCaller(configuration)
        validateMemory()
    }

    private fun requireCaller(configuration: ResolvedConfiguration): Assistant {
        val current = requireNotNull(configuration.assistants[namespace.caller]) {
            "disclosure_caller_unavailable"
        }
        check(configuration.access(ConfigurationCategory.ASSISTANT, current.id).canExecute) {
            "disclosure_caller_not_allowed"
        }
        return current
    }

    suspend fun read(): String {
        val configuration = readConfiguration()
        val current = requireCaller(configuration)
        // Directory policy is live. Native capabilities can only shrink within this Turn.
        val caller = current.copy(
            localTools = current.localTools.filter { it in capturedAssistant.localTools },
            enableMemory = capturedAssistant.enableMemory,
            useGlobalMemory = capturedAssistant.useGlobalMemory,
        )
        val memories = readMemory()
        return ConversationDisclosureSnapshotService.render(ConversationDisclosureSnapshotService.Candidate(
            assistant = caller,
            allAssistants = configuration.assistants.values.filter {
                configuration.access(ConfigurationCategory.ASSISTANT, it.id).canExecute
            },
            memories = memories,
            enterpriseMemorySeeds = seeds,
        ))
    }

    companion object {
        fun capture(
            configuration: ResolvedConfiguration,
            assistant: Assistant,
            namespace: DisclosureNamespace,
            readConfiguration: suspend () -> ResolvedConfiguration,
            readMemory: suspend () -> List<AssistantMemory>,
            validateMemory: suspend () -> Unit = {},
        ): TurnDisclosureSource = TurnDisclosureSource(
            namespace = namespace,
            capturedAssistant = assistant,
            seeds = configuration.assistantMemorySeeds(assistant.id).map {
                requireNotNull(configuration.enterpriseIdentity).reference(it.id) to it.content
            },
            readConfiguration = readConfiguration,
            readMemory = readMemory,
            validateMemory = validateMemory,
        )
    }
}
