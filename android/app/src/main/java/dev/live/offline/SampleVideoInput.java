package dev.live.offline;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Silent local video replay. Preview and microphone run independently of inference.
 * Frame time is a conservative preview-copy request, NOT a decoded sensor/PTS timestamp.
 * sourceMs is the player's approximate position; presentation uncertainty is unknown. */
final class SampleVideoInput implements AutoCloseable {
  interface Output {
    void frame(byte[] jpeg, long at, long sourceMs);
    void ended();
    void error(String message);
  }
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService encoder = Executors.newSingleThreadExecutor();
  private final AtomicBoolean encoding = new AtomicBoolean();
  private final MediaPlayer player = new MediaPlayer();
  private final Surface surface;
  private final TextureView preview;
  private final Output output;
  private volatile boolean closed;
  volatile long frames, positionMs;
  SampleVideoInput(Context context, Uri uri, TextureView preview, Output output) throws Exception {
    this.preview = preview; this.output = output;
    surface = new Surface(preview.getSurfaceTexture());
    try {
      player.setSurface(surface); player.setVolume(0, 0);
      try (AssetFileDescriptor file = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
        if (file == null || file.getLength() < 1 || file.getLength() > 256L * 1024 * 1024)
          throw new IllegalArgumentException("크기를 확인할 수 있는 256MB 이하의 로컬 영상을 선택하세요.");
        player.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
      }
      player.setOnPreparedListener(p -> {
        if (closed) return;
        if (p.getDuration() <= 0 || p.getDuration() > 600000
            || p.getVideoWidth() > 1920 || p.getVideoHeight() > 1920) {
          close(); output.error("10분 이내, 최대 1920픽셀의 샘플 영상을 선택하세요."); return;
        }
        p.start(); ui.post(tick);
      });
      player.setOnCompletionListener(p -> { if (!closed) { close(); output.ended(); } });
      player.setOnErrorListener((p, what, extra) -> {
        if (!closed) { close(); output.error("샘플 영상을 재생할 수 없습니다: " + what); }
        return true;
      });
      player.prepareAsync();
    } catch (Exception e) { close(); throw e; }
  }
  private final Runnable tick = new Runnable() {
    public void run() {
      if (closed) return;
      if (preview.isAvailable() && player.isPlaying() && encoding.compareAndSet(false, true)) {
        long at = SystemClock.elapsedRealtime();
        long position = player.getCurrentPosition();
        int width = player.getVideoWidth(), height = player.getVideoHeight();
        float scale = Math.min(1f, 640f / Math.max(1, Math.max(width, height)));
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = preview.getBitmap(w, h);
        if (bitmap == null) encoding.set(false);
        else encoder.execute(() -> {
          try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes);
            if (!closed && SystemClock.elapsedRealtime() - at <= 1000) {
              output.frame(bytes.toByteArray(), at, position);
              frames++; positionMs = position;
            }
          } finally { bitmap.recycle(); encoding.set(false); }
        });
      }
      if (!closed) ui.postDelayed(this, 500);
    }
  };
  public void close() {
    if (closed) return;
    closed = true; ui.removeCallbacks(tick);
    player.release(); surface.release(); encoder.shutdown();
  }
}
