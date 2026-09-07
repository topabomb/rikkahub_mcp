package net.weero.measix.pilot.service

import android.net.Uri
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.buildChildUserParts
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantChildPartsTest {
    @Test
    fun `attachment tool inputs follow an existing cross-message clone without touching other fields`() = runTest {
        AttachmentCloneFixture().use { fixture ->
            val stableRef = AttachmentRefs.format(Uuid.random())
            fixture.clone(listOf(AttachmentRefs.ensureAttachmentRef(
                UIMessagePart.Image(fixture.sourceRef.fileUri(fixture.filesDir)),
            )))
            val sourcePath = fixture.sourceRef.toolPath()!!
            val input = buildJsonObject {
                put("attachments", JsonArray(listOf(sourcePath, stableRef, sourcePath).map(::JsonPrimitive)))
                put("request", "Inspect $sourcePath without rewriting this prose")
                put("other", buildJsonObject { put("attachments", JsonArray(listOf(JsonPrimitive(sourcePath)))) })
            }.toString()
            val originals = listOf("inspect_attachments", "assistant_call").map { name ->
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = name, toolName = name, input = input)
            }

            val cloned = fixture.clone(originals).map { it as UIMessagePart.Tool }

            cloned.forEach { tool ->
                val args = JsonInstant.parseToJsonElement(tool.input).jsonObject
                val originalArgs = JsonInstant.parseToJsonElement(input).jsonObject
                assertEquals(
                    listOf(fixture.copiedRef.toolPath(), stableRef, fixture.copiedRef.toolPath()),
                    args["attachments"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals(originalArgs["request"], args["request"])
                assertEquals(originalArgs["other"], args["other"])
            }
            assertTrue(originals.all { it.input == input })
            assertEquals(listOf(fixture.owned), fixture.created)
            coVerify(exactly = 1) { fixture.store.copyFilePreservingOrigin(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `clonePart rebinds attachments after its own output or sub-assistant manifest is copied`() = runTest {
        listOf("inspect_attachments", "assistant_call").forEach { toolName ->
            AttachmentCloneFixture().use { fixture ->
                val sourcePath = fixture.sourceRef.toolPath()!!
                val input = """{"attachments":["$sourcePath"],"request":"$sourcePath"}"""
                val image = AttachmentRefs.ensureAttachmentRef(
                    UIMessagePart.Image(fixture.sourceRef.fileUri(fixture.filesDir)),
                ) as UIMessagePart.Image
                var tool = UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = toolName,
                    toolName = toolName,
                    input = input,
                    output = listOf(image),
                )
                if (toolName == "assistant_call") {
                    val metadata = SubAssistantCallMetadata(
                        runId = Uuid.random().toString(),
                        targetAssistantId = Uuid.random().toString(),
                        targetNameSnapshot = "Target",
                        state = SubAssistantCallState.COMPLETED,
                        artifacts = listOf(SubAssistantCallArtifact(
                            AttachmentRefs.getStableRef(image)!!, "image", "image/png", fixture.sourceRef,
                        )),
                    )
                    tool = tool.copy(output = listOf(
                        UIMessagePart.Text(buildSubAssistantCallResult(
                            JsonInstant, "completed", "Target", "done", artifacts = metadata.artifacts,
                        )),
                        UIMessagePart.Tool(
                            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "nested-inspection", toolName = "inspect_attachments", input = input,
                        ),
                    )).mergeSubAssistantCallMetadata(JsonInstant, metadata)
                }

                val cloned = AttachmentCloner.clonePart(
                    tool, fixture.store, fixture.created, fixture.rewriter,
                ) as UIMessagePart.Tool

                val args = JsonInstant.parseToJsonElement(cloned.input).jsonObject
                assertEquals(fixture.copiedRef.toolPath(), args["attachments"]!!.jsonArray.single().jsonPrimitive.content)
                assertEquals(sourcePath, args["request"]!!.jsonPrimitive.content)
                assertEquals(input, tool.input)
                if (toolName == "assistant_call") {
                    val nested = cloned.output[1] as UIMessagePart.Tool
                    assertEquals(cloned.input, nested.input)
                    assertEquals(fixture.copiedRef, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().artifact)
                } else {
                    assertEquals(fixture.copiedRef.fileUri(fixture.filesDir), (cloned.output.single() as UIMessagePart.Image).url)
                    assertEquals(AttachmentRefs.getStableRef(image), AttachmentRefs.getStableRef(cloned.output.single()))
                }
                assertEquals(listOf(fixture.owned), fixture.created)
                coVerify(exactly = 1) { fixture.store.copyFilePreservingOrigin(any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `clone preserves unknown malformed UUID and input-only attachment references`() = runTest {
        AttachmentCloneFixture().use { fixture ->
            fixture.clone(listOf(UIMessagePart.Image(fixture.sourceRef.fileUri(fixture.filesDir))))
            val sourcePath = fixture.sourceRef.toolPath()!!
            val validInput = """{ "attachments": ["$sourcePath"], "prompt": "$sourcePath" }"""
            val otherRootFile = File(fixture.filesDir, "images/same-name.png")
            fixture.copiedArtifacts[otherRootFile.canonicalPath] = fixture.owned
            coEvery { fixture.store.resolveToolPath("/upload/same-name.png") } returns
                File(fixture.filesDir, "upload/same-name.png")
            coEvery { fixture.store.resolveToolPath("/upload/input-only.png") } returns
                File(fixture.filesDir, "upload/input-only.png")
            val untouched = listOf(
                "generate_image" to validInput,
                "unknown_tool" to validInput,
                "inspect_attachments" to """{ "attachments": ["${AttachmentRefs.format(Uuid.random())}"] }""",
                "inspect_attachments" to """{ "attachments": ["/upload/same-name.png", "/upload/input-only.png", "/upload/missing.png"] }""",
                "inspect_attachments" to """{ "attachments": ["/upload/../source.png", "https://example.test/source.png"] }""",
                "inspect_attachments" to """{ "attachments": ["$sourcePath", 42] }""",
                "inspect_attachments" to """{ "attachments": "$sourcePath" }""",
                "inspect_attachments" to """{ "request": "$sourcePath" }""",
                "inspect_attachments" to "{",
            ).mapIndexed { index, (name, input) ->
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = index.toString(), toolName = name, input = input)
            }

            val cloned = fixture.clone(untouched).map { it as UIMessagePart.Tool }

            assertEquals(untouched.map { it.input }, cloned.map { it.input })
            assertEquals(listOf(fixture.owned), fixture.created)
            coVerify(exactly = 1) { fixture.store.copyFilePreservingOrigin(any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.store.resolveToolPath(sourcePath) }
        }
    }

    @Test
    fun `attachment input rebinding propagates lookup cancellation`() = runTest {
        AttachmentCloneFixture().use { fixture ->
            fixture.copiedArtifacts[fixture.sourceFile.canonicalPath] = fixture.owned
            val cancelled = CancellationException("fork cancelled")
            val sourcePath = fixture.sourceRef.toolPath()!!
            coEvery { fixture.store.resolveToolPath(sourcePath) } throws cancelled
            val tool = UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "inspection", toolName = "inspect_attachments",
                input = """{"attachments":["$sourcePath"]}""",
            )

            try {
                fixture.clone(listOf(tool))
                fail("cancellation must propagate to the clone owner")
            } catch (actual: CancellationException) {
                assertEquals(cancelled, actual)
            }
            coVerify(exactly = 0) { fixture.store.copyFilePreservingOrigin(any(), any(), any(), any()) }
            coVerify(exactly = 0) { fixture.store.discardUnpublished(any()) }
        }
    }

    @Test
    fun `clone does not copy unmanaged file uri`() = runTest {
        val source = kotlin.io.path.createTempFile("unmanaged", ".png").toFile()
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.resolveManagedReference(any<File>()) } returns null
        val created = mutableListOf<OwnedArtifact>()

        val cloned = AttachmentCloner.clonePart(
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(source)),
            artifactStore,
            created,
            mockk(relaxed = true),
        ) as UIMessagePart.Image

        assertEquals(AttachmentRefs.fileToFileUrl(source), cloned.url)
        assertTrue(created.isEmpty())
        coVerify(exactly = 0) { artifactStore.copyFilePreservingOrigin(any(), any(), any(), any()) }
        source.delete()
    }

    @Test
    fun `one clone operation reuses the copied artifact across messages`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("shared-clone").toFile()
        val source = File(filesDir, "upload/source.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        val owned = mockk<OwnedArtifact>()
        val copiedUri = mockk<Uri>()
        every { owned.localRef } returns copiedRef
        every { owned.uri } returns copiedUri
        every { copiedUri.toString() } returns copiedRef.fileUri(filesDir)
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveManagedReference(any<File>()) } returns sourceRef
        every { store.file(sourceRef) } returns source
        coEvery { store.copyFilePreservingOrigin(source, "image/png", "source.png", any()) } returns owned
        val created = mutableListOf<OwnedArtifact>()
        val parts = listOf(
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(source)),
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(source)),
        )

        val cloned = AttachmentCloner.cloneParts(
            parts = parts,
            artifactStore = store,
            createdArtifacts = created,
            toolArtifactRewriter = ToolArtifactRewriter(filesDir, store),
            copiedArtifacts = linkedMapOf(),
        )

        assertEquals(listOf(owned), created)
        assertEquals(listOf(copiedRef.fileUri(filesDir), copiedRef.fileUri(filesDir)), cloned.map { (it as UIMessagePart.Image).url })
        filesDir.deleteRecursively()
    }

    @Test
    fun `one clone operation reuses copied artifact for ordinary tool metadata`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("shared-tool-clone").toFile()
        val source = File(filesDir, "upload/source.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        val owned = mockk<OwnedArtifact>()
        val copiedUri = mockk<Uri>()
        every { owned.localRef } returns copiedRef
        every { owned.uri } returns copiedUri
        every { copiedUri.toString() } returns copiedRef.fileUri(filesDir)
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveManagedReference(any<File>()) } returns sourceRef
        coEvery { store.materialize(sourceRef) } returns sourceRef
        every { store.file(sourceRef) } returns source
        coEvery { store.copyFilePreservingOrigin(source, "image/png", "source.png", any()) } returns owned
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tool",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(sourceRef.fileUri(filesDir))),
            metadata = rewriter.encodeArtifactRef(null, sourceRef),
        )

        val cloned = AttachmentCloner.cloneParts(
            parts = listOf(UIMessagePart.Image(sourceRef.fileUri(filesDir)), tool),
            artifactStore = store,
            createdArtifacts = mutableListOf<OwnedArtifact>(),
            toolArtifactRewriter = rewriter,
            copiedArtifacts = linkedMapOf(),
        )

        assertEquals(listOf(copiedRef.fileUri(filesDir), copiedRef.fileUri(filesDir)), listOf(
            (cloned[0] as UIMessagePart.Image).url,
            ((cloned[1] as UIMessagePart.Tool).output.single() as UIMessagePart.Image).url,
        ))
        coVerify(exactly = 1) { store.copyFilePreservingOrigin(source, "image/png", "source.png", any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `child user parts are request text plus images`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val parts = buildChildUserParts("do the work", listOf(image))
        assertEquals(2, parts.size)
        assertEquals("do the work", (parts[0] as UIMessagePart.Text).text)
        assertEquals(image, parts[1])
    }

    @Test
    fun `clone copy keeps attachment ref on non-file parts`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image(
            url = "https://example.com/a.png",
            metadata = buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, ref)
                put("thoughtSignature", "sig")
            },
        )
        val artifactStore = mockk<ArtifactStore>(relaxed = true)
        val copied = AttachmentCloner.clonePart(
            image,
            artifactStore,
            mutableListOf(),
            mockk(relaxed = true),
        ) as UIMessagePart.Image
        assertEquals(ref, AttachmentRefs.getRef(copied))
        assertEquals("sig", (copied.metadata!!["thoughtSignature"] as JsonPrimitive).content)
        assertEquals(image.url, copied.url)
    }

    @Test
    fun `clone copy of tool output preserves nested ref`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(
                UIMessagePart.Image(
                    url = "https://example.com/a.png",
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        val copied = AttachmentCloner.clonePart(
            tool,
            mockk(relaxed = true),
            mutableListOf<OwnedArtifact>(),
            mockk(relaxed = true),
        ) as UIMessagePart.Tool
        assertEquals(ref, AttachmentRefs.getRef(copied.output.single()))
        assertTrue(copied.output.single() is UIMessagePart.Image)
    }

    @Test
    fun `tool artifact clone returns ownership token to caller`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("tool-artifact-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        coEvery { artifactStore.materialize(sourceRef) } returns sourceRef
        every { artifactStore.file(sourceRef) } returns sourceRef.file(filesDir)
        every { owned.localRef } returns copiedRef
        io.mockk.coEvery {
            artifactStore.copyFilePreservingOrigin(any(), any(), any(), any())
        } returns owned
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = sourceRef.fileUri(filesDir))),
            metadata = rewriter.encodeArtifactRef(null, sourceRef),
        )
        val created = mutableListOf<OwnedArtifact>()

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = created,
            toolArtifactRewriter = rewriter,
        ) as UIMessagePart.Tool

        assertEquals(listOf(owned), created)
        assertEquals(copiedRef.fileUri(filesDir), (cloned.output.single() as UIMessagePart.Image).url)
        assertEquals(copiedRef, rewriter.decodeArtifactRef(cloned.metadata!!))
        filesDir.deleteRecursively()
    }

    @Test
    fun `missing tool artifact clone removes stale durable reference`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("missing-tool-artifact-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/missing.png", mimeType = "image/png")
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.materialize(sourceRef) } returns null
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = sourceRef.fileUri(filesDir))),
            metadata = rewriter.encodeArtifactRef(null, sourceRef),
        )

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = mutableListOf(),
            toolArtifactRewriter = rewriter,
        ) as UIMessagePart.Tool

        assertTrue(cloned.output.isEmpty())
        assertEquals(null, rewriter.decodeArtifactRef(cloned.metadata!!))
        filesDir.deleteRecursively()
    }

    @Test
    fun `assistant call clone rewrites manifest and output to copied artifact`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("assistant-call-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        val sourceFile = sourceRef.file(filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        coEvery { artifactStore.materialize(sourceRef) } returns sourceRef
        every { artifactStore.file(sourceRef) } returns sourceFile
        every { owned.localRef } returns copiedRef
        val copiedUri = mockk<Uri>()
        every { copiedUri.toString() } returns copiedRef.fileUri(filesDir)
        every { owned.uri } returns copiedUri
        coEvery {
            artifactStore.copyFilePreservingOrigin(sourceFile, "image/png", "source.png", any())
        } returns owned
        val metadata = SubAssistantCallMetadata(
            runId = Uuid.random().toString(),
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Target",
            state = SubAssistantCallState.COMPLETED,
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = AttachmentRefs.format(Uuid.random()),
                    type = "image",
                    mime = "image/png",
                    artifact = sourceRef,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(
                UIMessagePart.Image(sourceRef.fileUri(filesDir)),
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "completed")
                        put(
                            "artifacts",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("ref", metadata.artifacts.single().ref)
                                    put("type", "image")
                                    put("mime", "image/png")
                                    put("path", sourceRef.toolPath()!!)
                                })
                            },
                        )
                    }.toString(),
                ),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "nested-result",
                    toolName = "nested",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "completed")
                                put(
                                    "artifacts",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(buildJsonObject {
                                            put("ref", metadata.artifacts.single().ref)
                                            put("type", "image")
                                            put("mime", "image/png")
                                            put("path", sourceRef.toolPath()!!)
                                        })
                                    },
                                )
                            }.toString(),
                        ),
                    ),
                ),
            ),
        ).mergeSubAssistantCallMetadata(JsonInstant, metadata)
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)
        val created = mutableListOf<OwnedArtifact>()

        val cloned = AttachmentCloner.clonePart(tool, artifactStore, created, rewriter) as UIMessagePart.Tool

        assertEquals(listOf(owned), created)
        assertEquals(copiedRef.fileUri(filesDir), (cloned.output[0] as UIMessagePart.Image).url)
        val resultJson = JsonInstant.parseToJsonElement((cloned.output[1] as UIMessagePart.Text).text) as JsonObject
        assertFalse(resultJson["artifacts"]!!.jsonArray.single().jsonObject.containsKey("ref"))
        assertEquals(copiedRef.toolPath(), resultJson["artifacts"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
        val nestedJson = JsonInstant.parseToJsonElement(
            ((cloned.output[2] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text,
        ) as JsonObject
        assertEquals(
            sourceRef.toolPath(),
            nestedJson["artifacts"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content,
        )
        val copiedMetadata = cloned.getSubAssistantCallMetadata(JsonInstant)!!
        assertEquals(metadata.artifacts.single().ref, copiedMetadata.artifacts.single().ref)
        assertEquals(copiedRef, copiedMetadata.artifacts.single().artifact)
        filesDir.deleteRecursively()
    }

    @Test
    fun `assistant call clone derives model refs from copied metadata without rewriting prose`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("assistant-call-ref-clone").toFile()
        try {
            val sourceRef = LocalArtifactRef(relativePath = "upload/gsource.png", mimeType = "image/png")
            val copiedRef = LocalArtifactRef(relativePath = "upload/gcopied.png", mimeType = "image/png")
            val sourceFile = sourceRef.file(filesDir).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val store = mockk<ArtifactStore>()
            val owned = mockk<OwnedArtifact>()
            coEvery { store.materialize(sourceRef) } returns sourceRef
            every { store.file(sourceRef) } returns sourceFile
            every { owned.localRef } returns copiedRef
            coEvery { store.copyFilePreservingOrigin(sourceFile, "image/png", sourceFile.name, any()) } answers {
                sourceFile.copyTo(copiedRef.file(filesDir))
                owned
            }
            val stableRef = AttachmentRefs.format(Uuid.random())
            val metadata = SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Target",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(SubAssistantCallArtifact(stableRef, "image", "image/png", sourceRef)),
            )
            val content = "Source was ${sourceRef.toolPath()}; leave this explanation unchanged."
            val outputText = buildSubAssistantCallResult(
                json = JsonInstant,
                status = "completed",
                assistantName = "Target",
                content = content,
                artifacts = metadata.artifacts,
            )
            val tool = UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                toolName = "assistant_call",
                input = "{}",
                output = listOf(UIMessagePart.Text(outputText)),
            ).mergeSubAssistantCallMetadata(JsonInstant, metadata)
            val created = mutableListOf<OwnedArtifact>()

            val cloned = AttachmentCloner.clonePart(
                tool, store, created, ToolArtifactRewriter(filesDir, store),
            ) as UIMessagePart.Tool

            val result = JsonInstant.parseToJsonElement((cloned.output.single() as UIMessagePart.Text).text).jsonObject
            assertFalse(result["artifacts"]!!.jsonArray.single().jsonObject.containsKey("ref"))
            assertEquals(copiedRef.toolPath(), result["artifacts"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals(content, result["content"]!!.jsonPrimitive.content)
            assertEquals(stableRef, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().ref)
            assertEquals(outputText, (tool.output.single() as UIMessagePart.Text).text)
            assertEquals(listOf(owned), created)
            assertTrue(sourceFile.delete())
            assertTrue(copiedRef.file(filesDir).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `assistant call clone removes stale output image and path`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("stale-assistant-call-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/stale.png", mimeType = "image/png")
        val sourceFile = sourceRef.file(filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val ref = AttachmentRefs.format(Uuid.random())
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.materialize(sourceRef) } returns null
        every { artifactStore.file(sourceRef) } returns sourceFile
        coEvery { artifactStore.resolveManagedReference(any<File>()) } returns null
        val metadata = SubAssistantCallMetadata(
            runId = Uuid.random().toString(),
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Target",
            state = SubAssistantCallState.COMPLETED,
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = ref,
                    type = "image",
                    mime = "image/png",
                    artifact = sourceRef,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "assistant-call-stale",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(
                AttachmentRefs.withMetadata(
                    UIMessagePart.Image(sourceRef.fileUri(filesDir)),
                    buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "completed")
                        put(
                            "artifacts",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("mime", "image/png")
                                    put("path", sourceRef.toolPath()!!)
                                })
                            },
                        )
                    }.toString(),
                ),
            ),
        ).mergeSubAssistantCallMetadata(JsonInstant, metadata)
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = mutableListOf(),
            toolArtifactRewriter = rewriter,
        ) as UIMessagePart.Tool

        assertEquals(1, cloned.output.size)
        val resultJson = JsonInstant.parseToJsonElement((cloned.output.single() as UIMessagePart.Text).text) as JsonObject
        assertTrue(resultJson["artifacts"]!!.jsonArray.isEmpty())
        assertEquals(ref, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().ref)
        assertEquals(null, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().artifact)
        filesDir.deleteRecursively()
    }

    @Test
    fun `assistant call clone removes output for unavailable null artifact descriptor`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("null-assistant-call-artifact").toFile()
        val ref = AttachmentRefs.format(Uuid.random())
        val staleRef = LocalArtifactRef(relativePath = "upload/stale.png", mimeType = "image/png")
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.resolveManagedReference(any<File>()) } returns null
        val metadata = SubAssistantCallMetadata(
            runId = Uuid.random().toString(),
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Target",
            state = SubAssistantCallState.COMPLETED,
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = ref,
                    type = "image",
                    mime = "image/png",
                    artifact = null,
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "assistant-call-null-artifact",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(
                AttachmentRefs.withMetadata(
                    UIMessagePart.Image(staleRef.fileUri(filesDir)),
                    buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
                UIMessagePart.Image(staleRef.fileUri(filesDir)),
                UIMessagePart.Document(
                    url = staleRef.fileUri(filesDir),
                    fileName = "stale.txt",
                    mime = "text/plain",
                ),
                UIMessagePart.Audio(staleRef.fileUri(filesDir)),
                UIMessagePart.Video(staleRef.fileUri(filesDir)),
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "completed")
                        put(
                            "artifacts",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("mime", "image/png")
                                    put("path", staleRef.toolPath()!!)
                                })
                            },
                        )
                    }.toString(),
                ),
            ),
        ).mergeSubAssistantCallMetadata(JsonInstant, metadata)

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = mutableListOf(),
            toolArtifactRewriter = ToolArtifactRewriter(filesDir, artifactStore),
        ) as UIMessagePart.Tool

        assertEquals(1, cloned.output.size)
        assertTrue(cloned.output.single() is UIMessagePart.Text)
        val resultJson = JsonInstant.parseToJsonElement((cloned.output.single() as UIMessagePart.Text).text) as JsonObject
        assertTrue(resultJson["artifacts"]!!.jsonArray.isEmpty())
        assertEquals(ref, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().ref)
        assertEquals(null, cloned.getSubAssistantCallMetadata(JsonInstant)!!.artifacts.single().artifact)
        filesDir.deleteRecursively()
    }

    @Test
    fun `shared tool artifact is not discarded when rewrite of existing copy fails`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("shared-tool-artifact-failure").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val sourceFile = sourceRef.file(filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.materialize(sourceRef) } returns sourceRef
        every { artifactStore.file(sourceRef) } returns sourceFile
        val existing = mockk<OwnedArtifact>()
        every { existing.localRef } throws IllegalStateException("shared copy unavailable")
        val copiedArtifacts = linkedMapOf(sourceFile.canonicalPath to existing)
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)

        val thrown = runCatching {
            rewriter.rewriteToolOutput(
                output = listOf(UIMessagePart.Image(sourceRef.fileUri(filesDir))),
                metadata = rewriter.encodeArtifactRef(null, sourceRef),
                copiedArtifacts = copiedArtifacts,
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        coVerify(exactly = 0) { artifactStore.discardUnpublished(any()) }
        assertEquals(existing, copiedArtifacts[sourceFile.canonicalPath])
        filesDir.deleteRecursively()
    }

    private class AttachmentCloneFixture : java.io.Closeable {
        val filesDir = kotlin.io.path.createTempDirectory("attachment-input-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copied.png", mimeType = "image/png")
        val sourceFile = sourceRef.file(filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val created = mutableListOf<OwnedArtifact>()
        val copiedArtifacts = linkedMapOf<String, OwnedArtifact>()

        init {
            val uri = mockk<Uri>()
            every { uri.toString() } returns copiedRef.fileUri(filesDir)
            every { owned.uri } returns uri
            every { owned.localRef } returns copiedRef
            every { store.file(sourceRef) } returns sourceFile
            coEvery { store.materialize(sourceRef) } returns sourceRef
            coEvery { store.resolveManagedReference(sourceFile) } returns sourceRef
            coEvery { store.resolveToolPath(any()) } returns null
            coEvery { store.resolveToolPath(sourceRef.toolPath()!!) } returns sourceFile
            coEvery { store.copyFilePreservingOrigin(sourceFile, "image/png", sourceFile.name, any()) } answers {
                sourceFile.copyTo(copiedRef.file(filesDir))
                owned
            }
        }

        suspend fun clone(parts: List<UIMessagePart>): List<UIMessagePart> = AttachmentCloner.cloneParts(
            parts, store, created, rewriter, copiedArtifacts,
        )

        override fun close() {
            filesDir.deleteRecursively()
        }
    }
}
