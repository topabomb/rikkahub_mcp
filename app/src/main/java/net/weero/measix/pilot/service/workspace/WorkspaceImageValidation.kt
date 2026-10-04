package net.weero.measix.pilot.service.workspace

import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_WORKSPACE_IMAGE_BYTES = 24 * 1024 * 1024

internal data class WorkspaceImageInfo(val mimeType: String, val width: Int, val height: Int)

internal fun workspaceImageGallerySaveSupported(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() !in setOf("svg", "bmp", "ico", "avif")

/** Workspace display validation is independent of model attachment format admission. */
internal fun validateWorkspaceImage(bytes: ByteArray, fileName: String): WorkspaceImageInfo {
    require(bytes.isNotEmpty() && bytes.size <= MAX_WORKSPACE_IMAGE_BYTES) { "workspace_image_byte_limit" }
    if (fileName.substringAfterLast('.', "").equals("svg", ignoreCase = true)) {
        return validateWorkspaceSvg(bytes)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "workspace_image_invalid" }
    require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) { "workspace_image_pixel_limit" }
    return WorkspaceImageInfo(requireNotNull(bounds.outMimeType), bounds.outWidth, bounds.outHeight)
}

private const val MAX_IMAGE_PIXELS = 16_000_000L
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private val SVG_ELEMENTS = setOf(
    "svg", "g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
    "text", "tspan", "title", "desc", "linearGradient", "radialGradient", "stop", "clipPath",
)
private val SVG_URL = Regex("""url\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
private val SVG_FRAGMENT = Regex("""#[A-Za-z_][A-Za-z0-9_.:-]*""")

/**
 * A bounded static SVG profile. No CSS sheets, embedded documents, external resources, entities,
 * or recursive use/paint references. Unsupported SVG remains available as source text.
 */
internal fun validateWorkspaceSvg(bytes: ByteArray): WorkspaceImageInfo {
    require(bytes.size <= 2 * 1024 * 1024) { "workspace_svg_byte_limit" }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    require(!Regex("""<!\s*(DOCTYPE|ENTITY)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        "workspace_svg_external_definition"
    }
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(text))
    }
    var definitionDepth = 0
    var elements = 0
    var dimensions: Pair<Int, Int>? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION ->
                error("workspace_svg_external_definition")
            XmlPullParser.START_TAG -> {
                require(++elements <= 10_000 && parser.depth <= 64 && parser.attributeCount <= 128) {
                    "workspace_svg_complexity_limit"
                }
                require(parser.namespace.orEmpty() in setOf("", SVG_NAMESPACE) && parser.name in SVG_ELEMENTS) {
                    "workspace_svg_element_unsupported: ${parser.name}"
                }
                if (parser.depth == 1) {
                    require(parser.name == "svg" && dimensions == null) { "workspace_svg_invalid_root" }
                    dimensions = svgDimensions(parser)
                }
                if (definitionDepth == 0 && parser.name in setOf("defs", "clipPath", "linearGradient", "radialGradient")) {
                    definitionDepth = parser.depth
                }
                repeat(parser.attributeCount) { index ->
                    val name = parser.getAttributeName(index)
                    val namespace = parser.getAttributeNamespace(index).orEmpty()
                    val value = parser.getAttributeValue(index)
                    require(value.length <= 65_536) { "workspace_svg_complexity_limit" }
                    require(namespace in setOf("", "http://www.w3.org/XML/1998/namespace")) {
                        "workspace_svg_attribute_unsupported: $name"
                    }
                    require(!name.startsWith("on", ignoreCase = true) && name !in setOf("href", "src", "base")) {
                        "workspace_svg_external_resource"
                    }
                    // Descriptive names are not resource expressions. CSS and presentation values
                    // still reject escapes/imports, including unknown presentation attributes.
                    val descriptive = name in setOf("id", "class", "aria-label", "role", "lang", "space", "version")
                    if (!descriptive) {
                        // Escaped CSS and imports are deliberately outside the static display profile.
                        require('\\' !in value && '@' !in value) { "workspace_svg_external_resource" }
                        if (value.contains("url", ignoreCase = true)) {
                            val references = SVG_URL.findAll(value).toList()
                            require(references.isNotEmpty()) { "workspace_svg_external_resource" }
                            require(references.all { SVG_FRAGMENT.matches(it.groupValues[1].trim().trim('\'', '"')) }) {
                                "workspace_svg_external_resource"
                            }
                            require(!SVG_URL.replace(value, "").contains("url", ignoreCase = true)) {
                                "workspace_svg_external_resource"
                            }
                            // Referenced paint/clip definitions cannot reference another definition.
                            require(definitionDepth == 0 && parser.name != "svg") {
                                "workspace_svg_recursive_reference"
                            }
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> if (parser.depth == definitionDepth) definitionDepth = 0
        }
        event = parser.nextToken()
    }
    val (width, height) = requireNotNull(dimensions) { "workspace_svg_invalid_root" }
    return WorkspaceImageInfo("image/svg+xml", width, height)
}

private fun svgDimensions(parser: XmlPullParser): Pair<Int, Int> {
    val viewBox = parser.getAttributeValue(null, "viewBox")?.trim()?.split(Regex("""[\s,]+"""))
        ?.map { it.toDoubleOrNull() ?: error("workspace_svg_invalid_dimensions") }
    require(viewBox == null || (viewBox.size == 4 && viewBox.all { it.isFinite() } && viewBox[2] > 0 && viewBox[3] > 0)) {
        "workspace_svg_invalid_dimensions"
    }
    fun dimension(name: String, fallback: Double?): Double {
        val raw = parser.getAttributeValue(null, name)?.trim()
        val value = if (raw == null || raw.endsWith('%')) fallback ?: 512.0
            else raw.removeSuffix("px").toDoubleOrNull() ?: error("workspace_svg_invalid_dimensions")
        require(value.isFinite() && value > 0 && value <= 16_384) { "workspace_svg_invalid_dimensions" }
        return value
    }
    val width = dimension("width", viewBox?.get(2))
    val height = dimension("height", viewBox?.get(3))
    require(width * height <= MAX_IMAGE_PIXELS) { "workspace_image_pixel_limit" }
    return kotlin.math.ceil(width).toInt() to kotlin.math.ceil(height).toInt()
}
