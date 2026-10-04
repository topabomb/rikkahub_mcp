package net.weero.measix.pilot.data.enterprise

import net.weero.measix.pilot.data.configuration.EnterprisePolicy

internal fun starterDeviceCandidate(historical: Boolean): EnterpriseCandidate {
    val discovery = PlatformDiscovery(PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", id("dep"),
        "设备测试企业", "/api/client/v1", "/runtime/v1", listOf(if (historical) 4L else 5L))
    val connection = PlatformConnection("https://mock-enterprise.invalid", discovery)
    val identity = EnterpriseIdentity(connection.authority, discovery.deploymentName, id("usr"), "设备测试用户")
    val opening = if (historical) null else EnterpriseStarterOpeningSnapshot(1, "  {{user}} 企业指令\n", listOf(
        EnterpriseStarterInitialContext("second", "  {{literal}}\n"),
        EnterpriseStarterInitialContext("first", ""),
    ))
    val configuration = EnterpriseConfiguration(
        generation = 1,
        policy = EnterprisePolicy(false, false, false, false, false),
        providers = listOf(EnterpriseProvider(id("prv"), "Mock", PlatformProviderDefinitionClientProtocol.OPENAI_CHAT_COMPLETIONS, true)),
        models = listOf(EnterpriseModel(id("mdl"), "Mock", "mock-model", providerId = id("prv"))),
        tts = emptyList(), asr = emptyList(), mcpServers = emptyList(), gateways = emptyList(), memorySeeds = emptyList(),
        assistants = listOf(EnterpriseAssistant(id("asd"), "设备测试助手", "说明", id("mdl"), "助手指令", emptyList(), emptyList())),
        starters = listOf(EnterpriseStarter(id("str"), id("asd"), "设备测试开场", " 原始提示词 ", 7, true, opening)),
        defaults = EnterpriseDefaults(assistantId = id("asd"), chatModelId = id("mdl")),
    )
    return EnterpriseCandidate(identity, configuration, EnterpriseExecution.Platform(
        connection, id("rel"), "sha256:" + "a".repeat(64), mapOf(id("mdl") to "/v1/chat/completions"),
        snapshotSchemaVersion = if (historical) 4L else 5L,
    ))
}

private fun id(prefix: String) = "${prefix}_12345678-1234-4234-8234-123456789012"
