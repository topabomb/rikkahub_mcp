package net.weero.measix.pilot.data.enterprise

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.storageKey
import kotlin.uuid.Uuid

@Serializable
internal enum class EnterpriseUpdateStatus { DRAFT, PUBLISHED, WITHDRAWN }
@Serializable
internal enum class EnterpriseUpdateFormat { PLAIN, MARKDOWN }
@Serializable
internal enum class EnterpriseUpdateCategory { NOTICE, ANNOUNCEMENT, MAINTENANCE }
@Serializable
internal enum class EnterpriseUpdateSeverity { INFO, WARNING, CRITICAL }

@Serializable
internal data class EnterpriseUpdateContent(
    val title: String,
    val content: String,
    val contentFormat: EnterpriseUpdateFormat,
    val category: EnterpriseUpdateCategory,
    val severity: EnterpriseUpdateSeverity,
)

@Serializable
internal data class EnterpriseFeedEntry(
    val enterpriseUpdateId: String,
    val content: EnterpriseUpdateContent,
    val status: EnterpriseUpdateStatus,
    val publishedAt: String? = null,
)

@Serializable
internal data class EnterpriseFeedSeed(val enterpriseTimezone: String, val items: List<EnterpriseFeedEntry>)

/** Feed content has its own revision and never participates in managed configuration equality. */
@Serializable
internal data class EnterpriseFeedDocument(
    val publicRevision: Long,
    val enterpriseTimezone: String,
    val items: List<EnterpriseFeedEntry>,
)

@Serializable
internal data class EnterpriseUpdateItem(
    val enterpriseUpdateId: String,
    val title: String,
    val content: String,
    val contentFormat: EnterpriseUpdateFormat,
    val category: EnterpriseUpdateCategory,
    val severity: EnterpriseUpdateSeverity,
    val publishedAt: String,
)

@Serializable
internal data class EnterpriseUpdateFeed(
    val enterpriseTimezone: String,
    val items: List<EnterpriseUpdateItem>,
    val truncated: Boolean,
)

internal data class EnterpriseFeedQuery(val startDate: String? = null, val endDate: String? = null, val limit: Int = 10)
internal data class EnterpriseFeedResult(val body: EnterpriseUpdateFeed, val etag: String)

internal sealed interface EnterpriseFeedCommand {
    data class CreateDraft(val content: EnterpriseUpdateContent) : EnterpriseFeedCommand
    data class UpdateDraft(val id: String, val content: EnterpriseUpdateContent) : EnterpriseFeedCommand
    data class Publish(val id: String) : EnterpriseFeedCommand
    data class Withdraw(val id: String) : EnterpriseFeedCommand
}

internal class EnterpriseFeedException(val reason: String) : IllegalArgumentException(reason)

