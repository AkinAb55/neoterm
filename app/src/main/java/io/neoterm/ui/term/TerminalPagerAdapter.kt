package io.neoterm.ui.term

import android.graphics.Rect
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.neoterm.NeoGLView
import io.neoterm.R
import io.neoterm.backend.TerminalColors
import io.neoterm.component.ComponentManager
import io.neoterm.component.colorscheme.ColorSchemeComponent
import io.neoterm.component.colorscheme.NeoColorScheme
import io.neoterm.component.completion.OnAutoCompleteListener
import io.neoterm.component.config.DefaultValues
import io.neoterm.component.config.NeoPreference
import io.neoterm.frontend.session.terminal.*
import io.neoterm.frontend.session.view.TerminalView
import io.neoterm.frontend.session.view.extrakey.ExtraKeysView
import io.neoterm.utils.Terminals

/**
 * Backs the [androidx.viewpager2.widget.ViewPager2] with the list of open
 * sessions. Each page is a live view bound to a session:
 *   - a terminal session -> R.layout.ui_term (TerminalView + ExtraKeysView)
 *   - an X session        -> R.layout.ui_xorg (the SDL GL surface)
 *
 * The binding reuses exactly the same [TermSessionData]/[TermTab] wiring the old
 * TabSwitcher decorator used, so a page is a fully live terminal — which (with
 * offscreenPageLimit = 1) is what lets the user peek the neighbouring tab's real
 * content while dragging.
 *
 * The model is the list of [NeoTab] (TermTab / XSessionTab) held by the
 * activity; the adapter never owns sessions, it only renders them.
 *
 * ## Infinite (circular) paging
 * When there are >= 3 real tabs the adapter switches to an *infinite* mode: it
 * reports a large virtual item count ([LOOP_COUNT]) and maps every virtual
 * position to a real tab with `position % realCount`. The user starts at a
 * middle multiple of realCount (see [startPosition]) so swiping past either end
 * wraps around endlessly. The activity is responsible for re-centering the
 * current virtual position when it drifts toward the ends (see
 * NeoTermActivity.recenterIfNeeded).
 *
 * For <= 2 real tabs we keep the simple finite adapter: the count is the real
 * count and `position == realIndex`. Wrapping is meaningless with 1 tab, and
 * with exactly 2 tabs an infinite modulo adapter would lay out the SAME session
 * at both neighbours of any page (current-1 and current+1 both map to the other
 * single tab) — two live [TerminalView]s bound to one session steals the
 * session's view and blanks it. So we only enable looping once both neighbours
 * are guaranteed to be DISTINCT real tabs (realCount >= 3).
 *
 * Stable ids are intentionally NOT used: in infinite mode the same session
 * appears at many virtual positions, which violates RecyclerView's "unique id
 * per item" contract. Binding is idempotent (it always re-runs
 * initializeViewWith + attachSession for the correct session), so position-keyed
 * rebinding is safe.
 */
