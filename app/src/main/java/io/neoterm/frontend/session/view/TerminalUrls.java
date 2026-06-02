package io.neoterm.frontend.session.view;

import java.util.regex.Pattern;

/**
 * Shared URL detection used both for drawing the underline (TerminalRenderer)
 * and for opening links on tap (TerminalView), so the highlighted span and the
 * tappable span always match exactly.
 */
final class TerminalUrls {

  /** Matches http(s)/ftp and bare www. URLs in terminal text. */
  static final Pattern PATTERN = Pattern.compile(
    "(?:(?:https?|ftp)://|www\\.)[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
    Pattern.CASE_INSENSITIVE);

  /** Punctuation that is not part of the URL when it appears at the very end. */
  private static final String TRAILING_PUNCTUATION = ".,;:!?)]}'\"";

  private TerminalUrls() {
  }

  /**
   * Given a regex match [start, end) over {@code text}, return the end index
   * with trailing punctuation trimmed (e.g. the period ending a sentence).
   */
  static int trimmedEnd(CharSequence text, int start, int end) {
    int trimmed = end;
    while (trimmed > start && TRAILING_PUNCTUATION.indexOf(text.charAt(trimmed - 1)) >= 0) {
      trimmed--;
    }
    return trimmed;
  }
}
