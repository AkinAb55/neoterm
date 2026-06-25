/* NeoTerm USB-filesystem redirect — injected verbatim into proot's
 * syscall/enter.c (just before translate_syscall_enter) by fakeid0-xattr.py.
 *
 * When the guest does mount("/dev/uksd0", "/mnt/usb", "vfat", ...), the real
 * Linux FS driver lives in the ukfsd daemon (io.neoterm.fs), not the host
 * kernel. proot can't issue a real mount, so instead it: (1) tells ukfsd to
 * MOUNT the block device, (2) records the guest mount point in a vmount table,
 * and (3) proxies every path syscall under that mount point to ukfsd, faking
 * each syscall result with set_sysnum(PR_void) + poke_reg(SYSARG_RESULT).
 *
 * This file is NOT compiled on its own (it has no includes / uses enter.c's
 * scope: Tracee, peek_reg/poke_reg, set_sysnum, read_string/write_data, the
 * PR_* sysnums, SYSARG_*, PATH_MAX, and the block proxy's uksd_wn/rn/rl socket
 * helpers, which are injected just above it). Task #2 scope: mount + client +
 * vmount table + stat. The open/read/getdents/write paths are added later.
 */

/* ---- gating: UK_FS=1 (set by ProotManager alongside UK_BLOCK) ---- */
static int g_uk_fs = -1;
static int uk_fs_on(void)
{
	if (g_uk_fs < 0) { const char *e = getenv("UK_FS"); g_uk_fs = (e && *e && *e != '0') ? 1 : 0; }
	return g_uk_fs;
}

/* ---- persistent io.neoterm.fs connection (ukfsd holds one mount per conn) ---- */
static int  g_ukfs_sock = -1;
static char g_vmount[PATH_MAX];      /* guest mount point, e.g. "/mnt/usb" */
static int  g_vmounted = 0;

static int ukfs_conn(void)
{
	if (g_ukfs_sock >= 0) return g_ukfs_sock;
	int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (s < 0) return -1;
	struct timeval tv = { .tv_sec = 30, .tv_usec = 0 };
	setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
	setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
	struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX;
	const char *name = "io.neoterm.fs";
	a.sun_path[0] = '\0'; strncpy(a.sun_path + 1, name, sizeof(a.sun_path) - 2);
	socklen_t len = sizeof(a.sun_family) + 1 + strlen(name);
	if (connect(s, (struct sockaddr *) &a, len) < 0) { close(s); return -1; }
	g_ukfs_sock = s; return s;
}
/* The mount lives in the connection; dropping it loses the mount. */
static void ukfs_sdrop(void) { if (g_ukfs_sock >= 0) { close(g_ukfs_sock); g_ukfs_sock = -1; } g_vmounted = 0; }

/* guest sees the block device at .../uksd0 (same convention as the block proxy) */
static int ukfs_src_is_dev(const char *p)
{
	size_t n = strlen(p);
	return n >= 6 && strcmp(p + n - 6, "/uksd0") == 0;
}

/* Map a guest path to its vmount-relative form ("/mnt/usb/a/b" -> "/a/b",
 * the mount point itself -> "/"). Returns 1 iff the path is under the vmount. */
static int ukfs_rel(const char *guest, char *out, size_t osz)
{
	if (!g_vmounted) return 0;
	size_t ml = strlen(g_vmount);
	if (strncmp(guest, g_vmount, ml) != 0) return 0;
	char c = guest[ml];
	if (c != '\0' && c != '/') return 0;            /* "/mnt/usb" vs "/mnt/usbX" */
	snprintf(out, osz, "%s", guest[ml] ? guest + ml : "/");
	return 1;
}

