package io.neoterm.ui.term

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.neoterm.R
import io.neoterm.component.config.NeoPreference

/**
 * Basic terminal lock: covers the whole terminal with an opaque overlay and
 * requires biometric / device-credential authentication to reveal it. While the
 * feature is enabled the window also gets FLAG_SECURE, which blocks screenshots,
 * screen recording and the recent-apps thumbnail.
 *
 * Scope: this is display-privacy — the shell keeps running behind the cover, so it
 * hides the session from an onlooker (even on an unlocked phone), but it is not
 * at-rest encryption. A stronger variant (biometric-gated Keystore encryption of
 * the scrollback) can be layered on top later.
 *
 * One instance lives per [NeoTermActivity]; the lock state is kept in a companion
 * flag so it survives a configuration-change recreation (rotation).
 */
class SessionLock(
  private val activity: FragmentActivity,
  /** Called after a successful unlock so the host can restore focus / raise the keyboard. */
  private val onUnlocked: () -> Unit = {}
) {

  private var overlay: View? = null
  private var prompting = false

  /** True while the blur cover is on screen — the host must block input then. */
  fun isCovering(): Boolean = overlay != null

  fun isEnabled(): Boolean =
    NeoPreference.loadBoolean(R.string.key_general_session_lock, false)

  private fun autoLockOnBackground(): Boolean =
    NeoPreference.loadBoolean(R.string.key_general_session_lock_on_bg, true)

  /** Apply/clear FLAG_SECURE to match the enabled state. Call from onCreate/onResume. */
  fun applyWindowSecurity() {
    if (isEnabled()) {
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  /** Cover the terminal now (the auth prompt is shown when the app is in front). */
  fun lock() {
    if (!isEnabled()) return
    locked = true
    showOverlay()
  }

  /**
   * onCreate hook: draw the cover before the first frame if we are already locked
   * (cold start, or a rotation before the user unlocked). No prompt here — that is
   * launched from onResume, once the activity is actually in front.
   */
  fun coverIfLocked() {
    if (isEnabled() && locked) showOverlay()
  }

  /** onStop hook: re-lock if configured to auto-lock when leaving the app. */
  fun onBackground() {
    if (isEnabled() && autoLockOnBackground()) lock()
  }

  /**
   * onResume hook: if the feature is enabled and we are (or should be) locked,
   * show the cover and launch the authentication prompt. On a cold start the
   * companion flag is already `true` when the feature is on (see companion init),
   * so the terminal is never shown before the first unlock.
   */
  fun onForeground() {
    if (!isEnabled()) { unlockNow(); return }
    if (locked) {
      showOverlay()
      promptUnlock()
    }
  }

  /**
   * Heavy blur of the terminal so the content is unreadable, plus a light frosted
   * scrim and the lock UI on top. On Android 12+ (API 31) the terminal is blurred
   * LIVE via RenderEffect; on older releases a strongly down-scaled snapshot is
   * drawn over it (the content behind is frozen but hidden by the blurred image).
   */
  private fun showOverlay() {
    if (overlay != null) return
    val ctx = activity
    val root = activity.findViewById<View>(R.id.terminal_root)

    val liveBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && root != null
    if (liveBlur) {
      root!!.setRenderEffect(
        RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
    }

    val cover = FrameLayout(ctx).apply {
      isClickable = true          // consume touches so nobody types into the session
      // Grab focus (in touch mode) and swallow key events, so the soft keyboard
      // has no input target and hardware keys don't reach the terminal behind it.
      isFocusableInTouchMode = true
      isFocusable = true
      setOnKeyListener { _, _, _ -> true }
      setOnClickListener { promptUnlock() }   // tap re-triggers the prompt if dismissed
      if (liveBlur) {
        // Live blur shows through from behind; just a faint frost tint on top.
        setBackgroundColor(0x33000000)
      } else {
        // No RenderEffect: paint a heavily-blurred snapshot of the content (the
        // sharp view still sits behind it, fully covered), then a frost tint.
        val snap = root?.let { blurredSnapshot(it) }
        if (snap != null) background = BitmapDrawable(ctx.resources, snap)
        else setBackgroundColor(ContextCompat.getColor(ctx, R.color.terminal_background))
        foreground = null
      }
    }
    // extra frost tint over the snapshot fallback so residual shapes vanish
    if (!liveBlur) {
      cover.addView(View(ctx).apply { setBackgroundColor(0x66000000) },
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    val column = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
    }
    val pad = (16 * ctx.resources.displayMetrics.density).toInt()
    column.addView(TextView(ctx).apply {
      setText(R.string.session_lock_locked_title)
      textSize = 18f
      gravity = Gravity.CENTER
      setTextColor(0xFFFFFFFF.toInt())
      setPadding(0, 0, 0, pad / 2)
    })
    column.addView(TextView(ctx).apply {
      setText(R.string.session_lock_hint)
      textSize = 14f
      alpha = 0.75f
      gravity = Gravity.CENTER
      setTextColor(0xFFFFFFFF.toInt())
    })
    cover.addView(column, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    activity.addContentView(cover, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    overlay = cover

    // Kill any soft keyboard and move focus off the terminal onto the cover.
    hideKeyboardAndGrabFocus(cover)
  }

  private fun hideKeyboardAndGrabFocus(cover: View) {
    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.let { imm ->
      activity.window?.decorView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
    }
    cover.requestFocus()
    cover.post { cover.requestFocus() }   // again once attached/laid out
  }

  private fun removeOverlay() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      activity.findViewById<View>(R.id.terminal_root)?.setRenderEffect(null)
    }
    overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
    overlay = null
  }

  /**
   * Snapshot [view] and blur it hard by down-scaling to a few percent and letting
   * the bilinear up-scale (done by the ImageView/drawable) smear it. Dependency-free
   * fallback for API < 31 where RenderEffect is unavailable.
   */
  private fun blurredSnapshot(view: View): Bitmap? {
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return null
    return try {
      val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      view.draw(Canvas(full))
      val sw = max(1, (w * SNAPSHOT_SCALE).toInt())
      val sh = max(1, (h * SNAPSHOT_SCALE).toInt())
      val small = Bitmap.createScaledBitmap(full, sw, sh, true)
      full.recycle()
      small   // drawn scaled back up (with filtering) -> heavy blur
    } catch (t: Throwable) {
      null
    }
  }

  /**
   * DEVICE_CREDENTIAL may only be combined with BIOMETRIC_STRONG on API 30+.
   * On older releases pair it with BIOMETRIC_WEAK, which the framework accepts.
   */
  private fun allowedAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

  private fun promptUnlock() {
    if (prompting) return
    // If there is no biometric AND no device credential to authenticate against,
    // don't lock the user out of their own terminal — just reveal it.
    val available = BiometricManager.from(activity).canAuthenticate(allowedAuthenticators())
    if (available == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
      available == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
      available == BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED) {
      unlockNow()
      return
    }
    prompting = true
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          prompting = false
          unlockNow()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          prompting = false
          // Cancelled / lockout: keep the cover in place. Tapping it retries.
        }
      })
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(activity.getString(R.string.session_lock_prompt_title))
      .setSubtitle(activity.getString(R.string.session_lock_prompt_subtitle))
      .setAllowedAuthenticators(allowedAuthenticators())
      .build()
    try {
      prompt.authenticate(info)
    } catch (t: Throwable) {
      // Misconfiguration (e.g. authenticator combo rejected on this device): fail
      // open rather than trap the user; FLAG_SECURE still protects the display.
      prompting = false
      unlockNow()
    }
  }

  private fun unlockNow() {
    locked = false
    removeOverlay()
    onUnlocked()   // host restores terminal focus + keyboard
  }

  companion object {
    /**
     * Lock state, kept process-wide so it survives an activity recreation. Starts
     * locked so the very first foreground with the feature on requires auth.
     */
    @JvmStatic
    var locked = true

    /** RenderEffect blur radius (px). Large enough to make terminal text illegible. */
    private const val BLUR_RADIUS = 55f

    /** Snapshot down-scale for the API < 31 fallback (~5% -> very heavy up-scale blur). */
    private const val SNAPSHOT_SCALE = 0.05f
  }
}
