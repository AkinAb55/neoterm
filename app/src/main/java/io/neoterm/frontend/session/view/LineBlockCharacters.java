package io.neoterm.frontend.session.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Pixel-precise drawing of the Box Drawing (U+2500–U+257F), Block Elements
 * (U+2580–U+259F) and Braille (U+2800–U+28FF) Unicode blocks, ported from KDE
 * Konsole's {@code src/characters/LineBlockCharacters.cpp}.
 *
 * <p>Fonts render these glyphs with their own metrics and hinting, so adjacent
 * cells leave hairline gaps and bars don't line up — very visible in TUIs that
 * draw frames and progress bars (e.g. Claude Code's borders). Drawing them
 * ourselves into the exact cell rectangle makes them seamless and resolution
 * independent, exactly as Konsole does.
 *
 * <p>The geometry mirrors Konsole verbatim; only the Qt {@code QPainter} calls
 * are translated to {@link Canvas}/{@link Path}. Qt's {@code arcTo} measures
 * angles counter-clockwise on a y-down canvas while Android's measures them
 * clockwise, so arc angles are negated in {@link #drawRoundedCorner}.
 */
final class LineBlockCharacters {

  private LineBlockCharacters() {}

  /** Whether {@link #draw} can render this code point (the two solid-shape blocks). */
  static boolean canDraw(int ucs4) {
    return (0x2500 <= ucs4 && ucs4 <= 0x259F) || (0x2800 <= ucs4 && ucs4 <= 0x28FF);
  }

  // Line types, packed two bits per side. Order matches Konsole's enum.
  private static final int LtNone = 0, LtDouble = 1, LtLight = 2, LtHeavy = 3;

  private static int mk(int top, int right, int bottom, int left) {
    return ((top & 3) << 6) | ((right & 3) << 4) | ((bottom & 3) << 2) | (left & 3);
  }

  /**
   * For each Box Drawing code point (offset from U+2500), the packed types of its
   * top/right/bottom/left lines (2 bits each, MSB = top). 0 means "not a basic
   * straight-line character" (handled by the dashed/rounded/diagonal/block paths).
   */
  private static final int[] PACKED_LINE_TYPES = {
    34, 51, 136, 204, 0, 0, 0, 0, 0, 0, 0, 0, 40, 56, 44, 60,
    10, 11, 14, 15, 160, 176, 224, 240, 130, 131, 194, 195, 168, 184, 232, 172,
    236, 248, 188, 252, 138, 139, 202, 142, 206, 203, 143, 207, 42, 43, 58, 59,
    46, 47, 62, 63, 162, 163, 178, 179, 226, 227, 242, 243, 170, 171, 186, 187,
    234, 174, 238, 235, 250, 175, 190, 251, 191, 239, 254, 255, 0, 0, 0, 0,
    17, 68, 24, 36, 20, 9, 6, 5, 144, 96, 80, 129, 66, 65, 152, 100,
    84, 137, 70, 69, 25, 38, 21, 145, 98, 81, 153, 102, 85, 0, 0, 0,
    0, 0, 0, 0, 2, 128, 32, 8, 3, 192, 48, 12, 50, 140, 35, 200,
  };

  /**
   * Draw {@code chr} into the integer cell rectangle [{@code x},{@code y},
   * {@code w}×{@code h}] using {@code paint} (its colour/antialias are set by the
   * caller). {@code bold} thickens the strokes.
   */
  static void draw(Canvas canvas, Paint paint, int x, int y, int w, int h, int chr, boolean bold) {
    if (chr >= 0x2800) {
      drawBraille(canvas, paint, x, y, w, h, chr - 0x2800);
      return;
    }
    int code = chr - 0x2500;
    if (drawBasicLine(canvas, paint, x, y, w, h, code, bold)) return;
    if (drawDashedLine(canvas, paint, x, y, w, h, code, bold)) return;
    if (drawRoundedCorner(canvas, paint, x, y, w, h, code, bold)) return;
    if (drawDiagonal(canvas, paint, x, y, w, h, code, bold)) return;
    drawBlock(canvas, paint, x, y, w, h, code);
  }

  // -- line widths (ported from Konsole lineWidth()) --------------------------

  private static int lineWidth(int fontWidth, boolean heavy, boolean bold) {
    final double lightWidthToFontWidthRatio = 1.0 / 6.5;
    final double heavyHalfExtraToLightRatio = 1.0 / 3.0;
    final double boldCoefficient = 1.5;

    final double baseWidth = fontWidth * lightWidthToFontWidthRatio;
    final double boldCoeff = bold ? boldCoefficient : 1.0;
    final double minWidth = (bold && fontWidth >= 7) ? baseWidth + 1.0 : 1.0;
    final int lightWidth = (int) Math.round(Math.max(baseWidth * boldCoeff, minWidth));
    final int heavyHalfExtraWidth = (int) Math.round(Math.max(lightWidth * heavyHalfExtraToLightRatio, 1.0));

    return heavy ? lightWidth + 2 * heavyHalfExtraWidth : lightWidth;
  }

  private static void strokePath(Canvas canvas, Paint paint, Path path, int width) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(width);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);
    canvas.drawPath(path, paint);
  }

  // -- basic straight-line characters (─ │ ┼ ┏ ╔ … ) --------------------------

  private static int rotl8(int value, int amount) {
    value &= 0xFF;
    return ((value << amount) | (value >>> (8 - amount))) & 0xFF;
  }

  /** Stateful per-call drawer mirroring Konsole's drawBasicLineCharacter lambdas. */
  private static final class BasicLineDrawer {
    int packed;
    final Path light = new Path();
    final Path heavy = new Path();
    // origin[side] = {x, y}; dir[side] = unit vector from centre to origin.
    final double[][] origin = new double[4][2];
    static final double[][] dir = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    double cx, cy;
    double doubleDist; // distance between the two strands of a double line
    double lightW;

    int getLineType(int lineId) {
      lineId = (4 - 1 - (lineId % 4));
      return (packed >> (2 * lineId)) & 3;
    }

    void removeLineType(int lineId) {
      lineId = (4 - 1 - (lineId % 4));
      packed &= ~(3 << (2 * lineId));
    }

    Path pathForLine(int lineId) {
      return getLineType(lineId) == LtHeavy ? heavy : light;
    }

    static void moveTo(Path p, double px, double py) { p.moveTo((float) px, (float) py); }
    static void lineTo(Path p, double px, double py) { p.lineTo((float) px, (float) py); }

    // ╚-style shorter inner strand of a double corner.
    void drawDoubleUpRightShorterLine(int top, int right) {
      moveTo(light, origin[top][0] + dir[right][0] * doubleDist, origin[top][1] + dir[right][1] * doubleDist);
      lineTo(light, cx + (dir[right][0] + dir[top][0]) * doubleDist, cy + (dir[right][1] + dir[top][1]) * doubleDist);
      lineTo(light, origin[right][0] + dir[top][0] * doubleDist, origin[right][1] + dir[top][1] * doubleDist);
    }

    // └┗ corner: top edge to centre to right edge.
    void drawUpRight(int top, int right) {
      Path p = pathForLine(top);
      moveTo(p, origin[top][0], origin[top][1]);
      lineTo(p, cx, cy);
      lineTo(p, origin[right][0], origin[right][1]);
    }
  }

  // Packed-type case constants used by the switch below.
  private static final int C_H_N_L_N = mk(LtHeavy, LtNone, LtLight, LtNone);
  private static final int C_H_N_N_N = mk(LtHeavy, LtNone, LtNone, LtNone);
  private static final int C_L_N_N_N = mk(LtLight, LtNone, LtNone, LtNone);
  private static final int C_H_H_L_L = mk(LtHeavy, LtHeavy, LtLight, LtLight);
  private static final int C_H_H_N_N = mk(LtHeavy, LtHeavy, LtNone, LtNone);
  private static final int C_L_L_N_N = mk(LtLight, LtLight, LtNone, LtNone);
  private static final int C_H_L_N_N = mk(LtHeavy, LtLight, LtNone, LtNone);
  private static final int C_H_N_N_L = mk(LtHeavy, LtNone, LtNone, LtLight);
  private static final int C_L_D_N_N = mk(LtLight, LtDouble, LtNone, LtNone);
  private static final int C_L_N_N_D = mk(LtLight, LtNone, LtNone, LtDouble);
  private static final int C_H_H_L_N = mk(LtHeavy, LtHeavy, LtLight, LtNone);
  private static final int C_H_H_N_L = mk(LtHeavy, LtHeavy, LtNone, LtLight);
  private static final int C_H_L_L_N = mk(LtHeavy, LtLight, LtLight, LtNone);
  private static final int C_H_N_L_L = mk(LtHeavy, LtNone, LtLight, LtLight);
  private static final int C_L_D_N_D = mk(LtLight, LtDouble, LtNone, LtDouble);
  private static final int C_D_N_D_N = mk(LtDouble, LtNone, LtDouble, LtNone);
  private static final int C_D_N_N_N = mk(LtDouble, LtNone, LtNone, LtNone);
  private static final int C_D_D_D_D = mk(LtDouble, LtDouble, LtDouble, LtDouble);
  private static final int C_D_D_D_N = mk(LtDouble, LtDouble, LtDouble, LtNone);
  private static final int C_D_D_N_N = mk(LtDouble, LtDouble, LtNone, LtNone);

  private static boolean drawBasicLine(Canvas canvas, Paint paint, int x, int y, int w, int h, int code, boolean bold) {
    int packed = (code >= PACKED_LINE_TYPES.length) ? 0 : PACKED_LINE_TYPES[code];
    if (packed == 0) return false;

    final int lightLineWidth = lineWidth(w, false, bold);
    final int heavyLineWidth = lineWidth(w, true, bold);

    final BasicLineDrawer d = new BasicLineDrawer();
    d.packed = packed;
    d.lightW = lightLineWidth;
    d.doubleDist = lightLineWidth;

    // Pixel-aligned centre point (half-pixel offset for odd line widths).
    d.cx = x + (w / 2) + 0.5 * (lightLineWidth % 2);
    d.cy = y + (h / 2) + 0.5 * (lightLineWidth % 2);

    // Line origins on the cell edges: top, right, bottom, left.
    d.origin[0][0] = d.cx;      d.origin[0][1] = y;
    d.origin[1][0] = x + w;     d.origin[1][1] = d.cy;
    d.origin[2][0] = d.cx;      d.origin[2][1] = y + h;
    d.origin[3][0] = x;         d.origin[3][1] = d.cy;
    final double[][] dir = BasicLineDrawer.dir;

    // Fully draw single up-down / left-right strokes, removing them so the switch
    // below only handles corners/tees/crosses. Draws: ╋ ╂ ┃ ┿ ┼ │ ━ ─
    for (int topIndex = 0; topIndex < 2; topIndex++) {
      int iB = (topIndex + 2) % 4;
      int t = d.getLineType(topIndex);
      boolean isSingleLine = (t == LtLight || t == LtHeavy);
      if (isSingleLine && t == d.getLineType(iB)) {
        Path p = d.pathForLine(topIndex);
        BasicLineDrawer.moveTo(p, d.origin[topIndex][0], d.origin[topIndex][1]);
        BasicLineDrawer.lineTo(p, d.origin[iB][0], d.origin[iB][1]);
        d.removeLineType(topIndex);
        d.removeLineType(iB);
      }
    }

    // Pick the base rotation (largest packed value) so one set of cases covers all
    // four rotations of each shape.
    int topIndex = 0;
    int basePacked = d.packed;
    for (int i = 0; i < 4; i++) {
      int rotated = rotl8(d.packed, i * 2);
      if (rotated > basePacked) {
        topIndex = i;
        basePacked = rotated;
      }
    }
    int rightIndex = (topIndex + 1) % 4;
    int bottomIndex = (topIndex + 2) % 4;
    int leftIndex = (topIndex + 3) % 4;

    final double lightW = lightLineWidth;
    final double dd = d.doubleDist;

    if (basePacked == C_H_N_L_N) {
      BasicLineDrawer.moveTo(d.light, d.origin[bottomIndex][0], d.origin[bottomIndex][1]);
      BasicLineDrawer.lineTo(d.light, d.cx + dir[topIndex][0] * lightW / 2.0, d.cy + dir[topIndex][1] * lightW / 2.0);
      // fallthrough
      Path p = d.pathForLine(topIndex);
      BasicLineDrawer.moveTo(p, d.origin[topIndex][0], d.origin[topIndex][1]);
      BasicLineDrawer.lineTo(p, d.cx, d.cy);
    } else if (basePacked == C_H_N_N_N || basePacked == C_L_N_N_N) {
      Path p = d.pathForLine(topIndex);
      BasicLineDrawer.moveTo(p, d.origin[topIndex][0], d.origin[topIndex][1]);
      BasicLineDrawer.lineTo(p, d.cx, d.cy);
    } else if (basePacked == C_H_H_L_L) {
      d.drawUpRight(bottomIndex, leftIndex);
      d.drawUpRight(topIndex, rightIndex);
    } else if (basePacked == C_H_H_N_N || basePacked == C_L_L_N_N) {
      d.drawUpRight(topIndex, rightIndex);
    } else if (basePacked == C_H_L_N_N || basePacked == C_H_N_N_L) {
      if (basePacked == C_H_L_N_N) { int t = leftIndex; leftIndex = rightIndex; rightIndex = t; }
      BasicLineDrawer.moveTo(d.light, d.origin[leftIndex][0], d.origin[leftIndex][1]);
      BasicLineDrawer.lineTo(d.light, d.cx, d.cy);
      BasicLineDrawer.moveTo(d.heavy, d.origin[topIndex][0], d.origin[topIndex][1]);
      BasicLineDrawer.lineTo(d.heavy, d.cx + dir[bottomIndex][0] * lightW / 2.0, d.cy + dir[bottomIndex][1] * lightW / 2.0);
    } else if (basePacked == C_L_D_N_N || basePacked == C_L_N_N_D) {
      if (basePacked == C_L_D_N_N) { int t = leftIndex; leftIndex = rightIndex; rightIndex = t; }
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0], d.origin[topIndex][1]);
      BasicLineDrawer.lineTo(d.light, d.cx + dir[bottomIndex][0] * dd, d.cy + dir[bottomIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.origin[leftIndex][0] + dir[bottomIndex][0] * dd, d.origin[leftIndex][1] + dir[bottomIndex][1] * dd);
      BasicLineDrawer.moveTo(d.light, d.origin[leftIndex][0] - dir[bottomIndex][0] * dd, d.origin[leftIndex][1] - dir[bottomIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.cx - dir[bottomIndex][0] * dd, d.cy - dir[bottomIndex][1] * dd);
    } else if (basePacked == C_H_H_L_N || basePacked == C_H_H_N_L) {
      if (basePacked == C_H_H_L_N) {
        int t = leftIndex; leftIndex = bottomIndex; bottomIndex = t;
        t = rightIndex; rightIndex = topIndex; topIndex = t;
      }
      d.drawUpRight(topIndex, rightIndex);
      BasicLineDrawer.moveTo(d.light, d.origin[leftIndex][0], d.origin[leftIndex][1]);
      BasicLineDrawer.lineTo(d.light, d.cx, d.cy);
    } else if (basePacked == C_H_L_L_N || basePacked == C_H_N_L_L) {
      if (basePacked == C_H_L_L_N) { int t = leftIndex; leftIndex = rightIndex; rightIndex = t; }
      BasicLineDrawer.moveTo(d.heavy, d.origin[topIndex][0], d.origin[topIndex][1]);
      BasicLineDrawer.lineTo(d.heavy, d.cx + dir[bottomIndex][0] * lightW / 2.0, d.cy + dir[bottomIndex][1] * lightW / 2.0);
      d.drawUpRight(bottomIndex, leftIndex);
    } else if (basePacked == C_L_D_N_D || basePacked == C_D_N_D_N) {
      if (basePacked == C_L_D_N_D) {
        BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0], d.origin[topIndex][1]);
        BasicLineDrawer.lineTo(d.light, d.cx - dir[bottomIndex][0] * dd, d.cy - dir[bottomIndex][1] * dd);
        int t = leftIndex; leftIndex = bottomIndex; bottomIndex = t;
        t = rightIndex; rightIndex = topIndex; topIndex = t;
      }
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[leftIndex][0] * dd, d.origin[topIndex][1] + dir[leftIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.origin[bottomIndex][0] + dir[leftIndex][0] * dd, d.origin[bottomIndex][1] + dir[leftIndex][1] * dd);
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[rightIndex][0] * dd, d.origin[topIndex][1] + dir[rightIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.origin[bottomIndex][0] + dir[rightIndex][0] * dd, d.origin[bottomIndex][1] + dir[rightIndex][1] * dd);
    } else if (basePacked == C_D_N_N_N) {
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[leftIndex][0] * dd, d.origin[topIndex][1] + dir[leftIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.cx + dir[leftIndex][0] * dd, d.cy + dir[leftIndex][1] * dd);
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[rightIndex][0] * dd, d.origin[topIndex][1] + dir[rightIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.cx + dir[rightIndex][0] * dd, d.cy + dir[rightIndex][1] * dd);
    } else if (basePacked == C_D_D_D_D) {
      d.drawDoubleUpRightShorterLine(topIndex, rightIndex);
      d.drawDoubleUpRightShorterLine(bottomIndex, rightIndex);
      d.drawDoubleUpRightShorterLine(topIndex, leftIndex);
      d.drawDoubleUpRightShorterLine(bottomIndex, leftIndex);
    } else if (basePacked == C_D_D_D_N) {
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[leftIndex][0] * dd, d.origin[topIndex][1] + dir[leftIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.origin[bottomIndex][0] + dir[leftIndex][0] * dd, d.origin[bottomIndex][1] + dir[leftIndex][1] * dd);
      d.drawDoubleUpRightShorterLine(topIndex, rightIndex);
      d.drawDoubleUpRightShorterLine(bottomIndex, rightIndex);
    } else if (basePacked == C_D_D_N_N) {
      BasicLineDrawer.moveTo(d.light, d.origin[topIndex][0] + dir[leftIndex][0] * dd, d.origin[topIndex][1] + dir[leftIndex][1] * dd);
      BasicLineDrawer.lineTo(d.light, d.cx + (dir[leftIndex][0] + dir[bottomIndex][0]) * dd, d.cy + (dir[leftIndex][1] + dir[bottomIndex][1]) * dd);
      BasicLineDrawer.lineTo(d.light, d.origin[rightIndex][0] + dir[bottomIndex][0] * dd, d.origin[rightIndex][1] + dir[bottomIndex][1] * dd);
      d.drawDoubleUpRightShorterLine(topIndex, rightIndex);
    }

    if (!d.light.isEmpty()) strokePath(canvas, paint, d.light, lightLineWidth);
    if (!d.heavy.isEmpty()) strokePath(canvas, paint, d.heavy, heavyLineWidth);
    return true;
  }

  // -- dashed lines (┄ ┅ ┆ ┇ ┈ ┉ ┊ ┋ ╌ ╍ ╎ ╏) --------------------------------

  private static boolean drawDashedLine(Canvas canvas, Paint paint, int x, int y, int w, int h, int code, boolean bold) {
    if (!((0x04 <= code && code <= 0x0B) || (0x4C <= code && code <= 0x4F))) return false;

    final int lightLineWidth = lineWidth(w, false, bold);
    final int heavyLineWidth = lineWidth(w, true, bold);

    final double cx = (int) (x + w / 2.0) + 0.5 * (lightLineWidth % 2);
    final double cy = (int) (y + h / 2.0) + 0.5 * (lightLineWidth % 2);

    final double halfGapH = Math.max(w / 20.0, 0.5);
    final double halfGapV = Math.max(h / 26.0, 0.5);
    final double halfGapDDV = Math.max(h / 14.0, 0.5); // vertical double dash has a bigger gap

    int linesNum;
    boolean horizontal;
    int penWidth;
    double halfGap;
    switch (code) {
      case 0x4C: linesNum = 2; horizontal = true;  penWidth = lightLineWidth; halfGap = halfGapH;   break; // ╌
      case 0x4D: linesNum = 2; horizontal = true;  penWidth = heavyLineWidth; halfGap = halfGapH;   break; // ╍
      case 0x4E: linesNum = 2; horizontal = false; penWidth = lightLineWidth; halfGap = halfGapDDV; break; // ╎
      case 0x4F: linesNum = 2; horizontal = false; penWidth = heavyLineWidth; halfGap = halfGapDDV; break; // ╏
      case 0x04: linesNum = 3; horizontal = true;  penWidth = lightLineWidth; halfGap = halfGapH;   break; // ┄
      case 0x05: linesNum = 3; horizontal = true;  penWidth = heavyLineWidth; halfGap = halfGapH;   break; // ┅
      case 0x06: linesNum = 3; horizontal = false; penWidth = lightLineWidth; halfGap = halfGapV;   break; // ┆
      case 0x07: linesNum = 3; horizontal = false; penWidth = heavyLineWidth; halfGap = halfGapV;   break; // ┇
      case 0x08: linesNum = 4; horizontal = true;  penWidth = lightLineWidth; halfGap = halfGapH;   break; // ┈
      case 0x09: linesNum = 4; horizontal = true;  penWidth = heavyLineWidth; halfGap = halfGapH;   break; // ┉
      case 0x0A: linesNum = 4; horizontal = false; penWidth = lightLineWidth; halfGap = halfGapV;   break; // ┊
      default:   linesNum = 4; horizontal = false; penWidth = heavyLineWidth; halfGap = halfGapV;   break; // 0x0B ┋
    }

    final double size = horizontal ? w : h;
    final double pos = horizontal ? x : y;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(penWidth);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);
    for (int i = 0; i < linesNum; i++) {
      final double start = pos + size * i / linesNum;
      final double end = pos + size * (i + 1) / linesNum;
      if (horizontal) {
        canvas.drawLine((float) (start + halfGap), (float) cy, (float) (end - halfGap), (float) cy, paint);
      } else {
        canvas.drawLine((float) cx, (float) (start + halfGap), (float) cx, (float) (end - halfGap), paint);
      }
    }
    return true;
  }

  // -- rounded corners (╭ ╮ ╯ ╰) ----------------------------------------------

  private static boolean drawRoundedCorner(Canvas canvas, Paint paint, int x, int y, int w, int h, int code, boolean bold) {
    if (!(0x6D <= code && code <= 0x70)) return false;

    final int lightLineWidth = lineWidth(w, false, bold);
    final double cx = (int) (x + w / 2.0) + 0.5 * (lightLineWidth % 2);
    final double cy = (int) (y + h / 2.0) + 0.5 * (lightLineWidth % 2);

    final int r = w * 3 / 8;
    final int dDiam = 2 * r;

    final Path path = new Path();
    final RectF oval = new RectF();
    // Qt measures arc angles counter-clockwise on a y-down canvas; Android measures
    // them clockwise, so each angle is negated relative to Konsole's call.
    switch (code) {
      case 0x6D: // ╭ arc down and right
        path.moveTo((float) cx, (float) (y + h));
        oval.set((float) cx, (float) cy, (float) (cx + dDiam), (float) (cy + dDiam));
        path.arcTo(oval, 180, 90);
        path.lineTo((float) (x + w), (float) cy);
        break;
      case 0x6E: // ╮ arc down and left
        path.moveTo((float) cx, (float) (y + h));
        oval.set((float) (cx - dDiam), (float) cy, (float) cx, (float) (cy + dDiam));
        path.arcTo(oval, 0, -90);
        path.lineTo((float) x, (float) cy);
        break;
      case 0x6F: // ╯ arc up and left
        path.moveTo((float) cx, (float) y);
        oval.set((float) (cx - dDiam), (float) (cy - dDiam), (float) cx, (float) cy);
        path.arcTo(oval, 0, 90);
        path.lineTo((float) x, (float) cy);
        break;
      default: // 0x70 ╰ arc up and right
        path.moveTo((float) cx, (float) y);
        oval.set((float) cx, (float) (cy - dDiam), (float) (cx + dDiam), (float) cy);
        path.arcTo(oval, 180, -90);
        path.lineTo((float) (x + w), (float) cy);
        break;
    }
    strokePath(canvas, paint, path, lightLineWidth);
    return true;
  }

  // -- diagonals (╱ ╲ ╳) ------------------------------------------------------

  private static boolean drawDiagonal(Canvas canvas, Paint paint, int x, int y, int w, int h, int code, boolean bold) {
    if (!(0x71 <= code && code <= 0x73)) return false;

    final int lightLineWidth = lineWidth(w, false, bold);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(lightLineWidth);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);
    if (code == 0x71 || code == 0x73) { // '/'
      canvas.drawLine(x + w, y, x, y + h, paint);
    }
    if (code == 0x72 || code == 0x73) { // '\'
      canvas.drawLine(x, y, x + w, y + h, paint);
    }
    return true;
  }

  // -- block elements & shades (█ ▀ ▄ ▌ ▐ ░ ▒ ▓ ▖▗▘▙▚▛▜▝▞▟ …) -----------------

  private static boolean drawBlock(Canvas canvas, Paint paint, int x, int y, int w, int h, int code) {
    paint.setStyle(Paint.Style.FILL);
    final double centerX = x + w / 2.0;
    final double centerY = y + h / 2.0;
    final int color = paint.getColor();

    // U+2581..U+258F: lower/left eighth blocks (growing bars).
    if (code >= 0x81 && code <= 0x8F) {
      double rx = x, ry = y, rw = w, rh = h;
      if (code < 0x88) { // horizontal (grows up from the bottom)
        final double height = h * (0x88 - code) / 8.0;
        ry = y + height;
        rh = h - height;
      } else if (code > 0x88) { // vertical (grows from the left)
        rw = w * (0x90 - code) / 8.0;
      }
      canvas.drawRect((float) rx, (float) ry, (float) (rx + rw), (float) (ry + rh), paint);
      return true;
    }

    // U+2596..U+259F: quadrant combinations.
    if (code >= 0x96 && code <= 0x9F) {
      final Path path = new Path();
      final RectF ul = new RectF(x, y, (float) centerX, (float) centerY);
      final RectF ur = new RectF((float) centerX, y, x + w, (float) centerY);
      final RectF ll = new RectF(x, (float) centerY, (float) centerX, y + h);
      final RectF lr = new RectF((float) centerX, (float) centerY, x + w, y + h);
      switch (code) {
        case 0x96: addRect(path, ll); break;                          // ▖
        case 0x97: addRect(path, lr); break;                          // ▗
        case 0x98: addRect(path, ul); break;                          // ▘
        case 0x99: addRect(path, ul); addRect(path, ll); addRect(path, lr); break; // ▙
        case 0x9A: addRect(path, ul); addRect(path, lr); break;       // ▚
        case 0x9B: addRect(path, ul); addRect(path, ur); addRect(path, ll); break; // ▛
        case 0x9C: addRect(path, ul); addRect(path, ur); addRect(path, lr); break; // ▜
        case 0x9D: addRect(path, ur); break;                          // ▝
        case 0x9E: addRect(path, ur); addRect(path, ll); break;       // ▞
        default:   addRect(path, ur); addRect(path, ll); addRect(path, lr); break; // 0x9F ▟
      }
      canvas.drawPath(path, paint);
      return true;
    }

    switch (code) {
      case 0x80: // ▀ top half block
        canvas.drawRect(x, y, x + w, (float) (y + h / 2.0), paint);
        return true;
      case 0x90: // ▐ right half block
        canvas.drawRect((float) centerX, y, x + w, y + h, paint);
        return true;
      case 0x94: // ▔ top one eighth block
        canvas.drawRect(x, y, x + w, (float) (y + h / 8.0), paint);
        return true;
      case 0x95: // ▕ right one eighth block
        canvas.drawRect((float) (x + 7 * w / 8.0), y, x + w, y + h, paint);
        return true;
      case 0x91: // ░ light shade
        fillShade(canvas, paint, x, y, w, h, color, 64);
        return true;
      case 0x92: // ▒ medium shade
        fillShade(canvas, paint, x, y, w, h, color, 128);
        return true;
      case 0x93: // ▓ dark shade
        fillShade(canvas, paint, x, y, w, h, color, 192);
        return true;
      default:
        return false;
    }
  }

  private static void addRect(Path path, RectF r) {
    path.addRect(r, Path.Direction.CW);
  }

  /** Fill the cell with {@code color} at the given alpha (antialiased shade). */
  private static void fillShade(Canvas canvas, Paint paint, int x, int y, int w, int h, int color, int alpha) {
    paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
    canvas.drawRect(x, y, x + w, y + h, paint);
    paint.setColor(color);
  }

  // -- Braille (U+2800..U+28FF): 2×4 dot matrix -------------------------------

  private static void drawBraille(Canvas canvas, Paint paint, int x, int y, int w, int h, int code) {
    paint.setStyle(Paint.Style.FILL);
    final float hw = w / 2.0f;
    final float qh = h / 4.0f;
    // Dot bit order matches Konsole: column-major within each half, rows 0..3.
    final float[][] rects = {
      {x, y, hw, qh},
      {x, y + qh, hw, qh},
      {x, y + 2 * qh, hw, qh},
      {x + hw, y, hw, qh},
      {x + hw, y + qh, hw, qh},
      {x + hw, y + 2 * qh, hw, qh},
      {x, y + 3 * qh, hw, qh},
      {x + hw, y + 3 * qh, hw, qh},
    };
    final Path path = new Path();
    for (int i = 0; i < 8; i++) {
      if ((code & (1 << i)) != 0) {
        final float[] r = rects[i];
        path.addRect(r[0], r[1], r[0] + r[2], r[1] + r[3], Path.Direction.CW);
      }
    }
    canvas.drawPath(path, paint);
  }
}
