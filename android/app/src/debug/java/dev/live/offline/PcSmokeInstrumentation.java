package dev.live.offline;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;

/** Emulator capture plumbing only. Never substitutes for real model acceptance. */
public final class PcSmokeInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private TextureView preview(View view) {
    if (view instanceof TextureView) return (TextureView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextureView found = preview(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private byte[] archive(String failure) throws Exception {
    JSONArray files = new JSONArray();
    String[] names = {
      "asr/config.json",
      "asr/llm_config.json",
      "vlm/config.json",
      "vlm/llm_config.json",
      "tts/config.json",
      "tts/llm_config.json"
    };
    byte[] data = "{}".getBytes("UTF-8");
    StringBuilder sha = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
      sha.append(String.format("%02x", b & 255));
    for (String name : names)
      files.put(
          new JSONObject()
              .put("path", failure.equals("path") ? "asr/../../escape" : name)
              .put("bytes", data.length)
              .put("sha256", failure.equals("hash") ? "0".repeat(64) : sha.toString()));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(new JSONObject().put("files", files).toString().getBytes("UTF-8"));
      zip.closeEntry();
      for (int i = 0; i < names.length; ++i) {
        if (failure.equals("missing") && i == names.length - 1) continue;
        zip.putNextEntry(new ZipEntry(names[i]));
        zip.write(data);
        zip.closeEntry();
      }
      if (failure.equals("extra")) {
        zip.putNextEntry(new ZipEntry("asr/extra.bin"));
        zip.write(data);
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Capture[] capture = {null};
    Activity[] activity = {null};
    AtomicInteger frames = new AtomicInteger();
    AtomicReference<String> error = new AtomicReference<>();
    try {
      File test = new File(getTargetContext().getCacheDir(), "import-fixture");
      test.mkdirs();
      ModelBundle.verify(ModelBundle.install(new ByteArrayInputStream(archive("valid")), test));
      for (String invalid : new String[] {"path", "hash", "missing", "extra"}) {
        boolean rejected = false;
        try {
          ModelBundle.install(new ByteArrayInputStream(archive(invalid)), test);
        } catch (Exception expected) {
          rejected = true;
        }
        if (!rejected) throw new IllegalStateException("invalid bundle accepted: " + invalid);
      }
      MainActivity.remove(test);
      result.putInt("bundle_rejection_cases", 4);
      activity[0] =
          startActivitySync(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      runOnMainSync(
          () -> {
            try {
              java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page");
              page.setAccessible(true);
              page.set(activity[0], "live");
              java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
              render.setAccessible(true);
              render.invoke(activity[0]);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      waitForIdleSync();
      long deadline = SystemClock.elapsedRealtime() + 5000;
      TextureView[] view = {null};
      do {
        runOnMainSync(() -> view[0] = preview(activity[0].getWindow().getDecorView()));
        if (view[0] != null && view[0].isAvailable()) break;
        Thread.sleep(50);
      } while (SystemClock.elapsedRealtime() < deadline);
      if (view[0] == null || !view[0].isAvailable())
        throw new IllegalStateException("preview unavailable");
      capture[0] =
          new Capture(
              new Capture.Listener() {
                public void frame(byte[] jpeg, long at) {
                  if (jpeg.length > 0 && SystemClock.elapsedRealtime() - at <= 3000)
                    frames.incrementAndGet();
                }

                public void speechStart() {}

                public void utterance(byte[] pcm, long at) {}

                public void error(String message) {
                  error.set(message);
                }
              });
      runOnMainSync(
          () -> {
            try {
              capture[0].start(activity[0], view[0]);
            } catch (Exception e) {
              error.set(e.toString());
            }
          });
      Thread.sleep(1200);
      long before = capture[0].capturedSamples.get();
      AudioTrack playback =
          new AudioTrack(
              AudioManager.STREAM_MUSIC,
              24000,
              AudioFormat.CHANNEL_OUT_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              4800,
              AudioTrack.MODE_STREAM);
      try {
        playback.play();
        short[] silence = new short[480];
        for (int i = 0; i < 150; i++)
          playback.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
      } finally {
        playback.stop();
        playback.release();
      }
      long during = capture[0].capturedSamples.get() - before;
      runOnMainSync(() -> capture[0].close());
      capture[0] = null;
      if (error.get() != null || frames.get() < 2 || during < 32000)
        throw new IllegalStateException(
            "capture failed: " + error.get() + ", frames=" + frames.get() + ", samples=" + during);
      result.putInt("camera_frames", frames.get());
      result.putLong("mic_samples_during_silent_playback", during);
      result.putString(
          "scope", "emulator capture plumbing, no model inference or acoustic acceptance");
      result.putString("result", "PASS");
      finish(Activity.RESULT_OK, result);
    } catch (Exception e) {
      result.putString("error", e.toString());
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (capture[0] != null) runOnMainSync(() -> capture[0].close());
      if (activity[0] != null) runOnMainSync(() -> activity[0].finish());
    }
  }
}