/* Tell ukfsd to MOUNT the block device and remember the guest mount point. */
static int ukfs_mount_dev(const char *target)
{
	int s = ukfs_conn(); if (s < 0) return -1;
	if (uksd_wn(s, "MOUNT auto uksd0\n", 16) < 0) { ukfs_sdrop(); return -1; }
	char line[64];
	if (uksd_rl(s, line, sizeof line) < 0) { ukfs_sdrop(); return -1; }
	if (line[0] != 'O' || line[1] != 'K') return -1;
	snprintf(g_vmount, sizeof g_vmount, "%s", target);
	g_vmounted = 1;
	return 0;
}

/* STAT one path. Returns 0 (filled), -2 (ENOENT from ukfsd), -1 (socket error). */
static int ukfs_query_stat(const char *rel, unsigned *mode, unsigned *uid, unsigned *gid,
	long *size, unsigned long *ino, long *mtime, long *atime, unsigned *nlink,
	unsigned long *rdev, unsigned long *blocks)
{
	int s = ukfs_conn(); if (s < 0) return -1;
	char req[PATH_MAX + 16];
	int n = snprintf(req, sizeof req, "STAT %s\n", rel);
	if (uksd_wn(s, req, n) < 0) { ukfs_sdrop(); return -1; }
	char line[256];
	if (uksd_rl(s, line, sizeof line) < 0) { ukfs_sdrop(); return -1; }
	if (sscanf(line, "OK %u %u %u %ld %lu %ld %ld %u %lu %lu",
	           mode, uid, gid, size, ino, mtime, atime, nlink, rdev, blocks) != 10)
		return -2;
	return 0;
}

/* aarch64 struct stat (128 B) — offsets match the block proxy's uksd_put_stat,
 * plus uid/gid (24/28) and a/m/ctime seconds (72/88/104). */
static void ukfs_put_stat(Tracee *tracee, word_t addr, unsigned mode, unsigned uid, unsigned gid,
	long size, unsigned long ino, long mtime, long atime, unsigned nlink,
	unsigned long rdev, unsigned long blocks)
{
	unsigned char st[128]; memset(st, 0, sizeof st);
	unsigned long st_ino = ino, st_rdev = rdev;
	unsigned int  st_mode = mode, st_nlink = nlink ? nlink : 1, st_uid = uid, st_gid = gid;
	long st_size = size, st_blocks = (long) blocks; int st_blksize = 512;
	long at_s = atime, mt_s = mtime, ct_s = mtime;
	memcpy(st + 8,  &st_ino,     8);
	memcpy(st + 16, &st_mode,    4);
	memcpy(st + 20, &st_nlink,   4);
	memcpy(st + 24, &st_uid,     4);
	memcpy(st + 28, &st_gid,     4);
	memcpy(st + 32, &st_rdev,    8);
	memcpy(st + 48, &st_size,    8);
	memcpy(st + 56, &st_blksize, 4);
	memcpy(st + 64, &st_blocks,  8);
	memcpy(st + 72, &at_s,       8);   /* st_atime.tv_sec  */
	memcpy(st + 88, &mt_s,       8);   /* st_mtime.tv_sec  */
	memcpy(st + 104, &ct_s,      8);   /* st_ctime.tv_sec  */
	write_data(tracee, addr, st, sizeof st);
}

