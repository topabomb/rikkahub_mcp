package net.weero.measix.pilot.data.files

/** Stable on-disk directory names shared by artifacts, backup, workspace and skill storage. */
object FileFolders {
    const val UPLOAD = "upload"
    const val IMAGES = "images"
    const val SKILLS = "skills"
    const val FONTS = "fonts"

    /** 归档 Tool Result 的受管 Artifact 目录：只经 ToolOutputStore scoped read 访问。 */
    const val TOOL_OUTPUTS = "tool_outputs"
}
