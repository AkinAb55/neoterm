package io.neoterm.ui.term

import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.Toolbar
import de.mrapp.android.tabswitcher.Tab
import io.neoterm.component.ComponentManager
import io.neoterm.component.colorscheme.ColorSchemeComponent
import io.neoterm.component.session.XSession
import io.neoterm.frontend.session.terminal.*
import org.greenrobot.eventbus.EventBus

/**
 * The tab model. [NeoTab] (and its [TermTab] / [XSessionTab] subclasses) still
 * extend the chrome-tabs [Tab] so they can carry per-tab parameters/colors, but
 * they are now rendered by [TerminalPagerAdapter] inside a ViewPager2 rather
 * than the old TabSwitcher overview — the live-binding logic moved there.
 *
 * @author kiva
 */
open class NeoTab(title: CharSequence) : Tab(title) {
  open fun onPause() {}
  open fun onResume() {}
  open fun onStart() {}
  open fun onStop() {}
  open fun onWindowFocusChanged(hasFocus: Boolean) {}
  open fun onDestroy() {}
  open fun onConfigurationChanged(newConfig: Configuration) {}
}

class XSessionTab(title: CharSequence) : NeoTab(title) {
  var session: XSession? = null
  val sessionData
    get() = session?.mSessionData

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) {
      onPause()
    } else {
      onResume()
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    session?.updateScreenOrientation()
  }

  override fun onPause() {
    session?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    super.onDestroy()
    session?.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    session?.onResume()
  }
}

class TermTab(title: CharSequence) : NeoTab(title), TermUiPresenter {
  companion object {
    val PARAMETER_SHOW_EKS = "show_eks"
  }

  var termData = TermSessionData()
  var toolbar: Toolbar? = null

  fun updateColorScheme() {
    val colorSchemeManager = ComponentManager.getComponent<ColorSchemeComponent>()
    colorSchemeManager.applyColorScheme(
      termData.termView, termData.extraKeysView,
      colorSchemeManager.getCurrentColorScheme()
    )
  }

  fun cleanup() {
    termData.cleanup()
    toolbar = null
  }

  fun onFullScreenModeChanged(fullScreen: Boolean) {
    // Window token changed, we need to recreate PopupWindow
    resetAutoCompleteStatus()
  }

  override fun requireHideIme() {
    val terminalView = termData.termView
    if (terminalView != null) {
      val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      if (imm.isActive) {
        imm.hideSoftInputFromWindow(terminalView.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
      }
    }
  }

  override fun requireFinishAutoCompletion(): Boolean {
    return termData.onAutoCompleteListener?.onFinishCompletion() ?: false
  }

  override fun requireToggleFullScreen() {
    EventBus.getDefault().post(ToggleFullScreenEvent())
  }

  override fun requirePaste() {
    termData.termView?.pasteFromClipboard()
  }

  override fun requireClose() {
    requireHideIme()
    EventBus.getDefault().post(TabCloseEvent(this))
  }

  override fun requireUpdateTitle(title: String?) {
    if (title != null && title.isNotEmpty()) {
      this.title = title
      EventBus.getDefault().post(TitleChangedEvent(title))
      termData.viewClient?.updateExtraKeys(title)
    }
  }

  override fun requireOnSessionFinished(exitCode: Int) {
    // A clean exit (0) closes the tab automatically, no Enter needed. A
    // non-zero exit keeps the tab open so the error/exit message stays visible
    // (press Enter to close).
    if (exitCode == 0) {
      requireClose()
    }
  }

  override fun requireCreateNew() {
    EventBus.getDefault().post(CreateNewSessionEvent())
  }

  override fun requireSwitchToPrevious() {
    EventBus.getDefault().post(SwitchSessionEvent(toNext = false))
  }

  override fun requireSwitchToNext() {
    EventBus.getDefault().post(SwitchSessionEvent(toNext = true))
  }

  override fun requireSwitchTo(index: Int) {
    EventBus.getDefault().post(SwitchIndexedSessionEvent(index))
  }

  fun resetAutoCompleteStatus() {
    termData.onAutoCompleteListener?.onCleanUp()
    termData.onAutoCompleteListener = null
  }

  fun resetStatus() {
    resetAutoCompleteStatus()
    termData.extraKeysView?.updateButtons()
    termData.termView?.updateSize()
    termData.termView?.onScreenUpdated()
  }
}
