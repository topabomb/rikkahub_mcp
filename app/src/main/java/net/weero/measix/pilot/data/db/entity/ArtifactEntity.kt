package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 可恢复生命周期：CREATING → ACTIVE → DELETING → 行删除。 */
enum class ArtifactState {
    CREATING,
    ACTIVE,
    DELETING,
}

/**
 * 文件实体的诞生方式——这个内容最初如何进入应用生态。
 *
 * 语义是"内容的诞生来源"而非"字节的写入动作"：结构性复制（会话 fork、子助手克隆、
 * 附件入站）继承源实体的 origin；只有新内容的引入才产生新值。
 *
 * - [USER]      用户主动引入：聊天上传、设置助手背景/头像时挑选的设备或外部图片
 * - [GENERATED] 生成媒体副本：文生图/图片编辑产物在聊天域的副本及其派生
 * - [SYSTEM]    其余系统创建：模型输出落盘、工具读取产物、MCP 图片资源
 */
enum class ArtifactOrigin {
    USER,
    GENERATED,
    SYSTEM,
}

/**
 * 受引用文件实体的注册行——聊天域与设置域的 artifact 注册表。
 *
 * 一行 = 一个被消息树或助手设置引用的本地文件（含生命周期状态与引用投影入口）。
 * 相册域的生成媒体 canonical 不在本表（由 gen_media 编目）；无引用需求的落盘
 * 文件（tool_outputs）也不在本表。
 */
@Entity(
    tableName = "artifact",
    indices = [
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"]),
        Index(value = ["state"]),
    ]
)
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("folder")
    val folder: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("state", defaultValue = "ACTIVE")
    val state: String = ArtifactState.ACTIVE.name,
    /** CREATING 的 staging 文件名；ACTIVE 后清空。 */
    @ColumnInfo("payload_token")
    val payloadToken: String? = null,
    /** 诞生方式（[ArtifactOrigin.name]），写入时一次确定后不变。 */
    @ColumnInfo("origin", defaultValue = "USER")
    val origin: String = ArtifactOrigin.USER.name,
)
