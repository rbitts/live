package dev.live.offline;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Consistent 24-unit native icons; no fonts, emoji, or downloaded assets. */
final class LiveIcon extends Drawable {
  private final String name;
  private final int color;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  LiveIcon(String name, int color) {
    this.name = name;
    this.color = color;
  }

  private void line(Canvas c, float x, float y, float xx, float yy) {
    c.drawLine(x, y, xx, yy, p);
  }

  private void path(Canvas c, float... points) {
    Path path = new Path();
    path.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    c.drawPath(path, p);
  }

  @Override
  public void draw(Canvas canvas) {
    canvas.save();
    Rect b = getBounds();
    canvas.translate(b.left, b.top);
    canvas.scale(b.width() / 24f, b.height() / 24f);
    p.setColor(color);
    p.setStrokeWidth(1.7f);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    p.setStyle(Paint.Style.STROKE);
    String n = name.replace("-off", "");
    switch (n) {
      case "cook":
        canvas.drawRoundRect(5, 10, 19, 21, 3, 3, p);
        line(canvas, 3, 10, 21, 10); line(canvas, 2, 13, 5, 13); line(canvas, 19, 13, 22, 13);
        path(canvas, 8, 7, 9, 5, 8, 3); path(canvas, 15, 7, 16, 5, 15, 3);
        break;
      case "bell":
        canvas.drawArc(6, 4, 18, 18, 180, 180, false, p);
        canvas.drawLine(6, 11, 6, 17, p);
        canvas.drawLine(18, 11, 18, 17, p);
        canvas.drawLine(4, 18, 20, 18, p);
        canvas.drawArc(10, 18, 14, 22, 0, 180, false, p);
        break;
      case "mic":
        canvas.drawRoundRect(9, 3, 15, 14, 3, 3, p);
        canvas.drawArc(6, 7, 18, 18, 0, 180, false, p);
        line(canvas, 12, 18, 12, 22);
        line(canvas, 9, 22, 15, 22);
        break;
      case "camera":
        canvas.drawRoundRect(3, 6, 21, 20, 3, 3, p);
        path(canvas, 8, 6, 10, 3, 14, 3, 16, 6);
        canvas.drawCircle(12, 13, 4, p);
        break;
      case "volume":
        path(canvas, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
        canvas.drawArc(10, 5, 22, 19, -60, 120, false, p);
        canvas.drawArc(12, 8, 18, 16, -60, 120, false, p);
        break;
      case "stop":
        canvas.drawRoundRect(6, 6, 18, 18, 2, 2, p);
        break;
      case "close":
        line(canvas, 6, 6, 18, 18);
        line(canvas, 18, 6, 6, 18);
        break;
      case "pause":
        line(canvas, 9, 5, 9, 19);
        line(canvas, 15, 5, 15, 19);
        break;
      case "play":
        path(canvas, 9, 5, 19, 12, 9, 19, 9, 5);
        break;
      case "arrow":
        path(canvas, 9, 5, 16, 12, 9, 19);
        break;
      case "back":
        path(canvas, 15, 5, 8, 12, 15, 19);
        break;
      case "wave":
        for (int i = 0; i < 5; i++) {
          float h = new float[] {4, 10, 16, 10, 4}[i];
          line(canvas, 4 + i * 4, 12 - h / 2, 4 + i * 4, 12 + h / 2);
        }
        break;
      case "memory":
        canvas.drawRoundRect(6, 3, 21, 18, 3, 3, p);
        path(canvas, 3, 7, 3, 21, 17, 21);
        canvas.drawCircle(13.5f, 10.5f, 3, p);
        break;
      case "settings":
        canvas.drawCircle(12, 12, 7, p);
        canvas.drawCircle(12, 12, 2.5f, p);
        for (int i = 0; i < 8; i++) {
          canvas.save();
          canvas.rotate(i * 45, 12, 12);
          line(canvas, 12, 2, 12, 4);
          canvas.restore();
        }
        break;
      case "bubble":
        path(canvas, 5, 4, 19, 4, 21, 6, 21, 16, 19, 18, 10, 18, 5, 22, 5, 18, 3, 16, 3, 6, 5, 4);
        line(canvas, 8, 9, 16, 9);
        line(canvas, 8, 13, 13, 13);
        break;
      case "keyboard":
        canvas.drawRoundRect(2, 5, 22, 19, 2, 2, p);
        for (int i = 0; i < 4; i++) {
          line(canvas, 6 + i * 4, 9, 6 + i * 4, 9.2f);
          line(canvas, 6 + i * 4, 12, 6 + i * 4, 12.2f);
        }
        line(canvas, 8, 16, 16, 16);
        break;
      case "download":
        path(canvas, 12, 3, 12, 15);
        path(canvas, 7, 10, 12, 15, 17, 10);
        path(canvas, 4, 16, 4, 21, 20, 21, 20, 16);
        break;
      case "shield":
        path(canvas, 12, 2, 21, 6, 20, 15, 17, 19, 12, 22, 7, 19, 4, 15, 3, 6, 12, 2);
        path(canvas, 8, 12, 11, 15, 16, 9);
        break;
      case "clock":
        canvas.drawCircle(12, 12, 9, p);
        path(canvas, 12, 6, 12, 12, 16, 14);
        break;
      case "check":
        canvas.drawCircle(12, 12, 9, p);
        path(canvas, 7, 12, 10, 15, 17, 8);
        break;
      case "alert":
        path(canvas, 12, 3, 22, 21, 2, 21, 12, 3);
        line(canvas, 12, 9, 12, 13);
        line(canvas, 12, 17, 12, 17.3f);
        break;
      case "info":
        canvas.drawCircle(12, 12, 9, p);
        line(canvas, 12, 10, 12, 17);
        line(canvas, 12, 6.5f, 12, 6.7f);
        break;
      case "hourglass":
        path(canvas, 6, 3, 18, 3, 18, 7, 6, 17, 6, 21, 18, 21, 18, 17, 6, 7, 6, 3);
        break;
      case "trash":
        path(canvas, 5, 7, 6, 21, 18, 21, 19, 7);
        line(canvas, 3, 6, 21, 6);
        path(canvas, 9, 6, 9, 3, 15, 3, 15, 6);
        line(canvas, 10, 10, 10, 17);
        line(canvas, 14, 10, 14, 17);
        break;
      case "send":
        path(canvas, 3, 3, 22, 12, 3, 21, 6, 12, 3, 3);
        line(canvas, 6, 12, 16, 12);
        break;
      case "folder":
        path(canvas, 3, 6, 3, 20, 21, 20, 21, 8, 12, 8, 10, 4, 3, 4, 3, 6);
        break;
      case "retry":
        canvas.drawArc(4, 4, 20, 20, -40, 295, false, p);
        path(canvas, 20, 3, 20, 8, 15, 8);
        break;
      default:
        canvas.drawCircle(12, 12, 4, p);
    }
    if (name.endsWith("-off")) {
      p.setColor(LiveTheme.SURFACE);
      p.setStrokeWidth(4.5f);
      line(canvas, 3, 3, 21, 21);
      p.setColor(color);
      p.setStrokeWidth(1.7f);
      line(canvas, 3, 3, 21, 21);
    }
    canvas.restore();
  }

  @Override
  public void setAlpha(int alpha) {
    p.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    p.setColorFilter(filter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
