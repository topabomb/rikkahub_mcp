package net.weero.measix.pilot.data.enterprise

import java.time.Instant
import kotlinx.serialization.json.*
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.*
import org.junit.Test

class EnterpriseFeedTest {
    private val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "example"), "user")
    private val now = Instant.parse("2026-09-07T12:00:00Z")
    private val content = EnterpriseUpdateContent("Title", "Content", EnterpriseUpdateFormat.PLAIN,
        EnterpriseUpdateCategory.NOTICE, EnterpriseUpdateSeverity.INFO)

    @Test
    fun `actual date query consumes shared raw vectors`() {
        val bytes = resource("feed-vectors.json")
        val suites = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("suites").jsonArray
        suites.forEach { rawSuite ->
            val suite = rawSuite.jsonObject
            val clock = Instant.parse(suite.getValue("now").jsonPrimitive.content)
            val items = suite.getValue("items").jsonArray.mapIndexed { index, raw ->
                val item = raw.jsonObject
                EnterpriseFeedEntry("eup_00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    content.copy(title = item.getValue("key").jsonPrimitive.content),
                    EnterpriseUpdateStatus.valueOf(item.getValue("status").jsonPrimitive.content),
                    item.getValue("publishedAt").jsonPrimitive.content)
            }
            val document = EnterpriseFeed.initialize(EnterpriseFeedSeed(suite.getValue("timezone").jsonPrimitive.content, items))
            suite.getValue("queries").jsonArray.forEach { raw ->
                val query = raw.jsonObject
                val request = EnterpriseFeedQuery(query["startDate"]?.jsonPrimitive?.content,
                    query["endDate"]?.jsonPrimitive?.content, query["limit"]?.jsonPrimitive?.int ?: 10)
                val name = "${suite.getValue("name")}/${query.getValue("name") }"
                if ("error" in query) {
                    val failure = assertThrows(name, EnterpriseFeedException::class.java) { EnterpriseFeed.list(document, scope, request, clock) }
                    assertEquals(name, "invalid_request", failure.reason)
                } else {
                    val result = EnterpriseFeed.list(document, scope, request, clock)
                    assertEquals(name, query.getValue("expected").jsonArray.map { it.jsonPrimitive.content }, result.body.items.map { it.title })
                    assertEquals(name, query.getValue("truncated").jsonPrimitive.boolean, result.body.truncated)
                    assertEquals(name, result, EnterpriseFeed.list(document, scope, request, clock))
                }
            }
        }
    }

    @Test
    fun `draft edits keep public ETag and publication withdrawal own independent revision`() {
        val empty = EnterpriseFeed.initialize(EnterpriseFeedSeed("UTC", emptyList()))
        val created = EnterpriseFeed.change(empty, EnterpriseFeedCommand.CreateDraft(content), now)
        val id = created.items.single().enterpriseUpdateId
        val edited = EnterpriseFeed.change(created, EnterpriseFeedCommand.UpdateDraft(id, content.copy(title = "Edited")), now)
        assertEquals(0L, edited.publicRevision)
        assertEquals(EnterpriseFeed.list(empty, scope, EnterpriseFeedQuery(), now).etag,
            EnterpriseFeed.list(edited, scope, EnterpriseFeedQuery(), now).etag)
        val published = EnterpriseFeed.change(edited, EnterpriseFeedCommand.Publish(id), now)
        assertEquals(1L, published.publicRevision)
        assertEquals(now.toString(), EnterpriseFeed.detail(published, id).publishedAt)
        assertThrows(EnterpriseFeedException::class.java) { EnterpriseFeed.change(published, EnterpriseFeedCommand.UpdateDraft(id, content), now) }
        assertThrows(EnterpriseFeedException::class.java) { EnterpriseFeed.change(published, EnterpriseFeedCommand.Publish(id), now) }
        val withdrawn = EnterpriseFeed.change(published, EnterpriseFeedCommand.Withdraw(id), now)
        assertEquals(2L, withdrawn.publicRevision)
        assertThrows(EnterpriseFeedException::class.java) { EnterpriseFeed.detail(withdrawn, id) }
        assertNotEquals(EnterpriseFeed.list(published, scope, EnterpriseFeedQuery(), now).etag,
            EnterpriseFeed.list(withdrawn, scope, EnterpriseFeedQuery(), now).etag)
    }

    @Test
    fun `ETag binds principal normalized dates and enterprise midnight`() {
        val document = EnterpriseFeed.initialize(EnterpriseFeedSeed("Asia/Shanghai", emptyList()))
        val query = EnterpriseFeedQuery(startDate = "2026-09-07")
        val before = Instant.parse("2026-09-07T15:59:59Z")
        val tag = EnterpriseFeed.list(document, scope, query, before).etag
        assertEquals(tag, EnterpriseFeed.list(document, scope, query.copy(endDate = "2026-09-07"), before).etag)
        assertNotEquals(tag, EnterpriseFeed.list(document, scope, query, before.plusSeconds(1)).etag)
        assertNotEquals(tag, EnterpriseFeed.list(document, scope.copy(userId = "other"), query, before).etag)
        listOf(EnterpriseFeedQuery(startDate = "2026-02-30"), EnterpriseFeedQuery(endDate = "2026-9-7"),
            EnterpriseFeedQuery(limit = 0), EnterpriseFeedQuery(limit = 21)).forEach {
            assertThrows(EnterpriseFeedException::class.java) { EnterpriseFeed.list(document, scope, it, now) }
        }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/contracts/portal/$name")).use { it.readBytes() }

    @Test
    fun `full package rejects invalid Feed identity timezone and publication metadata`() {
        val packet = exampleEnterprisePackage()
        val seed = requireNotNull(packet.feedSeed)
        val entry = seed.items.single()
        val invalid = listOf(
            seed.copy(enterpriseTimezone = "+08:00"),
            seed.copy(items = listOf(entry, entry)),
            seed.copy(items = listOf(entry.copy(enterpriseUpdateId = "feed_arbitrary"))),
            seed.copy(items = listOf(entry.copy(publishedAt = null))),
            seed.copy(items = listOf(entry.copy(publishedAt = "2026-09-07T00:00:60Z"))),
            seed.copy(items = listOf(entry.copy(content = entry.content.copy(title = " ")))),
        )
        invalid.forEach {
            val failure = assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.validate(packet.copy(feedSeed = it)) }
            assertEquals("invalid_enterprise_feed", failure.reason)
            assertNull(failure.cause)
        }
    }
}
