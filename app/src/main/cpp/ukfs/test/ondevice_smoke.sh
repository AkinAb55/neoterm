#!/bin/sh
# uKernel USB-storage — on-device smoke test.
#
# Run this INSIDE the NeoTerm proot guest, with:
#   - the USB-storage toggle ON (so UK_BLOCK=1 / UK_FS=1, BlockBridge + ukfsd up),
#   - a USB mass-storage drive (FAT/exFAT/NTFS/ext4) attached.
#
# It mounts /dev/uksd0 through the proot VFS-redirect and exercises the
# operations the engine supports today (mount, ls, stat, read; create a fresh
# file and read it back; mkdir). See the write-limitation note at the bottom.
#
#   sh ondevice_smoke.sh [mountpoint]

set -u
MNT="${1:-/mnt/uksd}"
fail=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fail=1; }

echo "== uKernel USB-storage on-device smoke test =="
echo "UK_BLOCK=${UK_BLOCK:-unset}  UK_FS=${UK_FS:-unset}"

if [ -e /dev/uksd0 ]; then ok "/dev/uksd0 present"
else bad "/dev/uksd0 missing — is the storage toggle on and a drive attached?"; exit 1; fi

mkdir -p "$MNT" 2>/dev/null
if mount /dev/uksd0 "$MNT" 2>/dev/null || mount -t vfat /dev/uksd0 "$MNT" 2>/dev/null; then
  ok "mount /dev/uksd0 -> $MNT"
else
  bad "mount /dev/uksd0 failed"; exit 1
fi

if ls -la "$MNT" >/dev/null 2>&1; then ok "ls $MNT"; ls -la "$MNT" 2>/dev/null | sed 's/^/      /'
else bad "ls $MNT"; fi

stat "$MNT" >/dev/null 2>&1 && ok "stat $MNT (root)" || bad "stat $MNT"

# create a fresh file and read it back (the supported write pattern)
F="$MNT/uk_smoke_$$.txt"
MSG="hello from the neoterm guest"
if printf '%s\n' "$MSG" > "$F" 2>/dev/null; then ok "write fresh file $F"; else bad "write $F"; fi
if [ "$(cat "$F" 2>/dev/null)" = "$MSG" ]; then ok "read back $F"; else bad "read back $F"; fi

D="$MNT/uk_dir_$$"
if mkdir "$D" 2>/dev/null && [ -d "$D" ]; then ok "mkdir $D"; else bad "mkdir $D"; fi

# best-effort cleanup
rm -f "$F" 2>/dev/null
rmdir "$D" 2>/dev/null
umount "$MNT" 2>/dev/null && ok "umount $MNT" || echo "  (umount skipped)"

echo
if [ "$fail" -eq 0 ]; then echo "SMOKE TEST PASSED"; else echo "SMOKE TEST HAD FAILURES"; fi
echo
echo "Known engine write limitations (NOT bugs in this script):"
echo "  - mid-file / append (non-zero-offset) writes do not yet persist;"
echo "  - re-writing a file already read in the same mount reads back stale data."
echo "  Whole-file rewrites (editors via temp+rename, cp) are the reliable path."
exit "$fail"
