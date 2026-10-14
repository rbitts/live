package dev.live.offline;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Small native view palette: no network assets or UI runtime dependency. */
final class LiveTheme {
  static final int BG = Color.rgb(10, 18, 29), SURFACE = Color.rgb(22, 35, 50);
  static final int TEXT = Color.rgb(242, 246, 249), MUTED = Color.rgb(173, 189, 204);
  static final int MINT = Color.rgb(126, 225, 201), LINE = Color.rgb(45, 65, 82);
  static final int WARNING = Color.rgb(255, 204, 148);
  final Context context;

  LiveTheme(Context context) {
    this.context = context;
  }

  int dp(float value) {
    return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radius));
    return drawable;
  }

  LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setFontFeatureSettings("kern");
    view.setLineSpacing(dp(3), 1);
    view.setPadding(0, dp(5), 0, dp(5));
    return view;
  }

  TextView title(String value) {
    TextView view = text(value, 28, TEXT);
    view.setTypeface(null, Typeface.BOLD);
    return view;
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout card = column();
    card.setPadding(dp(18), dp(16), dp(18), dp(16));
    card.setBackground(shape(SURFACE, 22));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(14);
    parent.addView(card, lp);
    return card;
  }

  Button button(LinearLayout parent, String label, boolean primary, View.OnClickListener action) {
    Button button = new Button(context);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(15);
    button.setTypeface(null, Typeface.BOLD);
    button.setTextColor(primary ? BG : TEXT);
    button.setBackgroundTintList(null);
    button.setBackground(shape(primary ? MINT : LINE, 16));
    button.setMinHeight(dp(52));
    button.setPadding(dp(12), dp(10), dp(12), dp(10));
    button.setOnClickListener(action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(10);
    parent.addView(button, lp);
    return button;
  }

  final class IconButton extends ImageButton {
    private String appearance = "";
    private String badge = "";
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    IconButton(String glyph, String label, boolean selected, View.OnClickListener action) {
      super(context);
      setMinimumWidth(dp(52));
      setMinimumHeight(dp(52));
      setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      setPadding(dp(14), dp(14), dp(14), dp(14));
      setOnClickListener(action);
      setTag(label);
      update(glyph, label, selected, false);
    }

    void update(String glyph, String label, boolean selected, boolean warning) {
      String next = glyph + selected + warning;
      if (!next.equals(appearance)) {
        setImageDrawable(new LiveIcon(glyph, warning ? WARNING : selected ? BG : TEXT));
        setBackground(
            new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x447EE1C9),
                shape(selected ? MINT : SURFACE, 24),
                null));
        appearance = next;
      }
      if (!label.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
        setContentDescription(label);
        setTooltipText(label);
      }
      setSelected(selected);
    }

    void badge(String value) {
      if (!badge.equals(value)) {
        badge = value;
        invalidate();
      }
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (badge.isEmpty()) return;
      float x = getWidth() - dp(11), y = dp(11);
      badgePaint.setColor(MINT);
      canvas.drawCircle(x, y, dp(8), badgePaint);
      if (!badge.equals("•")) {
        badgePaint.setColor(BG);
        badgePaint.setTextSize(dp(10));
        badgePaint.setTypeface(Typeface.DEFAULT_BOLD);
        badgePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(badge, x, y + dp(3.5f), badgePaint);
      }
    }
  }

  IconButton icon(String glyph, String label, boolean selected, View.OnClickListener action) {
    return new IconButton(glyph, label, selected, action);
  }

  IconButton iconRow(
      LinearLayout parent,
      String glyph,
      String label,
      boolean selected,
      View.OnClickListener action) {
    IconButton button = icon(glyph, label, selected, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
    lp.setMargins(dp(5), dp(5), dp(5), dp(5));
    parent.addView(button, lp);
    return button;
  }

  TextView chip(
      LinearLayout row,
      String value,
      String explanation,
      boolean selected,
      View.OnClickListener action) {
    TextView chip = text(value, 14, selected ? BG : TEXT);
    chip.setGravity(Gravity.CENTER);
    chip.setMinWidth(dp(52));
    chip.setMinHeight(dp(48));
    chip.setPadding(dp(12), dp(8), dp(12), dp(8));
    chip.setBackground(shape(selected ? MINT : SURFACE, 18));
    chip.setContentDescription(explanation);
    chip.setTooltipText(explanation);
    chip.setSelected(selected);
    chip.setOnClickListener(action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    row.addView(chip, lp);
    return chip;
  }

  View orbit() {
    View view =
        new View(context) {
          final Paint paint = new Paint(3);

          @Override
          protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f, r = Math.min(x, y) * .72f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(LINE);
            canvas.drawCircle(x, y, r * 1.35f, paint);
            canvas.drawCircle(x, y, r, paint);
            paint.setColor(MINT);
            paint.setStrokeWidth(dp(2));
            canvas.drawCircle(x, y, r * .73f, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 7; i++) {
              float h = dp(8 + (3 - Math.abs(3 - i)) * 9);
              float left = x + dp((i - 3) * 11) - dp(2);
              canvas.drawRoundRect(left, y - h / 2, left + dp(4), y + h / 2, dp(3), dp(3), paint);
            }
            canvas.drawCircle(x + r, y, dp(4), paint);
          }
        };
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return view;
  }

  interface OrbSignal {
    int mode(); // 0 idle, 1 listening, 2 preparing, 3 actual audio playback
    float level();
  }

  View assistant(OrbSignal signal) {
    View view = new View(context) {
      final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      float level, phase;
      long lastFrame;

      @Override protected void onDraw(Canvas canvas) {
        int mode = signal.mode();
        boolean animate = mode == 3 && android.animation.ValueAnimator.areAnimatorsEnabled();
        long now = android.os.SystemClock.uptimeMillis();
        float target = animate ? signal.level() : 0;
        // Only played PCM drives motion: no idle/listening/thinking oscillation.
        if (target > .015f) {
          level += (target - level) * .35f;
          if (lastFrame > 0) phase += Math.min(.05f, (now - lastFrame) / 1000f) * (.65f + level);
        } else level = 0;
        lastFrame = animate ? now : 0;
        float x = getWidth() / 2f, y = getHeight() / 2f;
        float radius = Math.min(x, y) * .53f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(LINE);
        canvas.drawCircle(x, y, radius * 1.43f, paint);
        paint.setColor(MINT);
        paint.setStrokeWidth(dp(2.2f));
        for (int i = 0; i < 72; i++) {
          float angle = (float) (i * Math.PI * 2 / 72);
          float wave = .5f + .3f * (float) Math.sin(angle * 3 + phase * 2)
              + .2f * (float) Math.sin(angle * 7 - phase * 3);
          float half = dp(1.5f + level * (3 + wave * 14));
          float radial = radius + dp(level * 3) * (float) Math.sin(angle * 4 - phase);
          float cosine = (float) Math.cos(angle + phase), sine = (float) Math.sin(angle + phase);
          canvas.drawLine(x + (radial - half) * cosine, y + (radial - half) * sine,
              x + (radial + half) * cosine, y + (radial + half) * sine, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 9; i++) {
          float wave = .5f + .5f * (float) Math.sin(phase * (5 + i * .63f) + i * 1.7f);
          float base = 6 + (4 - Math.abs(4 - i)) * 3;
          float height = dp(base + level * (7 + wave * 35));
          float left = x + dp((i - 4) * 10) - dp(2.5f);
          canvas.drawRoundRect(left, y - height / 2, left + dp(5), y + height / 2,
              dp(3), dp(3), paint);
        }
        if (animate && isAttachedToWindow() && getWindowVisibility() == VISIBLE)
          postInvalidateDelayed(33);
      }
    };
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return view;
  }
}
