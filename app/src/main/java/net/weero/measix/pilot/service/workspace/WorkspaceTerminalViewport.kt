package net.weero.measix.pilot.service.workspace

import com.termux.view.TerminalView

/** UI-owned viewport capability; it never exposes the runtime-owned TerminalSession. */
@JvmInline
value class WorkspaceTerminalViewport internal constructor(internal val view: TerminalView)
