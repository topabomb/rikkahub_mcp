# PRoot Binary Provenance

## Version

- **PRoot**: `v5.1.107.92` (commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`)
- **Termux Packages recipe**: commit `08b49b3ce00b1e14a3a0365200f30e50f8dfafe1`

## Source

- Source: [Termux/PRoot `v5.1.107.92`](https://github.com/termux/proot/tree/v5.1.107.92)
- Fixed recipe: [Termux Packages recipe](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/proot/build.sh)
- Upstream app binary commit: `f4508dfac2255cf83e75859a8fe37dd7da6778a3`

The checked-in `arm64-v8a` and `x86_64` `libproot_exec.so` files are byte-identical to that
upstream app commit. This establishes the exact binary import path. It does not prove that the
pinned Termux recipe reproduces those bytes; the local rebuild has not been run.

## Dependencies

- `libandroid-shmem 0.7` (SHA-256: `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`)
- `libtalloc 2.4.3` (SHA-256: `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`)

## Build Configuration

- NDK: r29
- Android API: 24
- `PROOT_WITH_LIBANDROID_SHMEM=true`
- Static linking (no runtime dependency on `libandroid-shmem.so` or `libtalloc.so`)

`workspace/tools/build-proot.sh` creates an owned temporary directory for each rebuild. Optional
`PROOT_BUILD_WORK_DIR` names only its parent directory; the script creates a unique
`proot-build.XXXXXXXX` child and never recursively removes the caller-provided parent. Set
`PROOT_KEEP_BUILD_WORK_DIR=true` to retain that owned child for inspection.

The script is a pinned rebuild candidate and a fail-closed verification command. It refuses to
install output whose hashes differ from the recorded artifacts; it never updates the manifest or
silently accepts toolchain drift. Its current status is **not run locally**, so these files must not
be described as bit-for-bit reproducible from the recipe yet.

## Artifacts

See `proot-lock.json` for the machine-readable manifest with SHA-256, ELF machine, interpreter, Android API floor, and allowed `DT_NEEDED` for each artifact.

## Licenses

- **PRoot**: GPL-2.0-or-later
- **libtalloc**: LGPL-3.0-or-later
- **libandroid-shmem**: BSD-3-Clause

The source URLs and hashes above are provenance records, not a completed distribution bundle. A
release containing these binaries must separately assemble and verify all applicable materials:

- PRoot GPL corresponding source for the distributed binary, including the exact patches and build/install scripts, plus the GPL text or a compliant source offer;
- the Termux recipe and patch set used for the binary;
- the complete `libandroid-shmem` BSD notice reproduced in the distribution materials;
- for statically linked libtalloc, Corresponding Application Code/object material that permits relinking with a modified libtalloc and any applicable installation information, or another compliant linkage model.

That release compliance gate is currently **not complete**. The exact obligations depend on the
actual release distribution method and must be checked as part of release preparation.

## Loader Companion

`libproot_loader.so` has not changed in this upgrade but must be verified alongside `libproot_exec.so` as an exec/loader pair. Both entries continue to use `PROOT_LOADER` and preserve `--root-id --link2symlink --kill-on-exit`, `-k 4.14.0`, existing bind mounts, `PWD`, and explicit environment. `PROOT_NO_SECCOMP=1` must not be set to bypass faults.

## Device Verification Status

**PARTIAL X86_64 SMOKE VERIFICATION ONLY.** The packaged x86_64 Debug artifact was installed on an Android 17/API 37 Pixel Fold AVD. The packaged `libproot_exec.so` reported `5.1.107.92` with process-vm and seccomp accelerators; with the packaged loader and production root-id/link2symlink/kill-on-exit environment it successfully executed `id`, `pwd`, a host bind plus `cat`, kernel-release emulation (`4.14.0`), and 1,000 lines of stdout. This proves basic packaged-binary execution, not a Rootfs or PTY acceptance matrix.

Arm64 Android 14/15/16 real-device verification and the complete x86_64 Rootfs/PTY matrix remain outstanding. The following must still pass before declaring this upgrade fully verified:

- cwd/mkdir/stat/rename
- symlink/hardlink
- `/workspace`/`/skills`/`/upload` mount
- DNS/netlink
- `ipcmk`/`ipcs` or equivalent SysV shared-memory fixture
- Long output, timeout/cancel/kill-on-exit
- Two concurrent PTY sessions
