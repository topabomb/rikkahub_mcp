package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserToolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val tool = buildAskUserTool()

    @Test
    fun `valid text question is approved for HITL`() {
        val args = validTextQuestion()
        assertNull(validateAskUserArguments(args))
        assertTrue(tool.needsApproval(args))
    }

    @Test
    fun `valid single-select strings are approved for HITL`() {
        val args = validSingleQuestion()
        assertNull(validateAskUserArguments(args))
        assertTrue(tool.needsApproval(args))
    }

    @Test
    fun `object options are rejected and not sent to HITL`() = runTest {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "retry_ask")
                    put("question", "二次测试：现在您看到输入框了吗？")
                    put("selection_type", "single")
                    put("options", buildJsonArray {
                        addJsonObject {
                            put("value", "yes")
                            put("label", "看到了弹窗")
                        }
                        addJsonObject {
                            put("value", "no")
                            put("label", "没看到弹窗")
                        }
                        addJsonObject {
                            put("value", "cannot_input")
                            put("label", "还是无法输入")
                        }
                    })
                }
            })
        }

        val error = validateAskUserArguments(args)
        assertNotNull(error)
        assertEquals("questions[0].options[0]", error!!.field)
        assertEquals("non-empty string", error.expected)
        assertEquals(ASK_USER_OPTIONS_HINT, error.hint)
        assertTrue(tool.needsApproval(args))

        val result = parseResult(tool.execute(args))
        assertEquals("invalid_arguments", result["error"]!!.jsonPrimitive.content)
        assertEquals("questions[0].options[0]", result["field"]!!.jsonPrimitive.content)
        assertEquals("non-empty string", result["expected"]!!.jsonPrimitive.content)
        assertEquals(ASK_USER_OPTIONS_HINT, result["hint"]!!.jsonPrimitive.content)
        assertEquals(result, tool.validateArguments(args))
        assertNull(result["type"])
        val rejection = assertThrows(ToolArgumentsException::class.java) { tool.parseArguments(args.toString(), json) }
        val replay = parseResult(rejection.output)
        assertEquals(result, JsonObject(replay.filterKeys { it != "type" }))
        assertEquals("error", replay["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing questions returns invalid_arguments`() = runTest {
        val args = buildJsonObject { }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions", error.field)
        assertTrue(tool.needsApproval(args))
        assertEquals("invalid_arguments", parseResult(tool.execute(args))["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty questions array returns invalid_arguments`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray { })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions", error.field)
        assertEquals("non-empty array", error.expected)
        assertTrue(tool.needsApproval(args))
    }

    @Test
    fun `questions must be an array`() {
        val args = buildJsonObject {
            put("questions", buildJsonObject {
                put("id", "q1")
                put("question", "Hello?")
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions", error.field)
        assertEquals("array", error.expected)
    }

    @Test
    fun `missing id is rejected`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("question", "Hello?")
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].id", error.field)
    }

    @Test
    fun `missing question text is rejected`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].question", error.field)
    }

    @Test
    fun `duplicate ids are rejected`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "First?")
                }
                addJsonObject {
                    put("id", "q1")
                    put("question", "Second?")
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[1].id", error.field)
        assertEquals("unique string", error.expected)
    }

    @Test
    fun `single selection requires string options`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "Pick one")
                    put("selection_type", "single")
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].options", error.field)
        assertEquals(ASK_USER_OPTIONS_HINT, error.hint)
        assertTrue(tool.needsApproval(args))
    }

    @Test
    fun `multi selection rejects empty options`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "Pick some")
                    put("selection_type", "multi")
                    put("options", buildJsonArray { })
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].options", error.field)
        assertEquals("non-empty array of strings", error.expected)
    }

    @Test
    fun `invalid selection_type is rejected`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "Hello?")
                    put("selection_type", "dropdown")
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].selection_type", error.field)
    }

    @Test
    fun `numeric option is rejected`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "Pick one")
                    put("selection_type", "single")
                    put("options", buildJsonArray {
                        add(1)
                    })
                }
            })
        }
        val error = validateAskUserArguments(args)!!
        assertEquals("questions[0].options[0]", error.field)
        assertEquals(ASK_USER_OPTIONS_HINT, error.hint)
    }

    @Test
    fun `more than four questions are rejected before HITL`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                repeat(5) { index ->
                    addJsonObject {
                        put("id", "q$index")
                        put("question", "Question $index?")
                    }
                }
            })
        }

        val error = validateAskUserArguments(args)!!
        assertEquals("questions", error.field)
        assertEquals("at most 4 items", error.expected)
    }

    @Test
    fun `oversized arguments are rejected instead of being truncated`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                addJsonObject {
                    put("id", "q1")
                    put("question", "x".repeat(MAX_ASK_USER_INPUT_CHARS))
                }
            })
        }

        val error = validateAskUserArguments(args)!!
        assertEquals("arguments", error.field)
        assertEquals("at most $MAX_ASK_USER_INPUT_CHARS characters", error.expected)
    }

    @Test
    fun `valid arguments must not execute outside HITL`() = runTest {
        val result = runCatching { tool.execute(validTextQuestion()) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HITL"))
    }

    private fun validTextQuestion() = buildJsonObject {
        put("questions", buildJsonArray {
            addJsonObject {
                put("id", "q1")
                put("question", "What should we do next?")
            }
        })
    }

    private fun validSingleQuestion() = buildJsonObject {
        put("questions", buildJsonArray {
            addJsonObject {
                put("id", "retry_ask")
                put("question", "Did you see the input field?")
                put("selection_type", "single")
                put("options", buildJsonArray {
                    add("看到了弹窗")
                    add("没看到弹窗")
                    add("还是无法输入")
                })
            }
        })
    }

    private fun parseResult(parts: List<UIMessagePart>): JsonObject {
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        return json.parseToJsonElement(text).jsonObject
    }
}
