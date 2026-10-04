package net.weero.measix.pilot.service.workspace

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class WorkspaceImageValidationTest {
    @Test fun decodableBitmapUsesActualContentAndDimensions() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        assertEquals(WorkspaceImageInfo("image/png", 24, 16), validateWorkspaceImage(bytes, "renamed.jpg"))
        assertTrue(runCatching { validateWorkspaceImage("not an image".toByteArray(), "image.png") }.isFailure)
    }

    @Test fun staticSvgAcceptsLocalGradientAndClipReferences() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 480">
            <defs><linearGradient id="paint"><stop offset="0" stop-color="red"/></linearGradient>
            <clipPath id="clip"><rect width="640" height="480"/></clipPath></defs>
            <path d="M0 0L640 480" fill="url(#paint)" clip-path="url(#clip)"/>
            </svg>"""
        assertEquals(WorkspaceImageInfo("image/svg+xml", 640, 480), validateWorkspaceImage(svg.toByteArray(), "drawing.SVG"))
    }

    @Test fun svgDescriptiveAttributesDoNotBecomeResourceExpressions() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <g id="curl" class="url-icon" aria-label="curl@example.test">
            <path d="M0 0L100 100" stroke="red"/></g></svg>"""
        assertEquals(WorkspaceImageInfo("image/svg+xml", 100, 100), validateWorkspaceSvg(svg.toByteArray()))
    }

    @Test fun svgRejectsEntitiesExternalResourcesAndRecursiveDefinitions() {
        val bodies = listOf(
            "<image href=\"https://example.test/image.png\"/>",
            "<script>alert(1)</script>",
            "<style>@import 'https://example.test/style.css';</style>",
            "<rect width=\"10\" height=\"10\" fill=\"url(https://example.test/paint)\"/>",
            "<rect width=\"10\" height=\"10\" onload=\"alert(1)\"/>",
            "<use href=\"#self\" id=\"self\"/>",
            "<defs><clipPath id=\"self\"><path clip-path=\"url(#self)\"/></clipPath></defs>",
            "<rect style=\"fill:u\\72l(https://example.test/paint)\"/>",
        )
        bodies.forEach { body ->
            assertTrue(body, runCatching { validateWorkspaceSvg("<svg>$body</svg>".toByteArray()) }.isFailure)
        }
        val entity = """<!DOCTYPE svg [<!ENTITY ex SYSTEM "file:///private.txt">]><svg><text>&ex;</text></svg>"""
        assertTrue(runCatching { validateWorkspaceSvg(entity.toByteArray()) }.isFailure)
    }

    @Test fun svgBoundsParserWorkAndRenderDimensions() {
        val rejected = listOf(
            "<svg width=\"5000\" height=\"5000\"/>",
            "<svg viewBox=\"0 0 NaN 20\"/>",
            "<svg>" + "<g>".repeat(64) + "</g>".repeat(64) + "</svg>",
            "<svg>" + "<path/>".repeat(10_000) + "</svg>",
        )
        rejected.forEach { assertTrue(runCatching { validateWorkspaceSvg(it.toByteArray()) }.isFailure) }
        assertTrue(runCatching { validateWorkspaceSvg(ByteArray(2 * 1024 * 1024 + 1)) }.isFailure)
    }
}
