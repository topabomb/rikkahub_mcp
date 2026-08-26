#!/usr/bin/env bash
set -euo pipefail

# Rebuild the pinned PRoot package through the pinned Termux packaging toolchain. The script
# deliberately verifies the checked-in artifact hashes: a recipe or compiler drift is a failure,
# not an implicit binary upgrade.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
keep_work_dir="${PROOT_KEEP_BUILD_WORK_DIR:-false}"
termux_commit="08b49b3ce00b1e14a3a0365200f30e50f8dfafe1"
work_dir_owned=false

if [[ -n "${PROOT_BUILD_WORK_DIR:-}" ]]; then
  work_parent="$PROOT_BUILD_WORK_DIR"
  mkdir -p -- "$work_parent"
  work_dir="$(mktemp -d "$work_parent/proot-build.XXXXXXXX")"
else
  work_dir="$(mktemp -d)"
fi
work_dir_owned=true

cleanup() {
  if [[ "$keep_work_dir" != "true" && "$work_dir_owned" == "true" && -n "$work_dir" && "$work_dir" != "/" ]]; then
    rm -rf -- "$work_dir"
  fi
}
trap cleanup EXIT

command -v git >/dev/null
command -v sha256sum >/dev/null
command -v dpkg-deb >/dev/null

git clone --filter=blob:none https://github.com/termux/termux-packages.git "$work_dir/termux-packages"
git -C "$work_dir/termux-packages" checkout --detach "$termux_commit"
grep -F 'TERMUX_PKG_VERSION="5.1.107.92"' "$work_dir/termux-packages/packages/proot/build.sh" >/dev/null
grep -F 'TERMUX_PKG_SHA256=29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135' \
  "$work_dir/termux-packages/packages/proot/build.sh" >/dev/null
grep -F ': "${TERMUX_NDK_VERSION_NUM:="29"}"' "$work_dir/termux-packages/scripts/properties.sh" >/dev/null

build_abi() {
  local termux_arch="$1"
  local android_abi="$2"
  local expected_exec="$3"
  local expected_loader="$4"
  local package_root="$work_dir/package-$android_abi"

  (
    cd "$work_dir/termux-packages"
    TERMUX_NDK_VERSION_NUM=29 TERMUX_NDK_REVISION='' TERMUX_PKG_API_LEVEL=24 \
      ./build-package.sh -a "$termux_arch" proot
  )
  local deb
  deb="$(find "$work_dir/termux-packages/output" -maxdepth 1 -name 'proot_*.deb' -print -quit)"
  test -n "$deb"
  rm -rf -- "$package_root"
  mkdir -p "$package_root"
  dpkg-deb -x "$deb" "$package_root"

  local exec_src loader_src
  exec_src="$(find "$package_root" -type f -path '*/bin/proot' -print -quit)"
  loader_src="$(find "$package_root" -type f \( -name 'loader' -o -name 'loader-m32' \) | head -n 1)"
  test -n "$exec_src"
  test -n "$loader_src"
  printf '%s  %s\n' "$expected_exec" "$exec_src" | sha256sum --check --status
  printf '%s  %s\n' "$expected_loader" "$loader_src" | sha256sum --check --status
  install -m 0755 "$exec_src" "$repo_root/workspace/src/main/jniLibs/$android_abi/libproot_exec.so"
  install -m 0755 "$loader_src" "$repo_root/workspace/src/main/jniLibs/$android_abi/libproot_loader.so"
  rm -f -- "$work_dir/termux-packages/output"/proot_*.deb
}

build_abi aarch64 arm64-v8a \
  06c4b8d620c960e2ae873786e81dc04d797b5af47967819b5ec4cbb7635590c9 \
  44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04
build_abi x86_64 x86_64 \
  29ad3f63f5a1a030d0db8a58c6f14124386ce4a2fc31c24003b8fac3febe5a02 \
  914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3
