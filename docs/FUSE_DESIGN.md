# FUSE in proot — design notes

Goal: make **libfuse filesystems work inside the proot guest** with no root —
`sshfs`, `rclone mount`, `gocryptfs`, `ntfs-3g`/`exfat-fuse`, `mergerfs`,
AppImage's internal mount, … Today they fail because proot's fake-root can't open
`/dev/fuse` or `mount(2)` a FUSE filesystem:

```
fuse: failed to open /dev/fuse: Permission denied
```

This reuses the architecture we already built for USB storage (the proot
VFS-redirect + an app-side server) — but the server speaks the **FUSE kernel
protocol** instead of running block-FS drivers.

## How FUSE works (what we must emulate)

A FUSE filesystem is a userspace **daemon** (e.g. sshfs) plus the kernel FUSE
driver:

1. The daemon `open("/dev/fuse")` → gets a channel fd.
2. The daemon `mount("…", "/mnt", "fuse", 0, "fd=N,rootmode=…,user_id=…,…")` —
   binds that channel fd to the mountpoint.
3. From then on the **kernel** is the other end: when anything accesses `/mnt/x`,
   the kernel writes a request (`FUSE_LOOKUP`, `FUSE_GETATTR`, `FUSE_OPEN`,
   `FUSE_READ`, `FUSE_READDIR`, …) to the channel; the daemon `read()`s it,
   handles it, and `write()`s a reply; the kernel turns the reply into the
   accessor's syscall result.

So FUSE is *kernel ⇄ daemon* over one bidirectional fd, request/response framed
by `fuse_in_header` / `fuse_out_header` with a per-request `unique` id. Under
proot there is no kernel FUSE — **we play the kernel.**

## Architecture (mirrors ukfs)

```
   guest accessor (ls /mnt/x)                 guest FUSE daemon (sshfs)
            │ path syscalls under /mnt                 │ read()/write() on /dev/fuse
            ▼                                          ▼
   proot VFS-redirect  ──VFS ops──►  fused (app-side)  ──FUSE wire──►  socketpair  ──► daemon
   (uknl_fs_redirect)                "the FUSE kernel"      to the daemon's channel fd
```

| USB storage (built)            | FUSE (proposed)                         |
|--------------------------------|-----------------------------------------|
| `ukfsd` runs FS drivers        | `fused` speaks the FUSE kernel protocol |
| backing = block device/image   | backing = the guest daemon's channel    |
| redirect routes `/mnt` → ukfsd | redirect routes `/mnt` → fused          |

Three pieces, two of them straight reuse of existing machinery:

### 1. `/dev/fuse` channel — reuse the usb-serial open-redirect
`open("/dev/fuse")` is redirected exactly like `/dev/ttyUSB*` and `/dev/iio:deviceN`:
the redirect asks the app side for a channel and rewrites the open to one end of a
**real socketpair** whose other end `fused` holds. Crucial: because it's a real
kernel socketpair, the daemon's blocking `read()`/`write()` on `/dev/fuse` are
serviced by the kernel, **not** by proot's event loop — `fused` ⇄ daemon traffic
needs no syscall interception.

