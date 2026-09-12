# PRoot Binary Provenance

## Sources and local patch

- PRoot: `v5.1.107.92`, commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`.
- Source archive and dependency checksums: `workspace/proot-lock.json`.
- Termux recipe reference: commit `08b49b3ce00b1e14a3a0365200f30e50f8dfafe1`,
  [PRoot](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/proot/build.sh)
  and [libtalloc](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/libtalloc/build.sh).
- Local termination patch: `workspace/tools/patches/proot-termination.patch`, also hash-locked.

The earlier imports came from upstream app commit `f4508dfac2255cf83e75859a8fe37dd7da6778a3`.
The shipped executables and loader companions are now local source builds; they are not claimed
byte-identical to those imports. The direct build uses the same fixed source versions, Android API
floor and static dependencies, with the Termux talloc cross answers. It does not run the entire
Termux packaging system.

## Process termination

Android `Process.destroyForcibly()` sends SIGTERM. The upstream PRoot ignores SIGTERM and only
cleans its tracees for SIGQUIT. The local patch makes both signals request termination through
PRoot's existing tracee registry and waitpid loop. PRoot blocks these signals before creating the
first tracee; the guest restores the inherited signal mask, while the tracer restores it only after
registering the child and installing its handlers. Early cancellation therefore remains pending.

The termination request remains set while the loop drains events, so a child first discovered
through a pending fork/clone event is also killed and reaped. Already reaped PIDs are not signaled.
Normal termination handlers avoid stdio. The shell adapter retains ProcessBuilder and separate
stdin/stdout/stderr; WorkspacePtySession sends SIGTERM to its existing PRoot PID. PRoot remains
the only owner of traced guest processes. No signal-forwarding shell, PID reflection, timer,
seccomp override or replacement process supervisor is used.

## Rebuild and verification

`workspace/tools/build-proot.py` requires Python 3.12+, make, patch, and the Linux x86_64 Android
NDK r29 (`29.0.14206865`). The NDK revision, API 24, source/dependency hashes, patch hash and final
artifact hashes are recorded in `workspace/proot-lock.json`. Native ELF segments support 16 KiB
host pages; guest Rootfs compatibility remains a separate admission check.

```sh
python3 workspace/tools/build-proot.py \
  --ndk /path/to/android-ndk-r29 \
  --work-dir /path/to/build-parent \
  --cache-dir /path/to/source-cache \
  --output-dir /path/to/verified-output
```

All three directories are explicit. The script creates and owns a unique child of `--work-dir`,
places temporary files and build caches there, and removes only that child. Output must be outside
the repository. Source checksums and compiler revision are checked before building. The default
mode rejects output differing from the manifest and never installs artifacts into the repository.
`--candidate` emits proposed hashes for an intentional native update, without rewriting the
manifest; it is not a verification pass. `--keep-work-dir` retains exact patched sources, dependency
objects and configuration for inspection and relinking.

Builds use deterministic archives, a fixed source date and normalized source paths. On 2026-09-12,
both ABI exec/loader pairs were reproduced byte-for-byte in independent source/build directories
using the finalized compiler and linker flags. All four hashes match the shipped files and manifest.
Both rebuilt loader companions also match their earlier imported bytes. Host builds and ELF checks
do not replace Android device verification.

## Runtime contract and device acceptance

Both ABIs retain the shared ProotLaunchSpec: root-id, link2symlink, kill-on-exit, `-k 4.14.0`, existing
bind mounts, PWD and explicit environment. `PROOT_LOADER` selects the separately built loader.
`--kill-on-exit` terminates remaining tracees when the initial guest exits; terminating the tracer
with SIGKILL bypasses its cleanup and is not the workspace shutdown protocol.

The x86_64 pair passed actual Android API 36 / 4 KiB Rootfs and PTY acceptance: shell/file operations, distinct
stdin/stdout/stderr, long output, bounded timeout and cancellation, cancellation during startup,
background process disappearance, and two independently closed PTYs. Tests on x86_64 AVDs do not
prove arm64 physical-device interoperability. The 16 KiB host rejection of an incompatible 4 KiB
guest archive is separate from successful execution of compatible guests.

## Distribution materials

- PRoot: GPL-2.0-or-later; retain the corresponding source, exact local patch and build scripts,
  including the GPL text or a compliant source offer.
- libtalloc 2.4.3: LGPL-3.0-or-later, statically linked. A distribution must provide relinkable
  application/object material and applicable installation information, or another compliant
  linkage model. Keep the build directory when preparing those materials.
- libandroid-shmem 0.7: BSD-3-Clause. Its complete notice is in `THIRD_PARTY_NOTICES.md`.

The source URLs, reproducible build and hash manifest establish provenance. They do not alone
complete the separate release distribution-materials gate.
