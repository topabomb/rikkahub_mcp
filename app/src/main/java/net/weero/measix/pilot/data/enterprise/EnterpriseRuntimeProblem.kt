package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import kotlinx.serialization.encodeToString
import me.rerere.common.http.RoutedHttpException

internal object EnterpriseRuntimeProblemCodes {
    const val BUDGET_EXHAUSTED = "budget_exhausted"
    const val BUDGET_SERVICE_UNAVAILABLE = "budget_service_unavailable"
    const val USAGE_RECONCILIATION_REQUIRED = "usage_reconciliation_required"
    const val USAGE_METER_UNAVAILABLE = "usage_meter_unavailable"
    const val IDENTITY_DELETED = "enterprise_identity_deleted"
    const val USER_DISABLED = "user_disabled"
    const val DEVICE_REVOKED = "device_revoked"
    const val SESSION_REVOKED = "session_revoked"

    val authorizationRevoked = setOf(USER_DISABLED, DEVICE_REVOKED, SESSION_REVOKED)

    val all = setOf(
        BUDGET_EXHAUSTED,
        BUDGET_SERVICE_UNAVAILABLE,
        USAGE_RECONCILIATION_REQUIRED,
        USAGE_METER_UNAVAILABLE,
        IDENTITY_DELETED,
    ) + authorizationRevoked
}

/** A trusted Core runtime Problem. Callers must only parse responses from a managed platform route. */
internal class EnterpriseRuntimeProblemException(
    val httpStatus: Int,
    val problem: PlatformProblem,
) : IOException(diagnostic(problem)) {
    val code: String get() = problem.code
    fun terminalDetail(): String = TERMINAL_DETAIL_PREFIX + PlatformWireCodec.json.encodeToString(problem)

    companion object {
        fun parse(status: Int, body: String): EnterpriseRuntimeProblemException? {
            if (body.length > MAX_PROBLEM_CHARS) return null
            val problem = try {
                PlatformWireCodec.decode<PlatformProblem>(body)
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: IllegalStateException) {
                return null
            }
            val expectedStatus = when (problem.code) {
                EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED -> 429
                EnterpriseRuntimeProblemCodes.BUDGET_SERVICE_UNAVAILABLE -> 503
                EnterpriseRuntimeProblemCodes.USAGE_RECONCILIATION_REQUIRED -> 503
                EnterpriseRuntimeProblemCodes.USAGE_METER_UNAVAILABLE -> 422
                EnterpriseRuntimeProblemCodes.IDENTITY_DELETED -> 401
                in EnterpriseRuntimeProblemCodes.authorizationRevoked -> 403
                else -> return null
            }
            // Relay-generated rejections carry this sentinel. Upstream bodies do not,
            // so a provider cannot impersonate a platform terminal state.
            if (problem.status.toInt() != status || status != expectedStatus || problem.forwarded != false) return null
            return EnterpriseRuntimeProblemException(status, problem)
        }

        fun find(error: Throwable): EnterpriseRuntimeProblemException? =
            generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)
                .filterIsInstance<EnterpriseRuntimeProblemException>().firstOrNull()

        fun fromManagedFailure(error: Throwable): EnterpriseRuntimeProblemException? {
            find(error)?.let { return it }
            val routed = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)
                .filterIsInstance<RoutedHttpException>().firstOrNull() ?: return null
            return parse(routed.status, routed.detail)
        }

        fun parseTerminalDetail(detail: String?): PlatformProblem? {
            val encoded = detail?.takeIf { it.startsWith(TERMINAL_DETAIL_PREFIX) }
                ?.removePrefix(TERMINAL_DETAIL_PREFIX) ?: return null
            return try {
                PlatformWireCodec.decode<PlatformProblem>(encoded)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun diagnostic(problem: PlatformProblem): String = buildString {
            append(problem.code)
            problem.detail?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
            problem.budget?.let { budget ->
                append(" [capability=").append(budget.capability)
                budget.resourceId?.let { append(", resourceId=").append(it) }
                if (budget.blockingLimits.isNotEmpty()) {
                    append(", blockers=")
                    append(budget.blockingLimits.joinToString("; ") { blocker ->
                        "${blocker.period}/${blocker.meter} used=${blocker.used} reserved=${blocker.reserved} limit=${blocker.limit}" +
                            (blocker.resetAt?.let { " resetAt=$it" } ?: "")
                    })
                }
                append(']')
            }
            problem.requestId?.let { append(" [requestId=").append(it).append(']') }
        }

        private const val MAX_PROBLEM_CHARS = 256 * 1024
        private const val MAX_CAUSE_DEPTH = 8
        private const val TERMINAL_DETAIL_PREFIX = "measix-runtime-problem:"
    }
}
