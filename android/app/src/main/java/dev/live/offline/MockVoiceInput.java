package dev.live.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;

/** Offline synthesis becomes input PCM, never speaker playback or a text ASR shortcut. */
final class MockVoiceInput implements AutoCloseable {
  interface Output { void ready(long id, short[] pcm); void error(long id, String message); }
  private final LocalSpeech voice;
  private final Output output;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private float[] buffer;
  private int size;
  private long request = -1;
  private Runnable timeout;

  MockVoiceInput(Context context, File directory, Output output) {
    this.output = output;
    voice = new LocalSpeech(context, directory, new LocalSpeech.Output() {
      public boolean pcm(long id, float[] samples) {
        synchronized (MockVoiceInput.this) {
          if (id != request || buffer == null) return false;
          if (size + samples.length > buffer.length) {
            fail(id, "12초를 넘는 문장입니다. 더 짧게 입력하세요.");
            return false;
          }
          System.arraycopy(samples, 0, buffer, size, samples.length);
          size += samples.length;
          return true;
        }
      }
      public void done(long id, String error) {
        synchronized (MockVoiceInput.this) {
          if (id != request || buffer == null) return;
          if (!error.isEmpty()) { fail(id, error); return; }
          try {
            short[] pcm = MockAudioInput.from24k(buffer, size);
            clear();
            handler.post(() -> output.ready(id, pcm));
          } catch (IllegalArgumentException e) { fail(id, e.getMessage()); }
        }
      }
    },"ko-kr-x-koc-local","ko-kr-x-kod-local");
  }
  String voice(){return voice.voice();}
  boolean ready() { return voice.ready(); }
  synchronized boolean busy() { return buffer != null; }
  synchronized void prepare(long id, String text) {
    cancel();
    if (!ready()) { output.error(id, "오프라인 한국어 음성을 준비 중이거나 설치되어 있지 않아요. 잠시 후 다시 보내세요."); return; }
    if (text.isBlank() || text.length() > 160) { output.error(id, "160자 이내의 짧은 문장을 입력하세요."); return; }
    request = id; size = 0; buffer = new float[24000 * MockAudioInput.MAX_SECONDS];
    timeout = () -> { synchronized (MockVoiceInput.this) {
      if (request == id) { voice.cancel(); fail(id, "모의 음성 준비 시간이 초과됐어요. 다시 보내세요."); }
    }};
    handler.postDelayed(timeout, 30000);
    voice.speak(id, text);
  }
  private void clear() {
    request = -1; buffer = null; size = 0;
    if (timeout != null) handler.removeCallbacks(timeout);
    timeout = null;
  }
  private void fail(long id, String message) {
    clear(); handler.post(() -> output.error(id, message));
  }
  synchronized void cancel() { clear(); voice.cancel(); }
  public synchronized void close() { clear(); voice.close(); }
}
