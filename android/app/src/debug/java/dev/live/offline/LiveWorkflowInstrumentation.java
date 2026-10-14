package dev.live.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/**
 * Explicitly authorized test device only. Resets this app's preferences and session memory.
 * Software checks and optional real JNI model path are reported separately.
 */
public final class LiveWorkflowInstrumentation extends Instrumentation {
  private Bundle args;
  private MainActivity activity;
  private final JSONObject report = new JSONObject();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    this.args = args;
    start();
  }

  private Object field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(activity, args);
  }

  private View find(View view, String text) {
    if (text.equals(view.getTag())
        || text.contentEquals(
            view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private void checkIcons(View view) {
    if (view instanceof android.widget.ImageButton) {
      require(
          view.getContentDescription() != null && view.getContentDescription().length() > 0,
          "icon accessible name");
      require(view.getTooltipText() != null && view.getTooltipText().length() > 0, "icon tooltip");
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        checkIcons(((ViewGroup) view).getChildAt(i));
  }

  private void click(String text) {
    runOnMainSync(
        () -> {
          View v = find(activity.getWindow().getDecorView(), text);
          if (v == null) throw new AssertionError("missing control: " + text);
          v.performClick();
        });
    waitForIdleSync();
  }

  private void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private String uiString(String name) throws Exception {
    String[] value = {null};
    Exception[] failure = {null};
    runOnMainSync(
        () -> {
          try {
            value[0] = String.valueOf(field(name));
          } catch (Exception e) {
            failure[0] = e;
          }
        });
    if (failure[0] != null) throw failure[0];
    return value[0];
  }

  private long micAtFirstAudio = -1, micAfterAudio = -1;
  private boolean visibleStreamingObserved;

  private void captureAssistantView(String name) {
    if (!"true".equals(args.getString("captureAssistantViews"))) return;
    runOnMainSync(() -> {
      try {
        View view=(View)field("cameraPlaceholder");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(
            view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
        canvas.drawColor(LiveTheme.SURFACE);view.draw(canvas);
        try(FileOutputStream out=new FileOutputStream(new File(
            getTargetContext().getExternalFilesDir(null),name+".png"))){
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        } finally {bitmap.recycle();}
      } catch(Exception e){throw new RuntimeException(e);}
    });
  }

  private void captureChatView(String name) {
    if (!"true".equals(args.getString("captureChatViews"))) return;
    runOnMainSync(() -> {
      try {
        Dialog dialog = (Dialog) field("activeSheet");
        View view = dialog.getWindow().getDecorView();
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
            view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(
            new File(getTargetContext().getExternalFilesDir(null), name + ".png"))) {
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  private void awaitInference(long timeout) throws Exception {
    ThreadPoolExecutor executor = (ThreadPoolExecutor) field("inference");
    long deadline = SystemClock.elapsedRealtime() + timeout;
    do {
      Thread.sleep(200);
      boolean[] visible = {false};
      runOnMainSync(() -> {
        try {
          TextView text = (TextView) field("responseView");
          visible[0] = text != null && text.getText().toString().endsWith(" ▍");
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      if (visible[0] && !visibleStreamingObserved) {
        visibleStreamingObserved = true;
        captureChatView("05-streaming-chat-partial");
      }
      Capture c = (Capture) field("capture");
      if (c != null && (Long) field("firstAudioMs") >= 0) {
        if (micAtFirstAudio < 0) micAtFirstAudio = c.capturedSamples.get();
        micAfterAudio = c.capturedSamples.get();
      }
      if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
        waitForIdleSync();
        return;
      }
    } while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("model request timeout");
  }

  private void submit(byte[] pcm, String question) throws Exception {
    Exception[] error = {null};
    runOnMainSync(
        () -> {
          try {
            call("acquireAudioFocus", new Class<?>[] {});
            long id = (Long) call("invalidate", new Class<?>[] {});
            call(
                "submit",
                new Class<?>[] {byte[].class, String.class, long.class, long.class},
                pcm,
                question,
                id,
                SystemClock.elapsedRealtime());
          } catch (Exception e) {
            error[0] = e;
          }
        });
    if (error[0] != null) throw error[0];
    awaitInference(600000);
    require(!uiString("lastStatus").startsWith("응답 실패"), uiString("lastStatus"));
  }

  private void screenshot(String name) throws Exception {
    // A physical device may already have an automation client attached.
    if ("true".equals(args.getString("skipScreenshots"))) return;
    SystemClock.sleep(350);
    android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new IOException("screenshot unavailable");
    try (FileOutputStream out =
        new FileOutputStream(
            new File(getTargetContext().getExternalFilesDir(null), name + ".png"))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      getTargetContext().getSharedPreferences("preferences", 0).edit().clear().commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      click("시작하기");
      click("다음 · 사용 방식");
      click("준비 화면으로");
      require(uiString("page").equals("live"), "onboarding navigation");
      runOnMainSync(() -> checkIcons(activity.getWindow().getDecorView()));
      screenshot("01-live-ready");
      click("텍스트로 질문하기");
      require(field("question") != null, "keyboard icon opens composer");
      require(!(Boolean) field("speechEnabled") && !(Boolean) field("listening"), "keyboard opens silent chat");
      runOnMainSync(
          () -> {
            try {
              ((Dialog) field("activeSheet")).dismiss();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      click("음성으로 답변 듣기");
      long old = ((AtomicLong) field("epoch")).get();
      click("응답 즉시 중단");
      activity.onModelText(old, "old", "must not appear");
      activity.onModelTextDelta(old, "old", "late stream must not appear");
      waitForIdleSync();
      require(uiString("response").isEmpty(), "late model UI rejected");
      require(!activity.onPcm(old, new float[480]), "late PCM rejected");
      // Completed and cancelled turns are bounded and cleared with session data.
      for (int i = 0; i < 10; i++) {
        final int n = i;
        runOnMainSync(() -> {
          try {
            long id = (Long) call("invalidate", new Class<?>[] {});
            activity.onModelText(id, "질문 " + n, "답변 " + n);
          } catch (Exception e) { throw new RuntimeException(e); }
        });
      }
      require(((java.util.List<?>) field("chatTurns")).size() == 8, "chat history bounded to 8 turns");
      ObservationMemory memory = (ObservationMemory) field("memory");
      memory.observe(new byte[] {1}, SystemClock.elapsedRealtime());
      memory.question(SystemClock.elapsedRealtime());
      // Invalid JPEG stays outside the gallery; memory reset is tested through the actual control.
      click("기억");
      click("모든 관측과 대화 지우기");
      require(memory.snapshot(SystemClock.elapsedRealtime()).isEmpty(), "memory erase control");
      require(((java.util.List<?>) field("chatTurns")).isEmpty(), "erase clears chat history");
      click("최근 관측 잠시 기억하기");
      require(uiString("memoryCount").equals("0"), "memory opt-out control");
      click("최근 관측 잠시 기억하기");
      click("라이브");
      report.put("software_workflow", "PASS");
      String bundle = args.getString("bundle");
      if (bundle != null) {
        // Provision through the same bounded ZIP verifier as the document picker.
        long installed = SystemClock.elapsedRealtime();
        awaitInference(600000);
        File root = new File(getTargetContext().getFilesDir(), "models");
        boolean reuse = "true".equals(args.getString("reuse"));
        if (!reuse)
          root = ModelBundle.install(new FileInputStream(bundle), getTargetContext().getFilesDir());
        final File modelRoot = root;
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field("inference");
        if (!reuse)
          executor
              .submit(
                  () -> {
                    try {
                      call("load", new Class<?>[] {File.class}, modelRoot);
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .get(600, TimeUnit.SECONDS);
        require((Boolean) field("loaded"), "actual model load: " + uiString("lastStatus"));
        report.put("install_load_seconds", (SystemClock.elapsedRealtime() - installed) / 1000.0);
        report.put("gpu_active", field("gpuActive"));
        if ("true".equals(args.getString("requireGpu")))
          require((Boolean) field("gpuActive"), "validated OpenCL profile must be active");
        File fixture = new File(bundle).getParentFile();
        byte[] jpeg = Files.readAllBytes(new File(fixture, "scene.jpg").toPath());
        byte[] wav = Files.readAllBytes(new File(fixture, "question.wav").toPath());
        byte[] pcm = java.util.Arrays.copyOfRange(wav, 44, wav.length);
        boolean fixtureOnly = "true".equals(args.getString("fixtureOnly"));
        report.put("live_microphone_test", !fixtureOnly);
        if (!fixtureOnly) {
          click("카메라 사용");
          click("라이브 시작");
          require((Boolean) field("listening"), "microphone-only capture starts");
        }
        memory.configure(2, 300000);
        memory.observe(jpeg, SystemClock.elapsedRealtime());
        boolean inspectChat = "true".equals(args.getString("captureChatViews"));
        if (inspectChat) click("답변 보기");
        long started = SystemClock.elapsedRealtime();
        submit(pcm, "");
        String response = uiString("response");
        require(response.length() > 10, "ASR and VLM response; status=" + uiString("lastStatus"));
        require((Long) field("firstAudioMs") >= 0, "actual TTS PCM callback");
        report.put("real_asr_vlm_tts", response).put("first_audio_ms", field("firstAudioMs"));
        report.put("first_text_ms", field("firstTextMs")).put("text_updates", field("textUpdates"));
        require((Integer) field("textUpdates") > 1, "real incremental model text");
        require((Long) field("firstTextMs") < (Long) field("firstAudioMs"), "text appears before TTS");
        report.put("request_seconds", (SystemClock.elapsedRealtime() - started) / 1000.0);
        long playoutDeadline = SystemClock.elapsedRealtime() + 30000;
        long expectedSamples = 0;
        org.json.JSONArray chunks = new org.json.JSONArray();
        java.util.List<long[]> arrivals = (java.util.List<long[]>) field("audioChunks");
        synchronized (arrivals) {
          for (long[] chunk : arrivals) {
            chunks.put(new org.json.JSONArray().put(chunk[0]).put(chunk[1]));
            expectedSamples += chunk[1];
          }
        }
        android.media.AudioTrack output=(android.media.AudioTrack)field("track");
        boolean capturedAssistant=false;
        float observedLevel=0;
        while(((Long)field("audioWrittenSamples")<expectedSamples
            || Integer.toUnsignedLong(output.getPlaybackHeadPosition())<expectedSamples)
            && SystemClock.elapsedRealtime()<playoutDeadline){
          observedLevel=Math.max(observedLevel,(Float)call("currentVoiceLevel",new Class<?>[]{}));
          if(!capturedAssistant && observedLevel>.03f){
            captureAssistantView("assistant-playing");capturedAssistant=true;
          }
          Thread.sleep(20);
        }
        report.put("expected_pcm_samples",expectedSamples).put("written_at_drain",field("audioWrittenSamples"));
        report.put("status_at_drain",uiString("lastStatus")).put("foreground_at_drain",field("foreground"));
        report.put("pcm_chunks_arrival_ms_and_samples", chunks);
        require((Long) field("audioWrittenSamples") == expectedSamples && expectedSamples > 0,
            "every model PCM sample reaches AudioTrack");
        long played=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
        require(played>=expectedSamples,"all model PCM must actually play before pausing");
        report.put("audio_played_samples",played);
        report.put("assistant_observed_playback_level",observedLevel);
        require(observedLevel>0,"actual model playback must drive the assistant meter");
        report.put("assistant_level_after_playback",call("currentVoiceLevel",new Class<?>[]{}));
        captureAssistantView("assistant-idle");
        require((Float) field("maxVoiceLevel") > 0, "assistant animation receives real PCM level");
        report.put("pcm_chunks_arrival_ms_and_samples", chunks);
        report.put("first_playback_write_ms", field("firstPlaybackMs"));
        report.put("audio_written_samples", field("audioWrittenSamples"));
        report.put("audio_starvations", field("audioStarvations"));
        report.put("audio_producer_waits", field("audioProducerWaits"));
        report.put("audio_fully_buffered", field("audioFullyBuffered"));
        report.put("max_voice_level", field("maxVoiceLevel"));
        if (!fixtureOnly) {
          report.put("mic_samples_during_tts", micAfterAudio - micAtFirstAudio);
          require(
              micAfterAudio - micAtFirstAudio > 16000,
              "microphone continues while real TTS produces PCM");
        }
        if (!inspectChat)
          require(field("responseView") == null, "transcript stays collapsed until requested");
        screenshot("02-live-icons");
        if (!inspectChat) click("답변 보기");
        require(field("responseView") != null, "reply icon opens actual transcript");
        screenshot("02-live-answer");
        runOnMainSync(
            () -> {
              try {
                ((Dialog) field("activeSheet")).dismiss();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        if (!fixtureOnly) click("마이크 일시정지");
        click("응답 즉시 중단");
        require(
            ((java.util.concurrent.ArrayBlockingQueue<?>) field("playback")).isEmpty(),
            "stop clears playback");
        click("기억");
        screenshot("03-memory");
        click("라이브");
        screenshot("04-home");
        click("텍스트로 질문하기");
        visibleStreamingObserved = false;
        runOnMainSync(() -> {
          try {
            ((EditText) field("question")).setText("관측한 컵의 중심은 어느 구역인가요?");
            call("askText", new Class<?>[] {});
          } catch (Exception e) { throw new RuntimeException(e); }
        });
        awaitInference(600000);
        require((Long) field("firstAudioMs") == -1, "silent chat must not synthesize audio");
        require((Integer) field("textUpdates") > 1, "silent chat actually streams model text");
        require(field("responseView") != null, "chat answer stays visible");
        require(((java.util.List<?>) field("chatTurns")).size() >= 2, "chat retains previous turn");
        report.put("silent_chat", uiString("response"));
        report.put("silent_first_text_ms", field("firstTextMs"));
        report.put("silent_text_updates", field("textUpdates"));
        require(visibleStreamingObserved, "partial answer is visible before generation completes");
        report.put("visible_partial_before_complete", visibleStreamingObserved);
        captureChatView("06-chat-complete");
        screenshot("05-streaming-chat");
        memory.reset();
        ((AssistantMemory) field("assistantMemory")).clear();
        submit(null, "지금 컵은 어디 있나요?");
        require((Long) field("firstAudioMs") == -1, "text-only must not synthesize audio");
        report.put("no_evidence_text_only", uiString("response"));
        require(uiString("response").matches("(?s).*(확인|알 수 없|모르|제공되지|볼 수 없).*"), "missing evidence abstention");
        // Background ends the session even after text-only inference.
        runOnMainSync(() -> callActivityOnPause(activity));
        require(
            memory.snapshot(SystemClock.elapsedRealtime()).isEmpty()
                && uiString("response").isEmpty()
                && ((java.util.List<?>) field("chatTurns")).isEmpty(),
            "background privacy");
        report.put("model_path", "PASS: real Android JNI ASR → VLM → TTS; no mock");
      }
      report.put(
          "scope",
          args.getString(
              "scope", "Isolated emulator; no physical Galaxy S, acoustic or thermal acceptance"));
      report.put("result", "PASS");
      result.putString("result", "PASS");
    } catch (Throwable error) {
      try {
        report.put("result", "FAIL").put("error", error.toString());
      } catch (Exception ignored) {
      }
      result.putString("error", error.toString());
    } finally {
      try {
        File out = new File(getTargetContext().getExternalFilesDir(null), "workflow-report.json");
        Files.write(
            out.toPath(), report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.putString("report", report.toString());
      } catch (Exception e) {
        result.putString("report_error", e.toString());
      }
      if (activity != null) runOnMainSync(() -> activity.finish());
      finish(
          "PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
          result);
    }
  }
}
