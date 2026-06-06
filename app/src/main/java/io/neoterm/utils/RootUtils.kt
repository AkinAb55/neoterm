package io.neoterm.utils

import java.io.File

/**
 * Minimal root detection + `su` path resolution, used to offer the chroot
 * runtime on rooted devices. We don't keep a root shell around; ChrootManager
 * builds a one-shot `su -c` launch.
 */
object RootUtils {
  private val SU_PATHS = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/su/bin/su",
    "/debug_ramdisk/su",
    "/system/sbin/su",
    "/vendor/bin/su"
  )

  /** The first existing su binary, or null. */
  fun suPath(): String? = SU_PATHS.firstOrNull { File(it).exists() }

  /** Whether the device looks rooted (a su binary is present). */
  fun isRooted(): Boolean = suPath() != null
}
