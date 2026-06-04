package io.neoterm.frontend.session.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a "tappable token" (a web URL, a filesystem path, or a git commit
 * hash) under a tapped position in a terminal line. Used by {@link TerminalView}
 * to offer a quick-action menu on tap. URLs reuse {@link TerminalUrls} so the
 * highlighted/underlined span stays in sync.
 */
public final class TerminalTokens {

  public enum Type { URL, PATH, GIT_HASH }

  public static final class Token {
    public final Type type;
    public final String value;

    Token(Type type, String value) {
      this.type = type;
      this.value = value;
    }
  }

  /**
   * Paths: ~/… ./… ../… ; absolute paths with at least two segments (so a stray
   * "and/or" isn't matched); and bare filenames with a known extension.
   */
  private static final Pattern PATH = Pattern.compile(
    "(?:~|\\.\\.?)/[\\w.+@/\\-]*"
      + "|/[\\w.+@\\-]+(?:/[\\w.+@\\-]+)+"
      + "|[\\w.+@\\-]+\\.(?:kt|java|c|h|hh|hpp|cc|cpp|cxx|py|rb|rs|go|js|ts|tsx|jsx|"
      + "json|xml|html|css|md|txt|log|sh|bash|zsh|yml|yaml|toml|ini|cfg|conf|gradle|"
      + "properties|gz|xz|zip|tar|png|jpg|jpeg|gif|pdf|csv)");

  /** A git short/long hash: 7–40 hex chars, requiring at least one a–f letter. */
  private static final Pattern GIT = Pattern.compile("\\b(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\\b");

  private TerminalTokens() {
  }

  /**
   * Return the token whose span contains {@code index}, trying URL, then path,
   * then git-hash (most-specific first), or null if the tap isn't on a token.
   */
  public static Token find(CharSequence text, int index) {
    Token url = matchAt(TerminalUrls.PATTERN, text, index);
    if (url != null) {
      String v = url.value;
      if (v.regionMatches(true, 0, "www.", 0, 4)) v = "http://" + v;
      return new Token(Type.URL, v);
    }
    Token path = matchAt(PATH, text, index);
    if (path != null) return new Token(Type.PATH, path.value);
    Token git = matchAt(GIT, text, index);
    if (git != null) return new Token(Type.GIT_HASH, git.value);
    return null;
  }

  private static Token matchAt(Pattern pattern, CharSequence text, int index) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      int start = matcher.start();
      int end = TerminalUrls.trimmedEnd(text, start, matcher.end());
      if (end > start && index >= start && index < end) {
        return new Token(Type.URL /* unused here */, text.subSequence(start, end).toString());
      }
    }
    return null;
  }
}
