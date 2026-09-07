package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn / Step / Tool 执行协议的结构性契约：采样循环、滚动压缩、tool 生命周期与终态提交各自的
 * 唯一归属与推进顺序；这些不变量决定 durable 状态机正确性，故以源码形态锁定，防止被重新耦合回循环或旁路。
 */
class TurnStepProtocolContractTest {
    @Test
    fun `tool runtime context planner and archived output have single owners`() {
        val turnRunner = File(architectureSourceRoot, "service/turn/TurnRunner.kt").readText()
        val stepRunner = File(architectureSourceRoot, "service/turn/StepRunner.kt").readText()
        val toolBatchRunner = File(architectureSourceRoot, "service/turn/ToolBatchRunner.kt").readText()
        val turnRunState = File(architectureSourceRoot, "service/turn/TurnRunState.kt").readText()
        val toolRuntime = File(architectureSourceRoot, "data/ai/tools/ToolCallRuntime.kt")
        val planner = File(architectureSourceRoot, "data/ai/request/RequestContextPlanner.kt")
        val outputStore = File(architectureSourceRoot, "data/ai/tools/ToolOutputStore.kt")
        assertTrue(toolRuntime.isFile)
        assertTrue(planner.isFile)
        assertTrue(outputStore.isFile)
        // 旧的截断/输出机制不得在拆分后的任一循环文件中复活
        listOf(turnRunner, stepRunner, toolBatchRunner, turnRunState).forEach { loopFile ->
            listOf(
                "MAX_TOOL_OUTPUT_CHARS",
                "TOOL_OUTPUT_PREVIEW_CHARS",
                "maybeTruncateToolOutput",
                "legacyTruncateOutput",
                "/tool_outputs/",
                "limitContext(",
            ).forEach { legacy -> assertFalse("loop file must not retain $legacy", loopFile.contains(legacy)) }
        }
        // 采样后的滚动压缩规划/暂存/checkpoint 应用单一归属 StepRunner
        assertTrue(stepRunner.contains("contextPlanner.planRequest"))
        assertTrue(stepRunner.contains("compactionPlanner.planAfterSuccessfulRequest"))
        assertTrue(
            "no-tool Final step stages the terminal write instead of a standalone ModelResponseCheckpoint",
            stepRunner.contains("stageTerminalModelOutput"),
        )
        assertTrue(
            "sampling checkpoint must prepare the tool batch before commit so pending rides AWAITING_USER",
            stepRunner.contains("toolCallRuntime.prepareBatch"),
        )
        assertTrue(stepRunner.contains("TurnExecutionStatus.AWAITING_USER"))
        assertFalse(
            "compaction must not be duplicated back into the loop",
            turnRunner.contains("compactionPlanner.planAfterSuccessfulRequest("),
        )
        assertFalse(File(architectureSourceRoot, "MeasixPilotApp.kt").readText().contains("cleanupToolOutputs"))
        listOf("baseline-prof.txt", "startup-prof.txt").forEach { profileName ->
            val profile = File("src/release/generated/baselineProfiles", profileName)
            assertTrue(profile.isFile)
            assertFalse(profile.readText().contains("cleanupToolOutputs"))
        }
        assertFalse(
            File("../workspace/src/main/java/me/rerere/workspace/ProotLaunchSpec.kt")
                .readText().contains("TOOL_OUTPUTS_DIR")
        )
        val ui = architectureSources.filter { it.relativeTo(architectureSourceRoot).invariantSeparatorsPath.startsWith("ui/") }
        assertNoHits("import net.weero.measix.pilot.data.files.ArtifactStore", ui)
        assertNoHits("import net.weero.measix.pilot.data.ai.tools.ToolOutputStore", ui)
    }

    @Test
    fun `tool output ownership transfer is mandatory`() {
        assertTrue(hits("registerUnpublishedResource").isNotEmpty())
        assertTrue(hits("ToolResourceLease").isNotEmpty())
        assertNoHits("ToolArtifactRewriter?")
    }

    @Test
    fun `tool UI uses the typed lifecycle and never gates inspection on output`() {
        // Boundary only: the tool UI drives off the typed ToolLivePhase, never off Provider replay
        // results or the retired approvalState, and notifications use typed events rather than
        // inferring execution from output. Concrete Compose rendering and inline notification logic
        // are runtime behavior owned by the UI/behavior tests, not locked here as source snippets.
        val toolUi = architectureSources.filter {
            it.relativeTo(architectureSourceRoot).invariantSeparatorsPath.startsWith("ui/components/message/")
        }
        assertNoHits("loading && !step.tool.hasReplayResult", toolUi)
        assertNoHits("val loading: Boolean", toolUi)
        assertTrue(hits("ToolLivePhase", toolUi).isNotEmpty())
        val chatMessageTools = architectureSourceRoot.resolve("ui/components/message/ChatMessageTools.kt").readText()
        assertFalse(chatMessageTools.contains("val isPending = tool.approvalState"))
        val notificationManager = architectureSourceRoot.resolve("service/ChatNotificationManager.kt").readText()
        assertFalse(
            "notifications must not infer execution from Provider replay results",
            notificationManager.contains("!lastTool.hasReplayResult"),
        )
        assertTrue(notificationManager.contains("executingToolLocalCallId"))
        assertTrue(notificationManager.contains("committedToolPhaseChanged"))
        assertTrue(notificationManager.contains("ChatGenerationAwaitingUser"))
        val turnServiceSource = architectureSourceRoot.resolve("service/ConversationTurnService.kt").readText()
        listOf("ChatGenerationAwaitingUser", "ChatGenerationEnded").forEach { event ->
            assertTrue(
                "$event must use suspending delivery",
                Regex("""appEventBus\.emit\(\s*AppEvent\.$event\b""").containsMatchIn(turnServiceSource),
            )
        }
    }