### 2. `mount(2)` of a FUSE fs — reuse the ukfs mount hook
`apply_emulated_mount` already gets every guest `mount`. Extend the hook: when
`fstype` starts with `fuse` (`fuse`, `fuse.sshfs`, `fuseblk`, …), parse the
`fd=N` mount option (N = the daemon's `/dev/fuse` fd → the channel), register the
mountpoint → that channel in `fused`, and send `FUSE_INIT`. (We already register
vmounts and route path ops under them; this adds a "this vmount is FUSE-backed"
kind.)

### 3. `fused` — the FUSE "kernel"
An app-side daemon (like ukfsd) that, per registered FUSE mount:
- owns the socketpair to the guest daemon;
- maintains the node table (nodeid ⇄ path, lookup counts) and the `unique`
  request counter;
- on a VFS op from the redirect (`getattr`/`lookup`/`open`/`read`/`write`/
  `readdir`/`create`/`unlink`/…), builds the matching FUSE request, writes it to
  the socketpair, reads the daemon's reply, and returns the translated result to
  the redirect — which completes the accessor's syscall, exactly as ukfsd does.
- A useful subset of opcodes (INIT, LOOKUP, FORGET, GETATTR, SETATTR, OPEN,
  READ, WRITE, RELEASE, FLUSH, OPENDIR, READDIR, RELEASEDIR, CREATE, MKDIR,
  UNLINK, RMDIR, RENAME, SYMLINK, READLINK, STATFS) covers the vast majority of
  filesystems; the rest can return `ENOSYS` (FUSE tolerates that).

## The hard part: the accessor must block while the daemon runs

This is the one genuinely new problem versus ukfs, and it must be solved up front.

- For **ukfs**, the accessor's path syscall blocks while the redirect talks to
  **ukfsd (app-side)**. No guest process needs proot during that wait → fine.
- For **FUSE**, serving the accessor's op requires the **guest daemon** to run
  (read the request, produce the reply). If the single-threaded proot tracer
  *blocks* in the accessor's syscall handler waiting for `fused`, and the daemon
  then needs proot to service *its* syscalls (sshfs does network I/O, rclone does
  HTTPS, ntfs-3g does block I/O on `/dev/uksd0` → our own redirect!), it
  **deadlocks**.

Options, in order of correctness:

1. **Async (deferred) syscall handling in proot (the right fix).** When an
   accessor op needs FUSE, *suspend* that tracee (leave it stopped, don't resume),
   return to proot's event loop, and keep servicing other tracees — including the
   daemon. When `fused` signals the reply is ready, resume the suspended tracee
   and poke its result. This is a proot-core change (a "pending syscall" queue +
   a wakeup from an fd `fused` writes when a reply lands, integrated into proot's
   `select`/event loop). Biggest effort, but the only fully general answer.
2. **Bounded synchronous with re-entrancy.** Block in the handler but pump the
   tracee event loop for *other* tracees while waiting (nested event loop). Risky
   (re-entrant proot state) but smaller than (1).
3. **MVP / restricted.** Block synchronously; works only for daemons whose FUSE
   handlers don't need the proot loop during a request (pure in-memory/compute
   FUSE fs). Too narrow for sshfs/rclone, but proves the protocol + plumbing end
   to end before investing in (1).

Recommended path: build pieces **1–3 of the architecture** (channel, mount hook,
`fused` with the opcode subset) behind the MVP scheduler (option 3) to validate
the FUSE protocol against a trivial FUSE fs (`hello` from libfuse examples), then
land option 1 (async proot scheduling) to make sshfs/rclone/ntfs-3g real.

## Phased plan

1. **`fused` skeleton + FUSE_INIT** over a socketpair; handshake with libfuse's
   `hello` example. Validate the wire format on aarch64.
2. **`/dev/fuse` open-redirect** (reuse usb-serial machinery) + **mount(fuse)
   hook** (reuse the ukfs vmount registration), so `hello` mounts.
3. **Opcode subset** in `fused` (getattr/lookup/open/read/readdir/…) wired to the
   redirect's existing path-op dispatch → `ls`/`cat` on the `hello` mount.
4. **Async proot scheduling** (the hard part) → sshfs/rclone/gocryptfs/ntfs-3g.
5. Host + proot test suites (a `hello`-fs e2e like the ukfs `run_proot_it.sh`).

## Until then (works today)

- **AppImage:** `APPIMAGE_EXTRACT_AND_RUN=1 app` or `app --appimage-extract`.
- **Remote files:** `sftp`/`scp`/`rsync`, or `rclone copy/sync` (no `mount`).
- **NTFS/exFAT images & USB:** already native via ukfs (`mount`, no FUSE) —
  `ntfs3`/`exfat` kernel drivers, not `ntfs-3g`/`fuse`.

## Notes

- `fused` should run the FUSE daemon's UID/GID mapping (`user_id`/`group_id` from
  the mount options) consistently with fake_id0.
- FUSE notifications (invalidate, poll) and `splice`/`writeback` cache can be
  declined in `FUSE_INIT` flags to keep the protocol minimal at first.
- Multi-mount: `fused` is single-mount-per-process like ukfsd, so the redirect's
  daemon pool can allocate one `fused` per FUSE mount.
