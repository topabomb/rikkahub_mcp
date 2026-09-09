# Terminal source

The `com.termux.view` and `com.termux.terminal` Java sources and selection-handle resources come from
[termux-app v0.118.0](https://github.com/termux/termux-app/tree/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-view),
commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`.
Both compile in the existing Workspace module. Their binary dependencies are removed, so each class has
one implementation. The emulator only changes `TerminalSession` from final to extensible. The application
subclass delegates the final byte-write boundary to its PTY owner; upstream encoding and emulation remain
on Main. JNI uses this module's existing `termux_pty.cpp`, not a duplicate upstream native library.
That adapter prepends the executable as argv[0]; callers supply only its arguments. Startup errors
throw IOException, interrupted waits retry, and signal exits return the negative signal number.

Local changes add permanent viewport retirement, guards for delayed input/selection/scroll callbacks,
and a host action dispatcher. The application runtime owns authorization and ordered input delivery;
the view does not interpret enterprise identity or policy. Resource references use the Workspace namespace.

Upstream declares GPL-3.0-only with an Apache-2.0 exception for code originating in Terminal Emulator for Android.
The original [license declaration](licenses/termux-LICENSE.md) and source notices are retained.
