package dev.live.offline;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;

/** Foreground diagnostics only; keeps Android scheduling comparable to the app. */
public final class ProfileActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    TextView view = new TextView(this);
    view.setText("성능 측정 중"); view.setTextSize(22); view.setGravity(Gravity.CENTER);
    view.setTextColor(LiveTheme.MINT);view.setBackgroundColor(LiveTheme.BG);
    setContentView(view);
  }
}
