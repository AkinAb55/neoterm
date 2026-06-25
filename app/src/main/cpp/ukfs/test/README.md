# uKernel USB-storage FS — tests

Two layers: an automated host regression suite (no device needed) and an
on-device smoke test (run in the guest with a USB drive).

## Host regression suite

```sh
app/src/main/cpp/ukfs/test/run_host_tests.sh
```

Builds the FS engine + ukfsd for the host (x86_64/glibc) and exercises every
native layer below proot against a real FAT32 image. Exits non-zero if any
runnable test fails; tests whose tools are missing are skipped.

| Test | What it checks |
|---|---|
| build | engine + ukfsd compile/link for the host |
| vfat engine | `ukfs_test_vfat` mounts a FAT image, lists, reads, writes |
| ukfsd e2e (`ukfsd_e2e.py`) | full `io.neoterm.fs` protocol with the block backend served over `io.neoterm.block` — mount/stat/list/read, fresh-file write, mkdir, rename, chmod, truncate, unlink, rmdir, statfs |
| proot redirect (`fs_redirect_test.c`) | `uknl_fs_redirect.c` compiles `-Wall -Wextra`; getdents64 synthesis + LIST-blob parsing unit-tested |
| proot patch | `fakeid0-xattr.py` applies cleanly into a copy of proot (enter.c/exit.c/seccomp.c) |
| bionic build | engine + ukfsd cross-compile/link for aarch64 (skipped if no NDK) |

Dependencies: `gcc`, `python3`, `mkfs.vfat` + `mtools` (`mcopy`); optionally the
Android NDK at `/opt/android-ndk-*` for the aarch64 check.

## On-device smoke test

Run inside the NeoTerm proot guest, USB-storage toggle ON, a drive attached:

```sh
sh ondevice_smoke.sh [mountpoint]
```

Mounts `/dev/uksd0` through the proot VFS-redirect and checks mount, ls, stat,
fresh-file write+read, and mkdir.

## Known engine write limitations (asserted around, not bugs in the tests)

- Non-zero-offset writes (mid-file random access / append) do not yet persist —
  `ukfs_write_file_at` only lands data at offset 0.
- Re-writing a file already `read` in the same mount reads back stale data (the
  read populates a page cache the write doesn't invalidate; the engine exposes
  `ukfs_remount` to re-read fresh disk state between write sessions).

Whole-file rewrites (editors via temp+rename, `cp`) are the reliable write path.
