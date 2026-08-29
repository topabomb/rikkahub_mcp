package net.weero.measix.pilot.data.ai.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTrustValidationTest {
    @Test
    fun `protected resource metadata is bound to the requested HTTPS resource`() {
        McpOAuthClient.validateProtectedResourceMetadata(
            expectedResource = "https://mcp.example/api",
            metadata = McpOAuthClient.ProtectedResourceMetadata(
                resource = "https://mcp.example/api",
                authorizationServers = listOf("https://auth.example"),
            ),
        )

        assertTrue(
            runCatching {
                McpOAuthClient.validateProtectedResourceMetadata(
                    expectedResource = "https://mcp.example/api",
                    metadata = McpOAuthClient.ProtectedResourceMetadata(
                        resource = "https://attacker.example/api",
                        authorizationServers = listOf("https://auth.example"),
                    ),
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                McpOAuthClient.validateProtectedResourceMetadata(
                    expectedResource = "https://mcp.example/api",
                    metadata = McpOAuthClient.ProtectedResourceMetadata(
                        resource = "https://mcp.example/api#other-resource",
                        authorizationServers = listOf("https://auth.example"),
                    ),
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                McpOAuthClient.validateProtectedResourceMetadata(
                    expectedResource = "https://mcp.example/api",
                    metadata = McpOAuthClient.ProtectedResourceMetadata(
                        resource = "https://mcp.example/api",
                        authorizationServers = listOf("http://auth.example"),
                    ),
                )
            }.isFailure
        )
    }

    @Test
    fun `authorization metadata requires matching issuer and secure endpoints`() {
        val valid = McpOAuthClient.AuthorizationServerMetadata(
            issuer = "https://auth.example",
            authorizationEndpoint = "https://auth.example/authorize",
            tokenEndpoint = "https://auth.example/token",
            registrationEndpoint = "https://auth.example/register",
        )
        McpOAuthClient.validateAuthorizationServerMetadata("https://auth.example", valid)

        assertTrue(
            runCatching {
                McpOAuthClient.validateAuthorizationServerMetadata(
                    "https://auth.example",
                    valid.copy(issuer = "https://attacker.example"),
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                McpOAuthClient.validateAuthorizationServerMetadata(
                    "https://auth.example",
                    valid.copy(tokenEndpoint = "http://auth.example/token"),
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                McpOAuthClient.validateAuthorizationServerMetadata(
                    "https://auth.example",
                    valid.copy(issuer = "https://auth.example#different-issuer"),
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                McpOAuthClient.validateAuthorizationServerMetadata(
                    "https://auth.example",
                    valid.copy(tokenEndpoint = "https://user:secret@auth.example/token"),
                )
            }.isFailure
        )
    }
}
