package net.weero.measix.pilot.data.enterprise

import me.rerere.common.http.RoutedHttpException
import org.junit.Assert.*
import org.junit.Test

class EnterpriseRuntimeProblemTest {
    @Test fun `managed budget problem preserves every blocker and diagnostic context`() {
        val body = """{
          "type":"https://measix.example/problems/budget-exhausted",
          "title":"Budget exhausted",
          "status":429,
          "code":"budget_exhausted",
          "detail":"Two limits block this request",
          "forwarded":false,
          "requestId":"req_550e8400-e29b-41d4-a716-446655440000",
          "budget":{
            "capability":"MODEL","resourceId":"mdl_3d005a2a-7e91-4abd-a1a4-5c7d4a2e1111","mode":"LIMITED",
            "blockingLimits":[
              {"period":"DAY","meter":"REQUESTS","limit":10,"used":9,"reserved":1,"resetAt":"2026-09-21T00:00:00Z"},
              {"period":"MONTH","meter":"TOTAL_TOKENS","limit":1000,"used":800,"reserved":200,"resetAt":"2026-10-01T00:00:00Z"}
            ],
            "resetAt":"2026-09-21T00:00:00Z","asOf":"2026-09-20T12:00:00Z"
          }
        }""".trimIndent()

        val parsed = requireNotNull(EnterpriseRuntimeProblemException.parse(429, body))
        assertEquals(EnterpriseRuntimeProblemCodes.BUDGET_EXHAUSTED, parsed.code)
        assertEquals(2, parsed.problem.budget?.blockingLimits?.size)
        assertTrue(parsed.message!!.contains("DAY/REQUESTS"))
        assertTrue(parsed.message!!.contains("MONTH/TOTAL_TOKENS"))
        assertTrue(parsed.message!!.contains("requestId=req_"))
        assertEquals(parsed.problem, EnterpriseRuntimeProblemException.parseTerminalDetail(parsed.terminalDetail()))
        assertSame(parsed, EnterpriseRuntimeProblemException.find(IllegalStateException("wrapper", parsed)))
        assertEquals(parsed.code, EnterpriseRuntimeProblemException.fromManagedFailure(RoutedHttpException(429, body))?.code)
    }

    @Test fun `untrusted provider error and malformed platform body retain their original semantics`() {
        assertNull(EnterpriseRuntimeProblemException.parse(429,
            """{"type":"about:blank","title":"Rate limited","status":429,"code":"rate_limit"}"""))
        assertNull(EnterpriseRuntimeProblemException.parse(429, "not-json"))
        assertNull(EnterpriseRuntimeProblemException.parse(500,
            """{"type":"about:blank","title":"Budget","status":429,"code":"budget_exhausted"}"""))
        assertNull(EnterpriseRuntimeProblemException.parse(429,
            """{"type":"about:blank","title":"Budget","status":429,"code":"budget_exhausted","forwarded":true}"""))
        assertNull(EnterpriseRuntimeProblemException.parse(429,
            """{"type":"about:blank","title":"Deleted","status":429,"code":"enterprise_identity_deleted","forwarded":false}"""))
    }

    @Test fun `managed authorization revocations preserve their stable reason`() {
        for (code in EnterpriseRuntimeProblemCodes.authorizationRevoked) {
            val body = """{"type":"about:blank","title":"Revoked","status":403,"code":"$code","forwarded":false}"""
            assertEquals(code, EnterpriseRuntimeProblemException.parse(403, body)?.code)
            assertNull(EnterpriseRuntimeProblemException.parse(401, body))
            assertNull(EnterpriseRuntimeProblemException.parse(403, body.replace("false", "true")))
        }
    }
}
