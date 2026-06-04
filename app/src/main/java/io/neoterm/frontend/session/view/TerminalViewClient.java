package io.neoterm.frontend.session.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import io.neoterm.backend.TerminalSession;

/**
 * Input and scale listener which may be set on a {@link TerminalView} through
 * {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 * <p/>
 */
public interface TerminalViewClient {
  /**
   * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
   */
  float onScale(float scale);

  /**
   * On a single tap on the terminal if terminal mouse reporting not enabled.
   */
  void onSingleTapUp(MotionEvent e);

  boolean shouldBackButtonBeMappedToEscape();

  void copyModeChanged(boolean copyMode);

  boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

  boolean onKeyUp(int keyCode, KeyEvent e);

  boolean readControlKey();

  boolean readAltKey();

  boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);

  boolean onLongPress(MotionEvent event);

  /**
   * The "CC" (custom commands) button in the text-selection bar was tapped.
   * Implementations open the custom-commands manager; {@code session} is the
   * active session a chosen command may be run in.
   */
  void onCustomCommands(TerminalSession session);

  /**
   * A horizontal swipe (fling) was detected on the terminal. Used to page
   * between tabs without interfering with vertical scrolling.
   *
   * @param toLeft true if the finger moved left (go to the next tab), false if
   *               it moved right (previous tab).
   */
  void onSwipe(boolean toLeft);

}
