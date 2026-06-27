/* uKernel fake <linux/blk-crypto.h> — inline block-layer encryption is disabled
 * in this userspace FS shim (no CONFIG_BLK_INLINE_ENCRYPTION). ext4's
 * page-io.c/readpage.c include this header unconditionally; the few symbols they
 * reference (blk_crypto_submit_bio) resolve to no-op stubs in ext4_stubs.c. */
#ifndef _UK_LINUX_BLK_CRYPTO_H
#define _UK_LINUX_BLK_CRYPTO_H

struct bio;
struct request_queue;
struct blk_crypto_key;

void blk_crypto_submit_bio(struct bio *bio);

#endif
