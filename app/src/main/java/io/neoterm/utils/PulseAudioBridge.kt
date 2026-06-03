package io.neoterm.utils

import android.content.Context
import io.neoterm.BuildConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File

/**
 * Android-side PulseAudio: plays the distro's audio on the device speaker.
 *
 * A vanilla PulseAudio cross-built for Android (bundled in assets as
 * pulseaudio-aarch64.tar) runs as the app uid — no root, no proot — with:
 *   - module-native-protocol-tcp on :4713 — distro apps connect via
 *     PULSE_SERVER=127.0.0.1:4713 (set in ProotManager);
 *   - module-sles-sink — a properly-clocked native OpenSL ES sink (PA →
 *     OpenSL → speaker). This replaced an earlier pipe-sink + FIFO + AudioTrack
 *     setup, whose open-loop clock drifted and caused constant underruns.
 *
 * Started with the app by NeoTermService so terminal apps (not just X11) have
 * audio.
 *
 * @author kiva
 */
object PulseAudioBridge {
  @Volatile private var running = false
  private var thread: Thread? = null
  private var paProcess: Process? = null

  fun start(context: Context) {
    if (running) return
    running = true
    val app = context.applicationContext
    // Extraction + launch off the caller's thread (e.g. the service onCreate).
    thread = Thread({
      val dir = prepare(app)
      if (dir == null) {
        running = false
        return@Thread
      }
      startPulseAudio(dir)
    }, "pulse-bridge").apply { isDaemon = true; start() }
  }

  fun stop() {
    running = false
    runCatching { paProcess?.destroy() }
    paProcess = null
    thread = null
  }

  /** Extract the bundled PulseAudio once per app version; returns its dir. */
  private fun prepare(context: Context): File? {
    return runCatching {
      val dir = File(context.filesDir, "pulseaudio")
      val verFile = File(dir, ".ver")
      val ver = BuildConfig.VERSION_CODE.toString()
      val bin = File(dir, "bin/pulseaudio")
      if (bin.exists() && verFile.exists() && verFile.readText() == ver) {
        return@runCatching dir
      }
      dir.deleteRecursively()
      dir.mkdirs()
      // Plain tar — AGP decompresses a .gz asset and renames it, so we ship
      // and read an uncompressed .tar.
      context.assets.open("pulseaudio-aarch64.tar").use { raw ->
        TarArchiveInputStream(raw).use { tar ->
          var entry = tar.nextTarEntry
          while (entry != null) {
            val out = File(dir, entry.name)
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().use { tar.copyTo(it) }
            }
            entry = tar.nextTarEntry
          }
        }
      }
      bin.setExecutable(true, false)
      File(dir, "runtime").mkdirs()
      verFile.writeText(ver)
      dir
    }.onFailure {
      NLog.e("PulseAudioBridge", "Failed to extract PulseAudio: ${it.localizedMessage}")
    }.getOrNull()
  }

  /** Run the bundled pulseaudio with the OpenSL ES sink + TCP protocol. */
  private fun startPulseAudio(dir: File) {
    runCatching {
      val runtime = File(dir, "runtime").apply { mkdirs() }
      val bin = File(dir, "bin/pulseaudio").absolutePath
      val modules = File(dir, "lib/pulseaudio/modules").absolutePath
      val log = File(dir, "pulse.log").absolutePath
      val pb = ProcessBuilder(
        bin, "-n", "--daemonize=no", "--exit-idle-time=-1", "--disable-shm=true",
        "--dl-search-path=$modules",
        "--log-target=newfile:$log", "--log-level=notice",
        "-L", "module-native-protocol-tcp port=4713 auth-ip-acl=127.0.0.1 auth-anonymous=1",
        "-L", "module-sles-sink sink_name=neoterm"
      )
      val env = pb.environment()
      env["HOME"] = runtime.absolutePath
      env["XDG_RUNTIME_DIR"] = runtime.absolutePath
      env["PULSE_RUNTIME_PATH"] = runtime.absolutePath
      env["LD_LIBRARY_PATH"] =
        "${File(dir, "lib").absolutePath}:${File(dir, "lib/pulseaudio/modules").absolutePath}"
      // Skip PulseAudio's startup self-exec (it canonicalizes the compile-time
      // PA_BINARY path /bin/pulseaudio, which doesn't exist on Android).
      env["LD_BIND_NOW"] = "1"
      env["PATH"] = "${File(dir, "bin").absolutePath}:/system/bin"
      pb.redirectErrorStream(true)
      pb.redirectOutput(ProcessBuilder.Redirect.to(File(dir, "pulse-stdout.log")))
      paProcess = pb.start()
      NLog.e("PulseAudioBridge", "PulseAudio (OpenSL sink) started on :4713")
    }.onFailure {
      NLog.e("PulseAudioBridge", "Failed to start PulseAudio: ${it.localizedMessage}")
    }
  }
}
