#!/usr/bin/env python3
"""Build the locked Android PRoot sources; candidate output never updates repository artifacts."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(url, expected, cache, name):
    target = cache / name
    if not target.exists():
        partial = target.with_suffix(target.suffix + ".partial")
        urllib.request.urlretrieve(url, partial)
        if sha256(partial) != expected:
            raise RuntimeError("Source checksum mismatch: " + url)
        partial.replace(target)
    if sha256(target) != expected:
        raise RuntimeError("Cached source checksum mismatch: " + str(target))
    return target


def run(args, cwd, env):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True, help="Parent of an owned build directory")
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), help="Build only one ABI")
    parser.add_argument("--candidate", action="store_true", help="Emit reviewable new hashes without changing the manifest")
    parser.add_argument("--keep-work-dir", action="store_true", help="Retain source, object and dependency material for relinking")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    lock = json.loads((repo / "workspace/proot-lock.json").read_text())
    ndk = args.ndk.resolve()
    revision = "Pkg.Revision = " + lock["build"]["ndkRevision"]
    if revision not in (ndk / "source.properties").read_text():
        raise RuntimeError("Wrong NDK revision; expected " + revision)
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    if not (toolchain / "clang").is_file():
        raise RuntimeError("The Linux x86_64 NDK toolchain is required")
    for path in (args.work_dir, args.cache_dir, args.output_dir):
        path.mkdir(parents=True, exist_ok=True)
    cache = args.cache_dir.resolve()
    output = args.output_dir.resolve()
    if output == repo or repo in output.parents:
        raise RuntimeError("Build output must be outside the repository")
    work = Path(tempfile.mkdtemp(prefix="proot-build.", dir=args.work_dir.resolve()))
    print("Build directory:", work, flush=True)
    try:
        env = os.environ.copy()
        env.update(TMPDIR=str(work / "tmp"), XDG_CACHE_HOME=str(work / "cache"),
                   PYTHONDONTWRITEBYTECODE="1", LC_ALL="C", TZ="UTC", SOURCE_DATE_EPOCH="0")
        Path(env["TMPDIR"]).mkdir()
        proot = lock["proot"]
        archives = {
            "proot": fetch(proot["sourceZipUrl"], proot["sourceZipSha256"], cache, "proot.zip"),
        }
        for name, config in lock["dependencies"].items():
            archives[name] = fetch(config["sourceUrl"], config["sha256"], cache, name + ".tar.gz")
        patch = repo / proot["patch"]["repoPath"]
        if sha256(patch) != proot["patch"]["sha256"]:
            raise RuntimeError("Local PRoot patch checksum mismatch")
        frozen_patch = work / patch.name
        shutil.copyfile(patch, frozen_patch)
        results = []
        for abi, target in (("x86_64", "x86_64-linux-android"), ("arm64-v8a", "aarch64-linux-android")):
            if args.abi and args.abi != abi:
                continue
            root = work / abi
            root.mkdir()
            with zipfile.ZipFile(archives["proot"]) as archive:
                archive.extractall(root)
            for name in ("libtalloc", "libandroidShmem"):
                with tarfile.open(archives[name]) as archive:
                    archive.extractall(root, filter="data")
            source = root / ("proot-" + proot["tag"].removeprefix("v"))
            talloc = root / ("talloc-" + lock["dependencies"]["libtalloc"]["version"])
            shmem = root / ("libandroid-shmem-" + lock["dependencies"]["libandroidShmem"]["version"])
            prefix = root / "prefix"
            (prefix / "lib").mkdir(parents=True)
            (prefix / "include/sys").mkdir(parents=True)
            cc = str(toolchain / (target + str(lock["build"]["api"]) + "-clang"))
            ar = str(toolchain / "llvm-ar")
            flags = "-O2 -fPIC -ffile-prefix-map=" + str(work) + "=/build"
            build_env = dict(env, CC=cc, AR=ar, RANLIB=str(toolchain / "llvm-ranlib"),
                             CFLAGS=flags, LDFLAGS="-Wl,--as-needed,-z,max-page-size=16384")
            answers = (repo / "workspace/tools/talloc-cross-answers.txt").read_text()
            (talloc / "cross-answers.txt").write_text(answers)
            run(["sh", "configure", "--prefix=" + str(prefix), "--disable-rpath", "--disable-python",
                 "--cross-compile", "--cross-answers=cross-answers.txt"], talloc, build_env)
            run(["make", "-j2"], talloc, build_env)
            objects = sorted((talloc / "bin/default").glob("talloc*.o"))
            if not objects:
                raise RuntimeError("Missing talloc objects")
            run([ar, "rcsD", prefix / "lib/libtalloc.a", *objects], talloc, build_env)
            shutil.copyfile(talloc / "talloc.h", prefix / "include/talloc.h")
            # Preserve the pinned Termux paths.h definition without modifying the NDK.
            run([cc, *flags.split(), "-include", "fcntl.h",
                 '-D_PATH_TMP="/data/data/com.termux/files/usr/tmp/"',
                 "-std=c11", "-c", "shmem.c", "-o", "shmem.o"], shmem, build_env)
            run([ar, "rcsD", prefix / "lib/libandroid-shmem.a", "shmem.o"], shmem, build_env)
            shutil.copyfile(shmem / "shm.h", prefix / "include/sys/shm.h")
            run(["patch", "--batch", "--fuzz=0", "-p1", "-i", frozen_patch], source, build_env)
            # The unbundled loader remains selected by PROOT_LOADER at runtime.
            build_env["PROOT_UNBUNDLE_LOADER"] = "/data/data/com.termux/files/usr/libexec/proot"
            build_env["CPPFLAGS"] = ('-DARG_MAX=131072 -DVERSION=\\"' + proot["tag"].removeprefix("v") +
                                     '\\" -I' + str(prefix / "include"))
            build_env["LDFLAGS"] += " -L" + str(prefix / "lib") + " -llog"
            run(["make", "-C", "src", "-j2", "GIT=false", "PROOT_WITH_LIBANDROID_SHMEM=true",
                 "STRIP=" + str(toolchain / "llvm-strip"), "OBJCOPY=" + str(toolchain / "llvm-objcopy"),
                 "OBJDUMP=" + str(toolchain / "llvm-objdump")], source, build_env)
            pair = output / abi
            pair.mkdir(exist_ok=True)
            for filename, built in (("libproot_exec.so", "src/proot"), ("libproot_loader.so", "src/loader/loader")):
                artifact = pair / filename
                shutil.copyfile(source / built, artifact)
                run([toolchain / "llvm-strip", "--strip-unneeded", artifact], source, build_env)
                results.append({"abi": abi, "name": filename, "sha256": sha256(artifact)})
        (output / "sha256.json").write_text(json.dumps(results, indent=2) + "\n")
        if not args.candidate:
            for result in results:
                expected = next(item for item in lock["artifacts"]
                                if item["abi"] == result["abi"] and Path(item["repoPath"]).name == result["name"])
                if expected["sha256"] != result["sha256"]:
                    raise RuntimeError("Artifact drift: " + result["abi"] + "/" + result["name"])
        print(json.dumps(results, indent=2), flush=True)
    finally:
        if not args.keep_work_dir:
            shutil.rmtree(work)


if __name__ == "__main__":
    main()
