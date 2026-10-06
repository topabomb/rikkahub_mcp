package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Client-only exception identity and cause detail, sanitized by the execution boundary. */
@Serializable
data class ToolClientDiagnostic(val detail: String)