/** Pure Feed rules; the Session owner serializes and publishes their result through its manifest. */
internal object EnterpriseFeed {
    private val idPattern = Regex("eup_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val timestampPattern = Regex("\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d{1,9})?Z")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun initialize(seed: EnterpriseFeedSeed): EnterpriseFeedDocument = EnterpriseFeedDocument(
        if (seed.items.any { it.status != EnterpriseUpdateStatus.DRAFT }) 1 else 0,
        seed.enterpriseTimezone, seed.items,
    ).also(::validate)

    fun validate(document: EnterpriseFeedDocument) {
        check(document.publicRevision >= 0)
        check(document.enterpriseTimezone in ZoneId.getAvailableZoneIds())
        try { ZoneId.of(document.enterpriseTimezone) } catch (_: Exception) { fail("invalid_enterprise_feed") }
        check(document.items.map { it.enterpriseUpdateId }.distinct().size == document.items.size)
        document.items.forEach { entry ->
            check(idPattern.matches(entry.enterpriseUpdateId))
            validateContent(entry.content)
            if (entry.status != EnterpriseUpdateStatus.DRAFT) check(entry.publishedAt != null)
            entry.publishedAt?.let(::instant)
        }
    }

    fun change(document: EnterpriseFeedDocument, command: EnterpriseFeedCommand, now: Instant): EnterpriseFeedDocument {
        validate(document)
        if (command is EnterpriseFeedCommand.CreateDraft) {
            validateContent(command.content)
            return document.copy(items = document.items + EnterpriseFeedEntry(
                "eup_${Uuid.random()}", command.content, EnterpriseUpdateStatus.DRAFT,
            ))
        }
        val id = when (command) {
            is EnterpriseFeedCommand.UpdateDraft -> command.id
            is EnterpriseFeedCommand.Publish -> command.id
            is EnterpriseFeedCommand.Withdraw -> command.id
            else -> error("unreachable")
        }
        val previous = document.items.find { it.enterpriseUpdateId == id } ?: fail("enterprise_update_not_found")
        val next = when (command) {
            is EnterpriseFeedCommand.UpdateDraft -> {
                requireStatus(previous, EnterpriseUpdateStatus.DRAFT)
                validateContent(command.content)
                previous.copy(content = command.content)
            }
            is EnterpriseFeedCommand.Publish -> {
                requireStatus(previous, EnterpriseUpdateStatus.DRAFT)
                previous.copy(status = EnterpriseUpdateStatus.PUBLISHED, publishedAt = now.toString())
            }
            is EnterpriseFeedCommand.Withdraw -> {
                requireStatus(previous, EnterpriseUpdateStatus.PUBLISHED)
                previous.copy(status = EnterpriseUpdateStatus.WITHDRAWN)
            }
            else -> error("unreachable")
        }
        val publicChange = command !is EnterpriseFeedCommand.UpdateDraft
        if (publicChange && document.publicRevision == Long.MAX_VALUE) fail("enterprise_feed_revision_exhausted")
        return document.copy(
            publicRevision = document.publicRevision + if (publicChange) 1 else 0,
            items = document.items.map { if (it.enterpriseUpdateId == id) next else it },
        ).also(::validate)
    }

    fun list(document: EnterpriseFeedDocument, scope: ConfigurationScope.Enterprise, query: EnterpriseFeedQuery, now: Instant): EnterpriseFeedResult {
        validate(document)
        if (query.limit !in 1..20) fail("invalid_request")
        val zone = ZoneId.of(document.enterpriseTimezone)
        val start = query.startDate?.let(::date)
        val end = query.endDate?.let(::date) ?: start?.let { now.atZone(zone).toLocalDate() }
        if (start != null && end != null && start > end) fail("invalid_request")
        val from = start?.atStartOfDay(zone)?.toInstant()
        val until = end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
        val matches = document.items.asSequence().filter { it.status == EnterpriseUpdateStatus.PUBLISHED }
            .map { it to instant(requireNotNull(it.publishedAt)) }
            .filter { (_, time) -> (from == null || time >= from) && (until == null || time < until) }
            .sortedWith(compareByDescending<Pair<EnterpriseFeedEntry, Instant>> { it.second }.thenBy { it.first.enterpriseUpdateId })
            .take(query.limit + 1).map { it.first.publicItem() }.toList()
        val body = EnterpriseUpdateFeed(document.enterpriseTimezone, matches.take(query.limit), matches.size > query.limit)
        val representation = listOf(scope.storageKey(), document.publicRevision.toString(), start?.toString(), end?.toString(),
            query.limit.toString(), EnterprisePackageCodec.json.encodeToString(body))
        val bytes = EnterprisePackageCodec.json.encodeToString(representation).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return EnterpriseFeedResult(body, "\"$digest\"")
    }

    fun detail(document: EnterpriseFeedDocument, id: String): EnterpriseUpdateItem {
        validate(document)
        return document.items.find { it.enterpriseUpdateId == id && it.status == EnterpriseUpdateStatus.PUBLISHED }
            ?.publicItem() ?: fail("enterprise_update_not_found")
    }

    private fun EnterpriseFeedEntry.publicItem() = EnterpriseUpdateItem(
        enterpriseUpdateId, content.title, content.content, content.contentFormat, content.category, content.severity,
        requireNotNull(publishedAt),
    )

    private fun validateContent(content: EnterpriseUpdateContent) { check(content.title.isNotBlank() && content.content.isNotBlank()) }
    private fun requireStatus(entry: EnterpriseFeedEntry, status: EnterpriseUpdateStatus) {
        if (entry.status != status) fail("invalid_enterprise_update_transition")
    }
    private fun date(value: String): LocalDate {
        if (!datePattern.matches(value)) fail("invalid_request")
        return try { LocalDate.parse(value) } catch (_: Exception) { fail("invalid_request") }
    }
    private fun instant(value: String): Instant {
        check(timestampPattern.matches(value))
        return try { Instant.parse(value) } catch (_: Exception) { fail("invalid_enterprise_feed") }
    }
    private fun check(value: Boolean) { if (!value) fail("invalid_enterprise_feed") }
    private fun fail(reason: String): Nothing = throw EnterpriseFeedException(reason)
}