class TerminalPagerAdapter(
  private val activity: NeoTermActivity,
  val tabs: MutableList<NeoTab>
) : RecyclerView.Adapter<TerminalPagerAdapter.PageHolder>() {

  companion object {
    private const val VIEW_TYPE_TERM = 0
    private const val VIEW_TYPE_X = 1

    /** Looping kicks in only when both pager neighbours are distinct sessions. */
    const val MIN_LOOP_TABS = 3

    /** Virtual item count in infinite mode (kept even so the middle is a clean
     *  multiple of realCount; large enough the user never reaches an end). */
    const val LOOP_COUNT = 100_000
  }

  class PageHolder(val root: View) : RecyclerView.ViewHolder(root)

  /** Number of real tabs (the model size). */
  val realCount: Int
    get() = tabs.size

  /** Whether the adapter is currently in infinite/circular mode. */
  val isLooping: Boolean
    get() = realCount >= MIN_LOOP_TABS

  /** Map a (possibly virtual) pager position to the real tab index. */
  fun realIndex(position: Int): Int {
    if (realCount == 0) return 0
    return if (isLooping) Math.floorMod(position, realCount) else position
  }

  /**
   * A virtual position near the middle of the loop range whose real index is
   * [realIndex]. The user is parked here so there's a huge runway of pages on
   * both sides before either end of the virtual range is reached.
   */
  fun startPosition(realIndex: Int): Int {
    if (!isLooping) return realIndex.coerceIn(0, (realCount - 1).coerceAtLeast(0))
    val middleBlock = (LOOP_COUNT / 2) / realCount
    return middleBlock * realCount + realIndex
  }

  override fun getItemCount(): Int = if (isLooping) LOOP_COUNT else realCount

  override fun getItemViewType(position: Int): Int =
    if (tabs[realIndex(position)] is XSessionTab) VIEW_TYPE_X else VIEW_TYPE_TERM

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
    val inflater = LayoutInflater.from(parent.context)
    val view = when (viewType) {
      VIEW_TYPE_X -> inflater.inflate(R.layout.ui_xorg, parent, false)
      else -> {
        val v = inflater.inflate(R.layout.ui_term, parent, false)
        val terminalView = v.findViewById<TerminalView>(R.id.terminal_view)
        val extraKeysView = v.findViewById<ExtraKeysView>(R.id.extra_keys)
        Terminals.setupTerminalView(terminalView)
        Terminals.setupExtraKeysView(extraKeysView)

        val colorSchemeManager = ComponentManager.getComponent<ColorSchemeComponent>()
        val scheme = colorSchemeManager.getCurrentColorScheme()
        colorSchemeManager.applyColorScheme(terminalView, extraKeysView, scheme)
        // Match the container background to the terminal background so swiping
        // between tabs doesn't flash a different color behind the terminal.
        v.setBackgroundColor(schemeBackgroundColor(scheme))
        v
      }
    }
    // Pages must fill the pager (ViewPager2 requires MATCH_PARENT children).
    view.layoutParams = RecyclerView.LayoutParams(
      RecyclerView.LayoutParams.MATCH_PARENT,
      RecyclerView.LayoutParams.MATCH_PARENT
    )
    return PageHolder(view)
  }

  override fun onBindViewHolder(holder: PageHolder, position: Int) {
    val tab = tabs[realIndex(position)]
    when (tab) {
      is TermTab -> bindTerminalPage(holder, tab)
      is XSessionTab -> bindXPage(holder, tab)
    }
  }

  private fun bindTerminalPage(holder: PageHolder, tab: TermTab) {
    tab.toolbar = activity.toolbar
    val terminalView = holder.root.findViewById<TerminalView>(R.id.terminal_view)
    val extraKeysView = holder.root.findViewById<ExtraKeysView>(R.id.extra_keys)

    val termData = tab.termData
    termData.initializeViewWith(tab, terminalView, extraKeysView)
    terminalView.setEnableWordBasedIme(termData.profile?.enableWordBasedIme ?: DefaultValues.enableWordBasedIme)
    terminalView.setTerminalViewClient(termData.viewClient)
    terminalView.attachSession(termData.termSession)

    if (NeoPreference.loadBoolean(R.string.key_general_auto_completion, false)) {
      if (termData.onAutoCompleteListener == null) {
        termData.onAutoCompleteListener = TermCompleteListener(terminalView)
      }
      terminalView.onAutoCompleteListener = termData.onAutoCompleteListener
    }

    if (termData.termSession != null) {
      termData.viewClient?.updateExtraKeys(termData.termSession?.title, true)
    }

    // Build the emulator + redraw once the page is laid out so neighbouring
    // (offscreen) pages are live and can be peeked during a drag.
    terminalView.post {
      terminalView.updateSize()
      terminalView.onScreenUpdated()
    }
  }

  private fun bindXPage(holder: PageHolder, tab: XSessionTab) {
    val sessionData = tab.sessionData ?: return
    val videoLayout = holder.root.findViewById<FrameLayout>(R.id.xorg_video_layout)

    // Re-parent the GL view onto this page's container (it may have been bound
    // to an old recycled view).
    sessionData.videoLayout = videoLayout
    videoLayout.setLayerType(View.LAYER_TYPE_NONE, null)

    val existing = sessionData.glView
    if (existing != null) {
      (existing.parent as? ViewGroup)?.removeView(existing)
      videoLayout.addView(
        existing,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT
        )
      )
      return
    }

    Thread {
      sessionData.client?.runOnUiThread {
        val glView = NeoGLView(sessionData.client)
        sessionData.glView = glView
        glView.isFocusableInTouchMode = true
        glView.isFocusable = true
        glView.requestFocus()
        glView.setLayerType(View.LAYER_TYPE_NONE, null)
        videoLayout.addView(
          glView,
          FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
          )
        )

        glView.pointerIcon = android.view.PointerIcon.getSystemIcon(
          activity, android.view.PointerIcon.TYPE_NULL
        )

        val r = Rect()
        videoLayout.getWindowVisibleDisplayFrame(r)
        glView.callNativeScreenVisibleRect(r.left, r.top, r.right, r.bottom)
        videoLayout.viewTreeObserver.addOnGlobalLayoutListener {
          val rect = Rect()
          videoLayout.getWindowVisibleDisplayFrame(rect)
          val heightDiff = videoLayout.rootView.height - videoLayout.height
          val widthDiff = videoLayout.rootView.width - videoLayout.width
          Log.v(
            "SDL",
            "Main window visible region changed: " + rect.left + ":" + rect.top + ":" + rect.width() + ":" + rect.height()
          )
          videoLayout.postDelayed({
            sessionData.glView?.callNativeScreenVisibleRect(
              rect.left + widthDiff, rect.top + heightDiff, rect.width(), rect.height()
            )
          }, 300)
          videoLayout.postDelayed({
            sessionData.glView?.callNativeScreenVisibleRect(
              rect.left + widthDiff, rect.top + heightDiff, rect.width(), rect.height()
            )
          }, 600)
        }
      }
    }.start()
  }

  override fun onViewRecycled(holder: PageHolder) {
    super.onViewRecycled(holder)
    // Terminal pages need no teardown here: binding is idempotent and the
    // session<->view link (termData.termView + TerminalView.attachSession) is
    // re-established on the next bind. The double-bind guarantee comes from
    // never laying out the same session twice at once: in infinite mode that
    // holds because looping is only enabled for realCount >= 3, so the three
    // laid-out pages (current +/- 1) always map to three DISTINCT real tabs.
    //
    // Detach the X GL view from a recycled page so it can be re-parented onto the
    // page it gets re-bound to (the GL surface is a single live instance).
    val videoLayout = holder.root.findViewById<FrameLayout>(R.id.xorg_video_layout) ?: return
    if (videoLayout.childCount > 0) videoLayout.removeAllViews()
  }

  /** The active color scheme's background color (falls back to the default). */
  private fun schemeBackgroundColor(scheme: NeoColorScheme): Int {
    return try {
      TerminalColors.parse(scheme.backgroundColor ?: "#14181c")
    } catch (e: Exception) {
      ContextCompat.getColor(activity, R.color.terminal_background)
    }
  }
}
