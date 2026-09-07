package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertTrue

/**
 * 架构契约测试共享的源码扫描器：把 `app` 生产源码树按 token 检索，供依赖方向、
 * 已退休符号与 Turn/Step 协议三族契约测试复用，避免每个测试类各自复制遍历逻辑。
 */
internal val architectureSourceRoot = File("src/main/java/net/weero/measix/pilot")

internal val architectureSources: List<File> by lazy {
    architectureSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}

/** 生产源码树中位于 `relativePrefix`（相对 pilot 包，`/` 分隔）下的 Kotlin 文件。 */
internal fun sourcesUnder(relativePrefix: String): List<File> = architectureSources.filter {
    it.relativeTo(architectureSourceRoot).invariantSeparatorsPath.startsWith(relativePrefix)
}

internal fun hits(token: String, files: List<File> = architectureSources): List<String> = files
    .filter { it.readText().contains(token) }
    .map { it.relativeTo(architectureSourceRoot).invariantSeparatorsPath }

internal fun assertNoHits(token: String, files: List<File> = architectureSources) {
    val violations = hits(token, files)
    assertTrue("forbidden architecture token '$token': $violations", violations.isEmpty())
}
