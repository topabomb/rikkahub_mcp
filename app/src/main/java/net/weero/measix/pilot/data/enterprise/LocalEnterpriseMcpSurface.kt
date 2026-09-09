package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.ai.mcp.McpCatalogTool

/** Installed example service definitions; consumers must still discover them over MCP. */
internal object LocalEnterpriseMcpSurface {
    val gatewayTools: List<McpCatalogTool> = Json.decodeFromString("""
[
  {
    "name": "discover_tools",
    "description": "Find published enterprise tools and their complete input schemas.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "queries": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "minItems": 1,
          "maxItems": 5
        },
        "limitPerQuery": {
          "type": "integer",
          "default": 3,
          "minimum": 1,
          "maximum": 5
        }
      },
      "required": [
        "queries"
      ],
      "additionalProperties": false
    },
    "outputSchema": {
      "type": "object",
      "properties": {
        "catalogGeneration": {
          "type": "integer"
        },
        "results": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string"
              },
              "matches": {
                "type": "array",
                "items": {
                  "type": "object",
                  "properties": {
                    "toolRef": {
                      "type": "string"
                    },
                    "gatewayToolId": {
                      "type": "string"
                    },
                    "name": {
                      "type": "string"
                    },
                    "description": {
                      "type": "string"
                    },
                    "inputSchema": {
                      "type": "object"
                    },
                    "outputSchema": {
                      "type": "object"
                    },
                    "risk": {
                      "type": "string",
                      "enum": [
                        "READ_ONLY"
                      ]
                    },
                    "expiresAt": {
                      "type": "string",
                      "format": "date-time"
                    }
                  },
                  "required": [
                    "toolRef",
                    "gatewayToolId",
                    "name",
                    "description",
                    "inputSchema",
                    "risk",
                    "expiresAt"
                  ],
                  "additionalProperties": false
                }
              }
            },
            "required": [
              "query",
              "matches"
            ],
            "additionalProperties": false
          }
        }
      },
      "required": [
        "catalogGeneration",
        "results"
      ],
      "additionalProperties": false
    }
  },
  {
    "name": "invoke_tool",
    "description": "Invoke a published enterprise tool using the reference returned by discover_tools.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "toolRef": {
          "type": "string"
        },
        "arguments": {
          "type": "object"
        }
      },
      "required": [
        "toolRef",
        "arguments"
      ],
      "additionalProperties": false
    }
  }
]
    """.trimIndent())

    fun validate(packet: EnterprisePackage) {
        val exampleIds = packet.runtimeBindings.filter { it.protocol == EnterpriseRuntimeProtocol.EXAMPLE }
            .mapTo(hashSetOf()) { it.resourceId }
        packet.configuration.gateways.filter { it.id in exampleIds }.forEach { gateway ->
            try { gateway.surface.validate(gatewayTools) }
            catch (_: IllegalArgumentException) { throw EnterpriseConfigurationException("example_gateway_surface_mismatch") }
        }
    }
}
