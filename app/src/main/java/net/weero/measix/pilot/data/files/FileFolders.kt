package net.weero.measix.pilot.data.files

/** Stable on-disk directory names shared by artifacts, backup, workspace and skill storage. */
object FileFolders {
    const val UPLOAD = "upload"
    const val IMAGES = "images"
    const val SKILLS = "skills"
    const val FONTS = "fonts"

    /** Tool outputs are ephemeral model context and are not managed artifacts. */
    const val TOOL_OUTPUTS = "tool_outputs"
}
