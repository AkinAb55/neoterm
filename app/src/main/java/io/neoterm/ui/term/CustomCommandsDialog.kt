package io.neoterm.ui.term

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.neoterm.R
import io.neoterm.utils.CustomCommands

/**
 * The "CC" (custom commands) manager, opened from the terminal's text-selection
 * bar. Lets the user add / edit / delete / reorder named shell commands, and run
 * a chosen one either in the current session or in a freshly opened one.
 *
 * @param runInCurrent runs the command line in the active session
 * @param runInNew     opens a new session that runs the command line
 */
class CustomCommandsDialog(
  private val context: Context,
  private val runInCurrent: (String) -> Unit,
  private val runInNew: (String) -> Unit
) {
  private val commands = CustomCommands.load(context)
  private lateinit var listContainer: LinearLayout
  private var mainDialog: AlertDialog? = null

  private fun dp(v: Int) = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
  ).toInt()

  fun show() {
    listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(context).apply {
      addView(listContainer)
      setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    rebuild()

    val dialog = AlertDialog.Builder(context)
      .setTitle(R.string.custom_commands)
      .setView(scroll)
      // Keep the manager open after Add (the click listener is overridden below
      // so the dialog isn't auto-dismissed).
      .setNeutralButton(R.string.cc_add, null)
      .setNegativeButton(R.string.cc_close, null)
      .create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { editOrAdd(null) }
    }
    mainDialog = dialog
    dialog.show()
  }

  private fun rebuild() {
    listContainer.removeAllViews()
    if (commands.isEmpty()) {
      listContainer.addView(TextView(context).apply {
        setText(R.string.cc_empty)
        setPadding(dp(8), dp(16), dp(8), dp(16))
      })
      return
    }
    commands.forEachIndexed { i, c -> addRow(c, i) }
  }

  private fun addRow(cmd: CustomCommands.Cmd, index: Int) {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val name = TextView(context).apply {
      text = cmd.name
      textSize = 16f
      isSingleLine = true
      ellipsize = TextUtils.TruncateAt.END
      setPadding(dp(4), dp(10), dp(8), dp(10))
      isClickable = true
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      setOnClickListener { chooseRunTarget(cmd) }
    }
    row.addView(name)
    row.addView(iconButton("↑") { move(index, -1) })  // ↑
    row.addView(iconButton("↓") { move(index, +1) })  // ↓
    row.addView(iconButton("✎") { editOrAdd(cmd) })   // ✎
    row.addView(iconButton("✕") {                      // ✕
      commands.removeAt(index)
      persistAndRebuild()
    })
    listContainer.addView(row)
  }

  private fun iconButton(glyph: String, onClick: () -> Unit) = TextView(context).apply {
    text = glyph
    textSize = 18f
    gravity = Gravity.CENTER
    minWidth = dp(40)
    setPadding(dp(6), dp(10), dp(6), dp(10))
    isClickable = true
    setOnClickListener { onClick() }
  }

  private fun move(index: Int, delta: Int) {
    val target = index + delta
    if (target in commands.indices) {
      val tmp = commands[index]
      commands[index] = commands[target]
      commands[target] = tmp
      persistAndRebuild()
    }
  }

  private fun chooseRunTarget(cmd: CustomCommands.Cmd) {
    val items = arrayOf(
      context.getString(R.string.cc_run_current),
      context.getString(R.string.cc_run_new)
    )
    AlertDialog.Builder(context)
      .setTitle(cmd.name)
      .setItems(items) { _, which ->
        if (which == 0) runInCurrent(cmd.command) else runInNew(cmd.command)
        mainDialog?.dismiss()
      }
      .setNegativeButton(R.string.cc_close, null)
      .show()
  }

  private fun editOrAdd(existing: CustomCommands.Cmd?) {
    val nameEdit = EditText(context).apply {
      hint = context.getString(R.string.cc_name_hint)
      setText(existing?.name ?: "")
      inputType = InputType.TYPE_CLASS_TEXT
    }
    val cmdEdit = EditText(context).apply {
      hint = context.getString(R.string.cc_command_hint)
      setText(existing?.command ?: "")
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    val layout = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(8), dp(20), 0)
      addView(nameEdit)
      addView(cmdEdit)
    }
    AlertDialog.Builder(context)
      .setTitle(if (existing == null) R.string.cc_add else R.string.cc_edit)
      .setView(layout)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val command = cmdEdit.text.toString().trim()
        if (command.isEmpty()) return@setPositiveButton
        val name = nameEdit.text.toString().trim().ifEmpty { command }
        if (existing == null) {
          commands.add(CustomCommands.Cmd(name, command))
        } else {
          existing.name = name
          existing.command = command
        }
        persistAndRebuild()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun persistAndRebuild() {
    CustomCommands.save(context, commands)
    rebuild()
  }
}