    @Test
    fun `turn context is prepared before START and materialized after it`() {
        val factory = File(architectureSourceRoot, "service/turn/TurnContextFactory.kt").readText()
        // prepareLaunch 是唯一允许 IO 的捕获；create/capturePromptInputs 已删除，materialize 只做纯绑定。
        assertTrue(factory.contains("suspend fun prepareLaunch"))
        assertTrue(factory.contains("fun materialize(plan: TurnLaunchPlan)"))
        assertFalse(factory.contains("capturePromptInputs"))
        assertFalse(factory.contains("internal fun create("))

        // ActiveTurnSession 只暴露必需的 turnContext；nullable requestContext 词汇已清除。
        val runtime = File(architectureSourceRoot, "service/runtime/ConversationRuntime.kt").readText()
        assertFalse(runtime.contains("requestContext"))
        assertFalse(runtime.contains("bindRequestContext"))
        assertTrue(runtime.contains("val turnContext: TurnContext"))

        listOf("service/ConversationTurnService.kt", "service/subassistant/SubAssistantRunCoordinator.kt").forEach { path ->
            val owner = File(architectureSourceRoot, path).readText()
            val prepare = owner.indexOf("turnContextFactory.prepareLaunch(")
            val start = owner.indexOf("TurnCommitter.start(")
            val materialize = owner.indexOf("turnContextFactory.materialize(")
            val bind = owner.indexOf("runtime.bindTurnContext(")
            assertTrue("$path must prepareLaunch before StartTurn", prepare in 0 until start)
            assertTrue("$path must materialize after StartTurn", start < materialize)
            assertTrue("$path must bind after StartTurn", start < bind)
            assertTrue("$path must finalize materialize failure", owner.contains("TurnTerminalReasons.TURN_CONTEXT_MATERIALIZE"))
        }
    }

    @Test
    fun `turn live phases are granular and wired to the turn service session`() {
        val presentation = File(architectureSourceRoot, "service/runtime/ConversationPresentation.kt").readText()
        listOf("MODEL_WAITING", "TOOL_PREPARING", "TOOL_EXECUTING").forEach {
            assertTrue("TurnLivePhase must declare $it", presentation.contains("$it,"))
        }
        assertFalse("live phase must not collapse back to GENERATING", presentation.contains("GENERATING,"))
        assertTrue(presentation.contains("fun turnLivePhaseOf(phase: String)"))

        val turnServiceSource = architectureSourceRoot.resolve("service/ConversationTurnService.kt").readText()
        assertTrue("turn service must map loop phases", turnServiceSource.contains("turnLivePhaseOf(phase)"))
        assertTrue("turn service must advance the session live phase", turnServiceSource.contains("runtime.livePhaseReporter()"))

        val stepRunner = File(architectureSourceRoot, "service/turn/StepRunner.kt").readText()
        assertTrue("tool batch preparation must emit TOOL_PREPARING", stepRunner.contains("sendPhase(\"tool_preparing\")"))
    }

    @Test
    fun `conversation UI consumes typed turn presentation instead of coroutine jobs`() {
        val chatUi = architectureSources.filter {
            it.relativeTo(architectureSourceRoot).invariantSeparatorsPath.startsWith("ui/pages/chat/")
        }
        assertNoHits("conversationJob", chatUi)
        assertNoHits("loadingJob", chatUi)
        assertTrue(hits("ConversationPresentation", chatUi).isNotEmpty())
    }

    @Test
    fun `attachment backfill is an exact metadata patch rather than a replacement tree`() {
        assertTrue(hits("BackfillAttachmentRefs").isNotEmpty())
        assertTrue(hits("AttachmentRefBackfill").isNotEmpty())
    }

    @Test
    fun `durable recovery and lifecycle domains never consume render overlays`() {
        listOf(
            "service/turn/TurnRecovery.kt",
            "service/turn/TurnFinalizer.kt",
            "service/subassistant/SubAssistantLifecycle.kt",
        ).forEach { path ->
            assertFalse(
                "$path must mutate durable nodes, not renderNodes",
                File(architectureSourceRoot, path).readText().contains("renderNodes"),
            )
        }
    }

    @Test
    fun `generation foreground service uses the fgsType timeout signature`() {
        val service = File(architectureSourceRoot, "service/ChatGenerationForegroundService.kt").readText()
        assertTrue(service.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        assertFalse(service.contains("override fun onTimeout(startId: Int)"))
    }
}
