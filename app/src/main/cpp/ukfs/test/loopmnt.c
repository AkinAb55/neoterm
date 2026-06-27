/* Deterministic loop-mount helper for the proot integration test. Drives the
 * kernel loop ioctls DIRECTLY on the bound /dev/loop* markers (so it never scans
 * sysfs / discovers the host's real loop devices the way losetup does), then
 * mounts the resulting loop partition. Exercises the redirect's loop emulation.
 *
 *   loopmnt <image> <mountpoint> <part> [offset]
 *     part>0  : mount /dev/loopN p<part> (partition of the image)
 *     part==0 : mount the whole loop at <offset> (mount -o loop,offset= style)
 *
 * Exit 0 on a successful mount.
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mount.h>

#define LOOP_CTL_GET_FREE 0x4C82
#define LOOP_CONFIGURE    0x4C0A
#define LO_FLAGS_PARTSCAN 8

/* struct loop_config { __u32 fd; __u32 block_size; struct loop_info64 info; __u64 resv[8]; } */
struct lo_info64 {
	unsigned long long lo_device, lo_inode, lo_rdevice, lo_offset, lo_sizelimit;
	unsigned int lo_number, lo_encrypt_type, lo_encrypt_key_size, lo_flags;
	unsigned char lo_file_name[64], lo_crypt_name[64], lo_encrypt_key[32];
	unsigned long long lo_init[2];
};
struct lo_config { unsigned int fd, block_size; struct lo_info64 info; unsigned long long resv[8]; };

int main(int argc, char **argv)
{
	if (argc < 4) { fprintf(stderr, "usage: loopmnt img mp part [offset]\n"); return 2; }
	const char *img = argv[1], *mp = argv[2];
	int part = atoi(argv[3]);
	long long offset = (argc > 4) ? atoll(argv[4]) : 0;

	int ctl = open("/dev/loop-control", O_RDWR);
	if (ctl < 0) { perror("open /dev/loop-control"); return 1; }
	int n = ioctl(ctl, LOOP_CTL_GET_FREE);
	printf("  GET_FREE=%d\n", n);
	if (n < 0) { perror("LOOP_CTL_GET_FREE"); return 1; }
	close(ctl);

	char ldev[64]; snprintf(ldev, sizeof ldev, "/dev/loop%d", n);
	int lfd = open(ldev, O_RDWR);
	if (lfd < 0) { perror("open loopN"); return 1; }
	int ifd = open(img, O_RDWR);
	if (ifd < 0) { perror("open image"); return 1; }

	struct lo_config cfg; memset(&cfg, 0, sizeof cfg);
	cfg.fd = (unsigned) ifd;
	cfg.info.lo_offset = (unsigned long long) offset;
	if (part > 0) cfg.info.lo_flags = LO_FLAGS_PARTSCAN;
	if (ioctl(lfd, LOOP_CONFIGURE, &cfg) < 0) { perror("LOOP_CONFIGURE"); return 1; }

	char src[80];
	if (part > 0) snprintf(src, sizeof src, "/dev/loop%dp%d", n, part);
	else          snprintf(src, sizeof src, "/dev/loop%d", n);
	printf("  mounting %s -> %s\n", src, mp);
	if (mount(src, mp, "auto", 0, "") < 0) { perror("mount"); return 1; }
	return 0;
}
