package dev.live.offline;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.*;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.*;
import android.view.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Capture threads never wait for model inference or playback. */
final class Capture implements AutoCloseable {
  interface Listener {
    void frame(byte[] jpeg, long at);

    void speechStart();

    void utterance(byte[] pcm, long at);
    default void partial(byte[] pcm,long at) {}

    void error(String message);
    default void samples(short[] pcm, int count) {} // Synchronous, bounded debug diagnostics only.
    default void level(float rms) {}
    default boolean acceptsInput(int type) { return true; }
    default void inputReady(boolean ready) {}
  }

  private final AtomicBoolean running = new AtomicBoolean(false);
  final AtomicLong capturedSamples = new AtomicLong();
  final MockAudioInput mockInput = new MockAudioInput();
  private final Listener listener;
  private final HandlerThread cameraThread = new HandlerThread("camera-capture");
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader reader;
  private Surface display;
  private AudioRecord recorder;
  private Thread micThread;
  private AcousticEchoCanceler aec;
  private Context captureContext;
  private long issued;
  private boolean pending;
  private int jpegOrientation;

  Capture(Listener listener) {
    this.listener = listener;
  }

  void start(Context context, TextureView preview) throws Exception {
    start(context, preview, true);
  }

  @SuppressLint("MissingPermission")
  void start(Context context, TextureView preview, boolean withCamera) throws Exception {
    if (!running.compareAndSet(false, true)) return;
    captureContext=context.getApplicationContext();
    if (withCamera) {
      cameraThread.start();
      Handler handler = new Handler(cameraThread.getLooper());
      CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      String id = null;
      for (String candidate : manager.getCameraIdList()) {
        Integer facing =
            manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          id = candidate;
          break;
        }
      }
      if (id == null) throw new IllegalStateException("후면 카메라를 찾을 수 없습니다.");
      Integer sensorOrientation =
          manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
      jpegOrientation =
          sensorOrientation == null
              ? 0
              : sensorOrientation; // Activity is portrait, rear camera only.
      reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
      reader.setOnImageAvailableListener(
          source -> {
            try (Image image = source.acquireLatestImage()) {
              if (image == null) return;
              ByteBuffer buffer = image.getPlanes()[0].getBuffer();
              if (buffer.remaining() > 2 * 1024 * 1024) return;
              byte[] jpeg = new byte[buffer.remaining()];
              buffer.get(jpeg);
              ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
              int orientation =
                  exif.getAttributeInt(
                      ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
              int angle =
                  orientation == ExifInterface.ORIENTATION_ROTATE_90
                      ? 90
                      : orientation == ExifInterface.ORIENTATION_ROTATE_180
                          ? 180
                          : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
              if (angle != 0) {
                Bitmap original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                Matrix matrix = new Matrix();
                matrix.postRotate(angle);
                Bitmap upright =
                    Bitmap.createBitmap(
                        original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                upright.compress(Bitmap.CompressFormat.JPEG, 90, out);
                jpeg = out.toByteArray();
                upright.recycle();
                if (original != upright) original.recycle();
              }
              if (running.get()) listener.frame(jpeg, issued);
            } catch (Exception e) {
              if (running.get()) listener.error("카메라 프레임 읽기 실패");
            } finally {
              pending = false;
            }
          },
          handler);
      preview.getSurfaceTexture().setDefaultBufferSize(640, 480);
      display = new Surface(preview.getSurfaceTexture());
      manager.openCamera(
          id,
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice value) {
              if (!running.get()) {
                value.close();
                return;
              }
              camera = value;
              try {
                camera.createCaptureSession(
                    Arrays.asList(display, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                      public void onConfigured(CameraCaptureSession value) {
                        if (!running.get()) {
                          value.close();
                          return;
                        }
                        session = value;
                        try {
                          CaptureRequest.Builder request =
                              camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                          request.addTarget(display);
                          session.setRepeatingRequest(request.build(), null, handler);
                        } catch (Exception e) {
                          listener.error(e.getMessage());
                        }
                        handler.post(
                            new Runnable() {
                              public void run() {
                                if (!running.get()) return;
                                try {
                                  if (!pending) {
                                    CaptureRequest.Builder shot =
                                        camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    shot.addTarget(reader.getSurface());
                                    shot.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
                                    issued = SystemClock.elapsedRealtime();
                                    pending = true;
                                    session.capture(
                                        shot.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                          public void onCaptureFailed(
                                              CameraCaptureSession s,
                                              CaptureRequest r,
                                              CaptureFailure f) {
                                            pending = false;
                                          }
                                        },
                                        handler);
                                  }
                                } catch (Exception e) {
                                  pending = false;
                                  listener.error(e.getMessage());
                                }
                                handler.postDelayed(this, 1000);
                              }
                            });
                      }

                      public void onConfigureFailed(CameraCaptureSession value) {
                        listener.error("카메라 구성 실패");
                      }
                    },
                    handler);
              } catch (Exception e) {
                listener.error(e.getMessage());
              }
            }

            public void onDisconnected(CameraDevice value) {
              value.close();
              listener.error("카메라 연결이 끊겼습니다.");
            }

            public void onError(CameraDevice value, int code) {
              value.close();
              listener.error("카메라 오류 " + code);
            }
          },
          handler);
    }
    int minimum =
        AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    recorder =
        new AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(6400, minimum * 2));
    if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
      throw new IllegalStateException("마이크 초기화 실패");
    if (AcousticEchoCanceler.isAvailable()) {
      aec = AcousticEchoCanceler.create(recorder.getAudioSessionId());
      if (aec != null) aec.setEnabled(true);
    }
    recorder.startRecording();
    micThread = new Thread(this::microphone, "microphone-capture");
    micThread.start();
  }

  volatile boolean mockAudible=true;
  volatile long mockMonitorSamples,mockMonitorDroppedSamples,mockMonitorPlayedSamples;
  volatile int mockMonitorRouteType;
  private void microphone() {
    short[] block = new short[320];
    byte[] pre = new byte[12800];
    int preSize = 0, lastPartial = 0;
    ByteArrayOutputStream utterance = null;
    byte[] endpointSnapshot = null;
    SpeechSegmenter segmenter = new SpeechSegmenter();
    int filled = 0;
    int routeId = -1, routeQuietFrames = 0;
    boolean routePrimed = false;
    long routeSince = 0, lastVoicedAt = 0;
    boolean inputReady = false;
    listener.inputReady(false);
    SpeechGate gate = new SpeechGate();
    try (NeuralVad detector = new NeuralVad(captureContext); MockInputMonitor monitor=new MockInputMonitor()) {
    while (running.get()) {
      int count = recorder.read(block, filled, block.length - filled, AudioRecord.READ_BLOCKING);
      if (count <= 0) {
        if (running.get()) listener.error("마이크 읽기 오류");
        break;
      }
      capturedSamples.addAndGet(count);
      filled += count;
      if (filled < block.length) continue;
      count = block.length; filled = 0;
      // AudioRecord is still drained at real time. In mock mode its samples are
      // replaced, preventing room sound/output echo from contaminating the fixture.
      boolean mock = mockInput.read(block);
      monitor.write(block,mock && mockAudible);
      mockMonitorSamples=monitor.written;mockMonitorDroppedSamples=monitor.dropped;mockMonitorPlayedSamples=monitor.played;mockMonitorRouteType=monitor.routeType;
      AudioDeviceInfo route = recorder.getRoutedDevice();
      int nextRoute = mock ? -2 : route == null ? -1 : route.getId();
      long now = SystemClock.elapsedRealtime();
      if (nextRoute != routeId) {
        android.util.Log.i("LiveVoice", "input_route mock=" + mock + " type=" + (route == null ? 0 : route.getType()));
        routeId = nextRoute; routeSince = now; routeQuietFrames = 0; routePrimed = false;
        detector.reset();
        // Do not splice two different microphones into one ASR request.
        utterance = null; endpointSnapshot = null; preSize = 0; segmenter = new SpeechSegmenter(); gate = new SpeechGate();
      }
      // Warm the classifier while SCO/AGC settles, but do not emit startup tones.
      boolean speech = detector.voiced(block,segmenter.speaking());
      routeQuietFrames = speech ? 0 : routeQuietFrames + 1;
      if (now-routeSince >= 3000 && routeQuietFrames >= 10) routePrimed = true;
      boolean ready = mock || (route != null && listener.acceptsInput(route.getType()) && routePrimed);
      if (ready != inputReady) { inputReady = ready; listener.inputReady(ready); }
      if (!ready) continue;
      listener.samples(block, count);
      double energy = 0;
      byte[] bytes = new byte[count * 2];
      for (int i = 0; i < count; i++) {
        energy += (double) block[i] * block[i];
        bytes[i * 2] = (byte) block[i];
        bytes[i * 2 + 1] = (byte) (block[i] >> 8);
      }
      float rms = (float)(Math.sqrt(energy / count) / 32768);
      listener.level(rms);
      boolean voiced = gate.voiced(rms, segmenter.speaking()) && speech;
      if (voiced) { endpointSnapshot = null; lastVoicedAt = now; }
      int event = segmenter.accept(voiced);
      if (event == SpeechSegmenter.START) {
        android.util.Log.i("LiveVoice", "speech_start rms=" + rms);
        listener.speechStart();
        utterance = new ByteArrayOutputStream(480000);
        utterance.write(pre, 0, preSize);
        lastPartial = 0;

      }
      if (utterance != null) {
        utterance.write(bytes, 0, bytes.length);
        if (event == SpeechSegmenter.END) {
          android.util.Log.i("LiveVoice", "speech_end ms=" + utterance.size()/32);
          listener.utterance(endpointSnapshot != null ? endpointSnapshot : utterance.toByteArray(), lastVoicedAt);
          utterance = null;
          endpointSnapshot = null;

          preSize = 0;
        } else if (segmenter.quietFrames() == 20) {
          // Decode the completed speech with 400ms trailing silence while the
          // remaining 400ms endpoint guard runs. Resumed speech invalidates it.
          endpointSnapshot = utterance.toByteArray(); lastPartial = utterance.size();
          listener.partial(endpointSnapshot, SystemClock.elapsedRealtime());
        } else if (endpointSnapshot == null && utterance.size() >= 32000 && utterance.size()-lastPartial >= 19200) {
          lastPartial = utterance.size();
          listener.partial(utterance.toByteArray(), SystemClock.elapsedRealtime());
        }
      } else {
        int keep = Math.min(preSize, pre.length - bytes.length);
        System.arraycopy(pre, preSize - keep, pre, 0, keep);
        System.arraycopy(bytes, 0, pre, keep, bytes.length);
        preSize = keep + bytes.length;
      }
    }
    } catch (Exception e) {
      if (running.get()) listener.error("음성 감지 실패: " + e.getMessage());
    }
  }

  public void close() {
    running.set(false);
    mockInput.disable();
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (Exception ignored) {
      }
      if (micThread != null)
        try {
          micThread.join(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      recorder.release();
      recorder = null;
    }
    if (aec != null) aec.release();
    if (session != null) session.close();
    if (camera != null) camera.close();
    cameraThread.quitSafely();
    if (reader != null) reader.close();
    if (display != null) display.release();
  }
}