/* struct statx (256 B) — kernel uapi layout. */
static void ukfs_put_statx(Tracee *tracee, word_t addr, unsigned mode, unsigned uid, unsigned gid,
	long size, unsigned long ino, long mtime, long atime, unsigned nlink,
	unsigned long rdev, unsigned long blocks)
{
	unsigned char sx[256]; memset(sx, 0, sizeof sx);
	unsigned int  stx_mask = 0x000007ffU;   /* STATX_BASIC_STATS */
	unsigned int  stx_blksize = 512, stx_nlink = nlink ? nlink : 1, stx_uid = uid, stx_gid = gid;
	unsigned short stx_mode = (unsigned short) mode;
	unsigned long long stx_ino = ino, stx_size = (unsigned long long) size,
	                   stx_blocks = (unsigned long long) blocks;
	long long at_s = atime, ct_s = mtime, mt_s = mtime;
	unsigned int rdev_major = (unsigned)((rdev >> 8) & 0xfff), rdev_minor = (unsigned)(rdev & 0xff);
	memcpy(sx + 0,   &stx_mask,    4);
	memcpy(sx + 4,   &stx_blksize, 4);
	memcpy(sx + 16,  &stx_nlink,   4);
	memcpy(sx + 20,  &stx_uid,     4);
	memcpy(sx + 24,  &stx_gid,     4);
	memcpy(sx + 28,  &stx_mode,    2);
	memcpy(sx + 32,  &stx_ino,     8);
	memcpy(sx + 40,  &stx_size,    8);
	memcpy(sx + 48,  &stx_blocks,  8);
	memcpy(sx + 64,  &at_s,        8);   /* stx_atime.tv_sec */
	memcpy(sx + 96,  &ct_s,        8);   /* stx_ctime.tv_sec */
	memcpy(sx + 112, &mt_s,        8);   /* stx_mtime.tv_sec */
	memcpy(sx + 128, &rdev_major,  4);
	memcpy(sx + 132, &rdev_minor,  4);
	write_data(tracee, addr, sx, sizeof sx);
}

/* Entry point, called from translate_syscall_enter before the syscall switch.
 * Returns true if the syscall was fully emulated (PR_void'd + result poked). */
static bool uknl_fs_dispatch(Tracee *tracee, word_t nr)
{
	if (!uk_fs_on()) return false;

	/* mount(2): only a /dev/uksd0 source is ours; everything else falls through
	 * to proot's normal mount emulation. */
	if (nr == PR_mount) {
		word_t sa = peek_reg(tracee, CURRENT, SYSARG_1);
		char src[PATH_MAX];
		if (!sa || read_string(tracee, src, sa, sizeof src) <= 0) return false;
		if (!ukfs_src_is_dev(src)) return false;
		word_t ta = peek_reg(tracee, CURRENT, SYSARG_2);
		char tgt[PATH_MAX];
		if (!ta || read_string(tracee, tgt, ta, sizeof tgt) <= 0) return false;
		int rc = ukfs_mount_dev(tgt);
		poke_reg(tracee, SYSARG_RESULT, (word_t)(long)(rc == 0 ? 0 : -EIO));
		set_sysnum(tracee, PR_void);
		return true;
	}

	if (!g_vmounted) return false;

	/* stat family on a path under the vmount: answer from ukfsd. */
	if (nr == PR_newfstatat || nr == PR_fstatat64 || nr == PR_statx) {
		word_t pa = peek_reg(tracee, CURRENT, SYSARG_2);
		char gp[PATH_MAX];
		if (!pa || read_string(tracee, gp, pa, sizeof gp) <= 0 || gp[0] == '\0') return false;
		char rel[PATH_MAX];
		if (!ukfs_rel(gp, rel, sizeof rel)) return false;
		unsigned mode, uid, gid, nlink; long size, mt, at; unsigned long ino, rdev, blocks;
		int q = ukfs_query_stat(rel, &mode, &uid, &gid, &size, &ino, &mt, &at, &nlink, &rdev, &blocks);
		if (q == -1) return false;                 /* socket down: let host try */
		if (q == -2) {
			poke_reg(tracee, SYSARG_RESULT, (word_t)(long) - ENOENT);
			set_sysnum(tracee, PR_void); return true;
		}
		if (nr == PR_statx)
			ukfs_put_statx(tracee, peek_reg(tracee, CURRENT, SYSARG_5),
			               mode, uid, gid, size, ino, mt, at, nlink, rdev, blocks);
		else
			ukfs_put_stat(tracee, peek_reg(tracee, CURRENT, SYSARG_3),
			              mode, uid, gid, size, ino, mt, at, nlink, rdev, blocks);
		poke_reg(tracee, SYSARG_RESULT, 0);
		set_sysnum(tracee, PR_void);
		return true;
	}

	return false;
}
