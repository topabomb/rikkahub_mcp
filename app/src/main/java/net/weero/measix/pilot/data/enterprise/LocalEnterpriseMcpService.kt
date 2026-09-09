package net.weero.measix.pilot.data.enterprise

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.engine.callContext
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.ai.mcp.LocalMcpRequestDefinition
import net.weero.measix.pilot.data.ai.mcp.McpConnectionDefinition
import net.weero.measix.pilot.data.configuration.storageKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/** Installed service adapter replaces HTTP I/O; SDK, transport, discovery, and execution remain production consumers. */
internal class LocalEnterpriseMcpService(
    private val sources: LocalEnterpriseSource,
    private val sessions: EnterpriseSessionController,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readGuide: suspend (String) -> JsonObject = { query -> buildJsonObject {
        put("query", query)
        put("content", "This is the local enterprise reference service. Enterprise resources use the original Session; personal resources require the corresponding policy permission.")
    } },
) {
    // Local interactions cannot survive process restart; their signing material and device context share that lifetime.
    private val signingKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private val deviceId = "dev_${Uuid.random()}"
    private val json = Json { encodeDefaults = true }

    fun createClient(): HttpClient = HttpClient(object : HttpClientEngineBase("local-enterprise-mcp") {
        override val config = HttpClientEngineConfig()
        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val requestedAt = GMTDate()
            val target = data.attributes.getOrNull(LocalMcpRequestDefinition)
            val result = if (target == null) Response(HttpStatusCode.Unauthorized) else handle(
                target, data.method, data.headers["X-Measix-Managed-Generation"], data.headers["X-Measix-Interaction-Id"],
                (data.body as? OutgoingContent.ByteArrayContent)?.bytes(),
            )
            return HttpResponseData(result.status, requestedAt, headersOf("Content-Type", if (result.status.value == 428) "application/problem+json" else "application/json"),
                HttpProtocolVersion.HTTP_1_1, ByteReadChannel(result.body?.toString().orEmpty().toByteArray()), callContext())
        }
    }) { followRedirects = false }

    private suspend fun handle(
        target: McpConnectionDefinition.Managed,
        method: HttpMethod,
        generationHeader: String?,
        interactionHeader: String?,
        body: ByteArray?,
    ): Response {
        if (!target.access.scope.authority.isLocal || target.binding.protocol != EnterpriseRuntimeProtocol.EXAMPLE ||
            generationHeader != target.version.generation.toString() || interactionHeader != target.interactionId) {
            return Response(HttpStatusCode.Unauthorized)
        }
        val candidate = sources.candidate(target.access.scope)?.packet ?: return Response(HttpStatusCode.ServiceUnavailable)
        val authorized = try {
            sessions.withAppliedConfiguration(target.access) { it.manifest.phase == EnterpriseSessionPhase.READY }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: EnterpriseConfigurationException) { false }
        if (!authorized) return Response(HttpStatusCode.Unauthorized)
        if (candidate.configuration.generation != target.version.generation) return Response(HttpStatusCode(428, "Precondition Required"), buildJsonObject {
            put("type", "about:blank")
            put("title", "Managed snapshot required")
            put("status", 428)
            put("code", "managed_snapshot_required")
            put("targetManagedGeneration", candidate.configuration.generation)
            put("requestId", "req_${Uuid.random()}")
            put("forwarded", false)
        })
        if (candidate.runtimeBindings.none { it.resourceId == target.id.id && it.protocol == EnterpriseRuntimeProtocol.EXAMPLE }) {
            return Response(HttpStatusCode.NotFound)
        }
        val gateway = target.managed.gatewaySurface != null
        if (gateway && candidate.configuration.gateways.none { it.id == target.id.id } ||
            !gateway && candidate.configuration.mcpServers.none { it.id == target.id.id && it.enabled }) return Response(HttpStatusCode.NotFound)
        if (method == HttpMethod.Get) return Response(HttpStatusCode.MethodNotAllowed)
        if (method != HttpMethod.Post || body == null) return Response(HttpStatusCode.BadRequest)
        val rpc = try { json.parseToJsonElement(body.decodeToString()).jsonObject }
        catch (_: IllegalArgumentException) { return Response(HttpStatusCode.BadRequest) }
        val id = rpc["id"]
        val methodName = (rpc["method"] as? JsonPrimitive)?.content
        if (id == null && methodName == "notifications/initialized") return Response(HttpStatusCode.Accepted)
        if (id == null) return Response(HttpStatusCode.BadRequest)
        val result = when (methodName) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                put("serverInfo", buildJsonObject { put("name", "measix-local-enterprise"); put("version", "1") })
            }
            "ping" -> buildJsonObject { }
            "tools/list" -> buildJsonObject {
                put("tools", JsonArray(if (gateway) LocalEnterpriseMcpSurface.gatewayTools.map { it.definition } else listOf(profileTool)))
            }
            "tools/call" -> {
                val params = rpc["params"] as? JsonObject
                val name = (params?.get("name") as? JsonPrimitive)?.content
                val arguments = params?.get("arguments") as? JsonObject ?: buildJsonObject { }
                if (gateway) when (name) {
                    "discover_tools" -> discover(target, arguments)
                    "invoke_tool" -> invoke(target, arguments)
                    else -> failure("gateway_tool_not_found")
                } else {
                    if (name != "get_enterprise_profile") failure("tool_not_found")
                    else if (arguments.isNotEmpty()) failure("tool_arguments_invalid")
                    else success(buildJsonObject {
                        put("enterpriseName", candidate.identity.enterpriseName)
                        put("userName", candidate.identity.userName)
                    })
                }
            }
            else -> return Response(HttpStatusCode.OK, buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") })
            })
        }
        return Response(HttpStatusCode.OK, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
    }

    private fun discover(target: McpConnectionDefinition.Managed, args: JsonObject): JsonObject {
        val queries = args["queries"] as? JsonArray ?: return failure("tool_arguments_invalid")
        val limit = if ("limitPerQuery" in args) (args["limitPerQuery"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: return failure("tool_arguments_invalid") else 3
        if (args.keys.any { it !in setOf("queries", "limitPerQuery") } || queries.size !in 1..5 || limit !in 1..5 ||
            queries.any { it !is JsonPrimitive || !it.isString }) return failure("tool_arguments_invalid")
        return success(buildJsonObject {
            put("catalogGeneration", target.version.generation)
            put("results", JsonArray(queries.map { query ->
                val value = query.jsonPrimitive.content
                val matches = published.filter { tool ->
                    value.isBlank() || tool.aliases.any { value.contains(it, true) || it.contains(value, true) }
                }.take(limit)
                buildJsonObject {
                    put("query", value)
                    put("matches", JsonArray(matches.map { tool ->
                        val expiry = nowMillis() + 5 * 60_000
                        buildJsonObject {
                            put("toolRef", sign(RefClaims(principal(target), target.id.id, target.version.generation, tool.id, tool.schemaHash, expiry)))
                            put("gatewayToolId", tool.id); put("name", tool.name); put("description", tool.description)
                            put("inputSchema", tool.schema); put("risk", "READ_ONLY"); put("expiresAt", Instant.ofEpochMilli(expiry).toString())
                        }
                    }))
                }
            }))
        })
    }

    private suspend fun invoke(target: McpConnectionDefinition.Managed, args: JsonObject): JsonObject {
        val ref = (args["toolRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return failure("tool_arguments_invalid")
        val arguments = args["arguments"] as? JsonObject ?: return failure("tool_arguments_invalid")
        if (args.keys != setOf("toolRef", "arguments")) return failure("tool_arguments_invalid")
        val claims = verify(ref) ?: return failure("tool_ref_invalid")
        if (claims.expiresAt <= nowMillis()) return failure("tool_ref_expired")
        if (claims.principal != principal(target) || claims.resourceId != target.id.id || claims.generation != target.version.generation) {
            return failure("tool_ref_scope_mismatch")
        }
        val tool = published.find { it.id == claims.toolId } ?: return failure("gateway_tool_not_found")
        if (tool.schemaHash != claims.schemaHash) return failure("gateway_catalog_drift")
        return execute(target, tool, arguments)
    }

    private suspend fun execute(target: McpConnectionDefinition.Managed, tool: PublishedTool, args: JsonObject): JsonObject {
        val requestId = "req_${Uuid.random()}"
        val result = try {
            when (tool.id) {
                "gtl_enterprise_updates" -> {
                    if (args.keys.any { it !in setOf("startDate", "endDate", "limit") }) return failure("tool_arguments_invalid")
                    fun optionalString(key: String): String? = args[key]?.let {
                        (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw EnterpriseFeedException("invalid_request")
                    }
                    val limit = args["limit"]?.let { (it as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?: throw EnterpriseFeedException("invalid_request") } ?: 10
                    json.encodeToJsonElement(sessions.listFeed(target.access,
                        EnterpriseFeedQuery(optionalString("startDate"), optionalString("endDate"), limit)).body).jsonObject
                }
                else -> {
                    val query = (args["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (args.keys != setOf("query") || query == null) return failure("tool_arguments_invalid")
                    readGuide(query)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: EnterpriseFeedException) { return failure("tool_arguments_invalid") }
        catch (error: LocalMcpDownstreamFailure) {
            return withResolved(failure(if (error.outcomeUnknown) "downstream_outcome_unknown" else "downstream_tool_error"),
                tool, requestId, if (error.outcomeUnknown) "UNKNOWN" else "FAILED")
        }
        return withResolved(success(result), tool, requestId, "SUCCEEDED")
    }

    private fun principal(target: McpConnectionDefinition.Managed) = digest(json.encodeToString(listOf(
        target.access.scope.storageKey(), deviceId, target.access.sessionId, target.interactionId,
    )))

    private fun sign(claims: RefClaims): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(claims).toByteArray())
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload))
    }
    private fun verify(ref: String): RefClaims? = try {
        val parts = ref.split('.')
        if (ref.length > 4096 || parts.size != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) null
        else json.decodeFromString<RefClaims>(Base64.getUrlDecoder().decode(parts[0]).decodeToString())
    } catch (_: IllegalArgumentException) { null }
    private fun mac(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(signingKey, "HmacSHA256")); doFinal(value.toByteArray())
    }
    private fun success(body: JsonObject) = buildJsonObject {
        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", body.toString()) })))
        put("structuredContent", body); put("isError", false)
    }
    private fun failure(code: String) = buildJsonObject {
        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", code) })))
        put("structuredContent", buildJsonObject { put("code", code) }); put("isError", true)
    }
    private fun withResolved(result: JsonObject, tool: PublishedTool, requestId: String, status: String): JsonObject =
        JsonObject(result + ("_meta" to buildJsonObject {
            put("com.measix/resolvedTool", buildJsonObject {
                put("gatewayToolId", tool.id); put("name", tool.name); put("status", status); put("requestId", requestId)
            })
        }))

    private data class Response(val status: HttpStatusCode, val body: JsonObject? = null)
    @Serializable private data class RefClaims(val principal: String, val resourceId: String, val generation: Long,
        val toolId: String, val schemaHash: String, val expiresAt: Long)
    private class PublishedTool(val id: String, val name: String, val description: String, val schema: JsonObject, val aliases: List<String>) {
        val schemaHash = digest(schema.toString())
    }
    private val profileTool = buildJsonObject {
        put("name", "get_enterprise_profile")
        put("description", "Read the current enterprise and member display names.")
        put("inputSchema", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false)
        })
    }
    private val published = listOf(
        PublishedTool("gtl_enterprise_updates", "get_enterprise_updates", "Read published enterprise updates by date.",
            json.parseToJsonElement("""{"type":"object","properties":{"startDate":{"type":"string"},"endDate":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":20,"default":10}},"additionalProperties":false}""").jsonObject,
            listOf("get_enterprise_updates", "updates", "notices", "动态", "公告", "通知")),
        PublishedTool("gtl_enterprise_guide", "read_enterprise_guide", "Search the local enterprise reference guide.",
            json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}""").jsonObject,
            listOf("read_enterprise_guide", "guide", "reference", "企业", "指南", "参考")),
    )
    companion object {
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal class LocalMcpDownstreamFailure(val outcomeUnknown: Boolean) : Exception("local_mcp_downstream_failure")
