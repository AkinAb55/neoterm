package io.neoterm.utils

import android.content.Context
import android.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined "custom commands" (the CC button in the terminal's text-selection
 * bar). Each entry is a label + a shell command line. The list is persisted as a
 * JSON array in the default SharedPreferences, so it survives restarts and is
 * shared across sessions.
 *
 * Run targets (current session vs. a new one) are decided in the UI, not here.
 */
object CustomCommands {
  private const val KEY = "neoterm_custom_commands"

  /** One stored command: a [name] shown in the list and the [command] to run. */
  data class Cmd(var name: String, var command: String)

  private fun prefs(context: Context) =
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

  /** The current list (a mutable copy the caller may edit then [save]). */
  fun load(context: Context): MutableList<Cmd> {
    val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
    return runCatching {
      val arr = JSONArray(raw)
      MutableList(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        Cmd(o.optString("n"), o.optString("c"))
      }
    }.getOrDefault(mutableListOf())
  }

  /** Persist the given list (replaces the stored one). */
  fun save(context: Context, list: List<Cmd>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.command)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }
}
