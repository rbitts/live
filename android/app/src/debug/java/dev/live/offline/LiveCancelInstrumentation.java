package dev.live.offline;

import android.app.*;
import android.content.Intent;
import android.media.AudioTrack;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Cancel actual TTS after its first PCM callback; capture must continue. */
public final class LiveCancelInstrumentation extends Instrumentation {
  private MainActivity activity;
  private boolean duringPlayback;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    duringPlayback = "playback".equals(args.getString("phase"));
    start();
  }

  private Object field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private void set(String name, Object value) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(activity, value);
  }

  private Object call(String name, Class<?>[] types, Object... values) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(activity, values);
  }

  private View find(View v, String label) {
    if (label.equals(v.getTag())
        || label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
      return v;
    if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View match = find(((ViewGroup) v).getChildAt(i), label);
        if (match != null) return match;
      }
    return null;
  }

  private void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      ThreadPoolExecutor inference = (ThreadPoolExecutor) field("inference");
      long deadline = SystemClock.elapsedRealtime() + 180000;
      while (!(Boolean) field("loaded") && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(200);
      require((Boolean) field("loaded"), "model not loaded");
      runOnMainSync(
          () -> {
            try {
              set("page", "live");
              set("cameraEnabled", false);
              call("render", new Class<?>[] {});
              call("startCapture", new Class<?>[] {});
              long id = (Long) call("invalidate", new Class<?>[] {});
              call(
                  "submit",
                  new Class<?>[] {byte[].class, String.class, long.class, long.class},
                  null,
                  "지금 컵은 어디 있나요?",
                  id,
                  SystemClock.elapsedRealtime());
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      deadline = SystemClock.elapsedRealtime() + 180000;
      while ((Long) field(duringPlayback ? "firstPlaybackMs" : "firstAudioMs") < 0
          && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(30);
      require((Long) field(duringPlayback ? "firstPlaybackMs" : "firstAudioMs") >= 0, "no real TTS output");
      if (!duringPlayback) require(inference.getActiveCount() > 0, "must interrupt ongoing inference");
      else require(((AudioTrack) field("track")).getPlayState() == AudioTrack.PLAYSTATE_PLAYING,
          "must interrupt actual playback");
      result.putString("cancel_phase", duringPlayback ? "actual playback" : "generation and buffering");
      result.putBoolean("model_active_before_cancel", inference.getActiveCount() > 0);
      long old = ((AtomicLong) field("epoch")).get();
      Capture capture = (Capture) field("capture");
      long before = capture.capturedSamples.get();
      long start = SystemClock.elapsedRealtime();
      runOnMainSync(() -> find(activity.getWindow().getDecorView(), "응답 즉시 중단").performClick());
      long flush = SystemClock.elapsedRealtime() - start;
      require(((AtomicLong) field("epoch")).get() > old, "epoch not invalidated");
      require(((ArrayBlockingQueue<?>) field("playback")).isEmpty(), "queued PCM retained");
      require(
          ((AudioTrack) field("track")).getPlayState() != AudioTrack.PLAYSTATE_PLAYING,
          "playback not stopped");
      require(!activity.onPcm(old, new float[480]), "late audio accepted");
      deadline = SystemClock.elapsedRealtime() + 20000;
      while (inference.getActiveCount() > 0 && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(30);
      require(inference.getActiveCount() == 0, "cooperative cancel timeout");
      long cancel = SystemClock.elapsedRealtime() - start;
      Thread.sleep(300);
      require(
          (Boolean) field("listening") && capture.capturedSamples.get() > before,
          "microphone stopped during interrupt");
      result.putString("result", "PASS");
      result.putLong("button_to_track_paused_ms", flush);
      result.putLong("native_cancel_ms", cancel);
      result.putLong("mic_samples_after_cancel", capture.capturedSamples.get() - before);
      result.putString(
          "scope", "One actual Android TTS interruption; not human acoustic latency or p95");
    } catch (Throwable e) {
      result.putString("error", e.toString());
    } finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      finish(
          "PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
          result);
    }
  }
}
