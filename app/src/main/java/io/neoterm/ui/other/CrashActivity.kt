package io.neoterm.ui.other

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.neoterm.R
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * @author kiva
 */
class CrashActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.ui_crash)
    setSupportActionBar(findViewById(R.id.crash_toolbar))

    (findViewById<TextView>(R.id.crash_model)).text = getString(R.string.crash_model, collectModelInfo())
    (findViewById<TextView>(R.id.crash_app_version)).text = getString(R.string.crash_app, collectAppInfo())
    (findViewById<TextView>(R.id.crash_details)).text = collectExceptionInfo()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menu.add(0, MENU_COPY, 0, R.string.copy_text)
      .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == MENU_COPY) {
      copyReport()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  /** Copy the full crash report (device + app version + stack trace). */
  private fun copyReport() {
    val report = buildString {
      append(getString(R.string.crash_model, collectModelInfo())).append('\n')
      append(getString(R.string.crash_app, collectAppInfo())).append('\n')
      append('\n')
      append(getString(R.string.crash_stack_trace)).append('\n')
      append(collectExceptionInfo())
    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("NeoTerm crash report", report))
    Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
  }

  private fun collectExceptionInfo(): String {
    val extra = intent.getSerializableExtra("exception")
    if (extra != null && extra is Throwable) {
      val byteArrayOutput = ByteArrayOutputStream()
      val printStream = PrintStream(byteArrayOutput)
      (extra.cause ?: extra).printStackTrace(printStream)
      return byteArrayOutput.use {
        byteArrayOutput.toString("utf-8")
      }
    }
    return "are.you.kidding.me.NoExceptionFoundException: This is a bug, please contact developers!"
  }

  private fun collectAppInfo(): String {
    val pm = packageManager
    val info = pm.getPackageInfo(packageName, 0)
    return "${info.versionName} (${info.versionCode})"
  }

  private fun collectModelInfo(): String {
    return "${Build.MODEL} (Android ${Build.VERSION.RELEASE} ${determineArchName()})"
  }

  private fun determineArchName(): String {
    for (androidArch in Build.SUPPORTED_ABIS) {
      when (androidArch) {
        "arm64-v8a" -> return "aarch64"
        "armeabi-v7a" -> return "arm"
        "x86_64" -> return "x86_64"
        "x86" -> return "i686"
      }
    }
    return "Unknown Arch"
  }

  companion object {
    private const val MENU_COPY = 1
  }
}
