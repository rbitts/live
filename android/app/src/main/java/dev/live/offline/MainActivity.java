package dev.live.offline;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;
import dev.live.memory.WorkingMemory;

public final class MainActivity extends Activity {
  static {
    System.loadLibrary("live_jni");
  }

  private native long nativeCreate();

  private native boolean nativeLoad(long handle, String root, boolean validatedDevice);

  private volatile boolean gpuActive;

  private native void nativeCancel(long handle, long epoch);

  private native String nativeInfer(
      long handle, String wav, String observation, String question, String memory, long epoch, boolean speak);

  private native String nativeObserve(long handle, String image, String memory, String task, long epoch);
  private native void nativeSpeak(long handle, String text, long epoch);
  private native void nativeCancelAsr(long handle,long turn);
  private native String nativeStreamingAsr(long handle,String wav,long turn,String context);
  private native String nativeTranscribe(long handle, String wav, long epoch);
  private native String nativeCooking(long handle, String input, String state, String image, long epoch, boolean plan);
  private native String nativeTaskObserve(long handle, String input, String state, String image, long epoch);
  private native String nativeVisualCheck(long handle, String question, String image, long epoch);
  private boolean fastObserver;

  private native void nativeRelease(long handle);

  private final AtomicLong epoch = new AtomicLong();
  private final AtomicLong captureEpoch = new AtomicLong();
  private final ObservationMemory memory = new ObservationMemory();
  private final AssistantMemory assistantMemory = new AssistantMemory();
  private final LiveWorkingMemory working = new LiveWorkingMemory();
  private volatile WorkingMemory.Followup activeFollowup;
  private volatile long followupEpoch = -1;
  private volatile boolean followupAudioFailed;
  private volatile long responseMemoryEpoch = -1;
  private volatile WorkingMemory.Lease responseMemoryLease;
  private String pendingHeardQuestion = "", pendingHeardAnswer = "";
  private long pendingHeardEpoch = -1;
  private volatile long lastDeliveredEpoch = -1;
  private final CookingSession cooking = new CookingSession();
  private final SceneGate cookingScenes = new SceneGate(1000);
  private Uri sampleVideoUri;
  private SampleVideoInput sampleVideo;
  private volatile long alignedTurn = -1, alignedAnchor = -1;
  private volatile List<ObservationMemory.Evidence> alignedEvidence = Collections.emptyList();
  private volatile long lastEvidenceAt = -1, lastEvidenceSourceMs = -1;
  private volatile String lastEvidenceSource = "unavailable";
  private volatile String lastResponsePath = "none";
  private volatile long visualMemoryEpoch = -1;
  private ObservationMemory.Frame visualMemoryFrame;
  private LocalSpeech localSpeech;
  private MockVoiceInput mockVoice;
  private long mockVoiceId, mockVoiceCapture, mockVoiceEpoch;
  private LiveTheme.IconButton mockButton;
  private String mockText = "안녕. 오늘 할 일을 짧게 정리해 줘.";
  private StreamingAsr streamingAsr;
  private SentenceVoice sentenceVoice;
  private final SpeculativeReply draft = new SpeculativeReply();
  private volatile long streamSpeechEpoch=-1, finalizedAsrTurn=-1;
  // Armed only by the debug instrumentation; one bounded utterance, cleared after diagnosis.
  private volatile boolean diagnosticCapture;
  private volatile byte[] diagnosticPcm;
  private volatile byte[] diagnosticRawPcm;
  private volatile int asrPartialUpdates, speculativeStarts, speculativeReuses, sentenceStarts;
  private volatile long lastAsrMs, lastBargeInStopMs;
  private volatile long localSpeechEpoch = -1;
  private long cookingObservationMs = -1;
  private int cookingChecks;
  private boolean cookingTimerPending;
  private long cookingGuidanceEpoch=-1;
  private int cookingGuidanceRevision=-1;
  private String lastCookingObservation="", lastCookingError="";
  private volatile ObservationMemory.Frame lastTaskFrame;
  private volatile int taskAttempts;
  private volatile String lastTaskDiagnostic="";
  private LinearLayout cookingPanel;
  private TextView cookingStep;
  private LiveTheme.IconButton cookingButton, cookingPause, cookingClock;
  private volatile boolean userSpeaking, proactiveBusy;
  private volatile int proactiveChecks, proactiveNotices;
  private volatile String lastObservation = "";
  private volatile String lastProactiveError = "";
  private LiveTheme.IconButton watchButton;
  private final ThreadPoolExecutor inference =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ArrayBlockingQueue<Audio> playback = new ArrayBlockingQueue<>(4);
  private volatile boolean alive = true, loaded = false, listening = false;
  private volatile AudioTrack track;
  private final Object audioGuard = new Object();
  private static boolean cacheCleaned;
  private File sessionCache;
  private long handle;
  private Capture capture;
  private TextureView preview;
  private boolean needsAttention, replyUnread;
  private volatile long thinkingEpoch = -1, speakingEpoch = -1;
  private volatile long audioDoneEpoch = -1, firstPlaybackMs = -1;
  private volatile long audioFailedEpoch = -1;
  private volatile int audioProducerWaits, audioStarvations;
  private volatile long audioWrittenSamples;
  private volatile long audioQueuedSamples;
  private long playbackFramesWritten; // guarded by audioGuard; reset with AudioTrack.flush()
  private double meterEnergy;
  private int meterSamples;
  private volatile float maxVoiceLevel;
  private volatile boolean audioFullyBuffered;
  private final ArrayList<long[]> audioChunks = new ArrayList<>();
  private final ArrayBlockingQueue<VoiceLevel> voiceLevels = new ArrayBlockingQueue<>(128);
  private View cameraPreviewCard;
  private Dialog activeSheet;
  private EditText question;
  private AudioFocusRequest audioFocus;
  private LiveTheme theme;
  private LinearLayout root, content;
  private TextView responseView;
  private LinearLayout chatMessages;
  private ScrollView chatScroll;
  private LiveTheme.IconButton chatVoiceButton;
  private final ArrayList<ChatTurn> chatTurns = new ArrayList<>();
  private final AtomicReference<TextUpdate> pendingText = new AtomicReference<>();
  private final AtomicBoolean textDrainPosted = new AtomicBoolean();
  private volatile int textUpdates;
  private volatile long firstTextMs = -1;
  private View cameraPlaceholder;
  private LiveTheme.IconButton statusButton, cameraButton, voiceButton, replyButton, memoryBadge;
  private final Map<Long, TextView> memoryAgeViews = new LinkedHashMap<>();
  private LiveTheme.IconButton startButton;
  private LinearLayout microphoneCaption;
  private TextView microphoneText;
  private ProgressBar microphoneMeter;
  private volatile float microphoneLevel;
  private volatile long microphoneTurnEpoch = -1;
  private String microphoneTranscript = "", microphoneState = "";
  private SharedPreferences preferences;
  private String page = "home", lastStatus = "모델을 준비해 주세요.", response = "";
  private boolean cameraEnabled = false, speechEnabled = true, foreground;
  private volatile boolean modelBusy;
  private int memoryCount = 2;
  private long memoryDuration = 300000;
  private long sessionStarted;
  private volatile long requestStarted;
  private volatile long firstAudioMs = -1;
  private int completedTurns;
  private int displayedMemories;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (!alive) return;
          if (page.equals("memory")
              && displayedMemories != memory.snapshot(SystemClock.elapsedRealtime()).size())
            render();
          refreshIndicators();
          workingTick();
          proactiveTick();
          cookingTick();
          uiHandler.postDelayed(this, 250);
        }
      };

  private static final class Audio {
    final long epoch;
    final float[] pcm;
    final long arrivedAt = SystemClock.elapsedRealtime();

    Audio(long e, float[] p) {
      epoch = e;
      pcm = p;
    }
  }

  private static final class VoiceLevel {
    final long start, end;
    final float value;
    VoiceLevel(long start, long end, float value) {
      this.start = start; this.end = end; this.value = value;
    }
  }

  private static final class ChatTurn {
    final long id;
    String question = "", answer = "";
    boolean complete, stopped;
    TextView questionView, answerView;

    ChatTurn(long id) { this.id = id; }
  }

  private static final class TextUpdate {
    final long id;
    final String question, answer;
    TextUpdate(long id, String question, String answer) {
      this.id = id;
      this.question = question;
      this.answer = answer;
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    synchronized (MainActivity.class) {
      if (!cacheCleaned) {
        File[] stale = getCacheDir().listFiles();
        if (stale != null)
          for (File file : stale)
            if (file.getName().startsWith("request-") || file.getName().startsWith("session-"))
              remove(file);
        cacheCleaned = true;
      }
    }
    sessionCache = new File(getCacheDir(), "session-" + UUID.randomUUID());
    localSpeech = new LocalSpeech(this, new File(sessionCache, "speech"), new LocalSpeech.Output() {
      public boolean pcm(long id, float[] samples) { return onPcm(id, samples); }
      public void done(long id, String error) {
        if (epoch.get() != id) return;
        uiHandler.post(() -> {
          if (epoch.get()!=id) return;
          if (sentenceVoice!=null && sentenceVoice.owns(id)) sentenceVoice.sentenceDone(id,error);
          else { audioDoneEpoch = id; thinkingEpoch = -1; if (!error.isEmpty()) {audioFailedEpoch=id;followupAudioFailed=true;showFor(id,error);} }
        });
      }
    });
    handle = nativeCreate();
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      mockVoice = new MockVoiceInput(this, new File(sessionCache, "mock-speech"), new MockVoiceInput.Output() {
        public void ready(long id, short[] pcm) {
          if (!alive || !foreground || !listening || capture == null || id != mockVoiceId
              || captureEpoch.get() != mockVoiceCapture || epoch.get() != mockVoiceEpoch) return;
          try {
            capture.mockAudible=preferences.getBoolean("mock_audible",true);
            capture.mockInput.offer(pcm);
            showMicrophoneTranscript(epoch.get(),"모의 사용자: "+mockText);
            microphoneState = "모의 음성 입력 중";
            show("모의 사용자 음성을 입력합니다. 이어서 AI가 답해요.");
          } catch (IllegalStateException | IllegalArgumentException e) { showError(e.getMessage()); }
        }
        public void error(long id, String message) {
          if (alive && foreground && id == mockVoiceId) { microphoneState = ""; showError(message); }
        }
      });
    }
    sentenceVoice = new SentenceVoice(new SentenceVoice.Output() {
      public void speak(long id,String sentence) {
        if(epoch.get()!=id)return;
        sentenceStarts++;
        android.util.Log.i("LiveStream","sentence epoch="+id+" chars="+sentence.length());
        localSpeech.speak(id,sentence);
      }
      public void done(long id,String error) {
        if(epoch.get()!=id)return;
        audioDoneEpoch=id;
        if(!error.isEmpty()){audioFailedEpoch=id;localSpeech.cancel();showFor(id,error);}
      }
    });
    streamingAsr = new StreamingAsr(new StreamingAsr.Decoder() {
      public void cancel(long turn){nativeCancelAsr(handle,turn);}
      public String decode(byte[] pcm,long turn)throws Exception {return decode(pcm,turn,"");}
      public String decode(byte[] pcm,long turn,String context)throws Exception {
        File wav=File.createTempFile("stream-asr-",".wav",sessionCache);
        try{writeWav(wav,pcm);return nativeStreamingAsr(handle,wav.getAbsolutePath(),turn,context);}
        finally{wav.delete();}
      }
    },new StreamingAsr.Output(){
      public void text(long turn,String text,boolean last,long at){runOnUiThread(()->acceptRecognition(turn,text,last,at));}
      public void error(long turn,String message){runOnUiThread(()->{
        if(alive && listening && streamingAsr.current()==turn){invalidate();working.state.userEnded(SystemClock.elapsedRealtime());microphoneState="인식 실패";showError(message);}
      });}
    });
    theme = new LiveTheme(this);
    preferences = getSharedPreferences("preferences", MODE_PRIVATE);
    memoryCount = preferences.getInt("memory_count", 2);
    memoryDuration = preferences.getLong("memory_duration", 300000);
    memory.configure(memoryCount, memoryDuration);
    assistantMemory.configure(memoryCount > 0, memoryDuration);
    assistantMemory.visualCapacity(memoryCount);
    getWindow().setStatusBarColor(LiveTheme.BG);
    getWindow().setNavigationBarColor(LiveTheme.BG);
    getWindow().getDecorView().setSystemUiVisibility(0);
    page = preferences.getBoolean("onboarded", false) ? "live" : "welcome";
    render();
    new Thread(this::play, "audio-playback").start();
    uiHandler.post(ticker);
    File models = new File(getFilesDir(), "models");
    if (new File(models, "manifest.json").isFile()) {
      modelBusy = true;
      inference.execute(() -> load(models));
    }
  }

  private LinearLayout row() {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  private void detail(String title, String body) {
    if (activeSheet != null) activeSheet.dismiss();
    activeSheet =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("닫기", null)
            .create();
    activeSheet.show();
  }

  private void render() {
    preview = null;
    cameraPreviewCard = null;
    cameraPlaceholder = null;
    memoryAgeViews.clear();
    question = null;
    responseView = null;
    startButton = null;
    cameraButton = null;
    voiceButton = null;
    replyButton = null;
    memoryBadge = null;
    watchButton = null;
    mockButton = null;
    cookingButton = cookingPause = cookingClock = null;
    cookingPanel = null; cookingStep = null;
    if (page.equals("home")) page = "live";
    root = theme.column();
    root.setBackgroundColor(LiveTheme.BG);
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          int left, top, right, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = Math.max(bars.bottom, keyboard.bottom);
          } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
          }
          view.setPadding(
              theme.dp(18) + left, theme.dp(8) + top, theme.dp(18) + right, theme.dp(6) + bottom);
          return insets;
        });
    LinearLayout header = row();
    if (!page.equals("live")) {
      TextView title =
          theme.text(page.equals("memory") ? "기억" : "설정", 22, LiveTheme.TEXT);
      title.setTypeface(null, android.graphics.Typeface.BOLD);
      header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    }
    statusButton =
        theme.iconRow(
            header,
            "check",
            "모델 상태",
            false,
            v ->
                detail(
                    "상태",
                    lastStatus
                        + "\n\n"
                        + memoryLabel()
                        + (firstAudioMs < 0
                            ? ""
                            : "\n첫 음성 준비 "
                                + String.format(Locale.KOREA, "%.1f초", firstAudioMs / 1000.0))));
    if (page.equals("live")) {
      header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      if (mockVoice != null)
        mockButton = theme.iconRow(header, "wave", "모의 음성 입력", false, v -> mockVoiceDialog());
    }
    theme.iconRow(
        header,
        "info",
        "도움말 · 길게 눌러 아이콘 설명 보기",
        false,
        v ->
            detail(
                "사용 방법",
                "아이콘을 길게 누르면 설명이 표시됩니다.\n\n"
                    + "마이크: 시작·일시정지\n"
                    + "네모: 응답 즉시 중단\n"
                    + "닫기: 대화 종료·기억 삭제\n"
                    + "말풍선: 답변 보기\n\n"
                    + "카메라와 마이크는 시작 후에만 사용합니다. 앱을 벗어나면 이번 대화와 관측을 지웁니다."));
    root.addView(header);
    content = theme.column();
    content.setPadding(0, theme.dp(12), 0, theme.dp(12));
    if (page.equals("live")) root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    else {
      ScrollView scroll = new ScrollView(this);
      scroll.setFillViewport(true);
      scroll.setClipToPadding(false);
      scroll.addView(content);
      root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    switch (page) {
      case "welcome":
        welcome();
        break;
      case "setup":
        setup();
        break;
      case "permissions":
        permissions();
        break;
      case "memory":
        memoryPage();
        break;
      case "settings":
        settings();
        break;
      default:
        live();
    }
    if (page.equals("live") || page.equals("memory") || page.equals("settings")) {
      LinearLayout nav = row();
      nav.setGravity(Gravity.CENTER);
      for (String item : new String[] {"live", "memory", "settings"}) {
        LinearLayout cell = row();
        cell.setGravity(Gravity.CENTER);
        nav.addView(cell, new LinearLayout.LayoutParams(0, theme.dp(64), 1));
        theme.iconRow(
            cell,
            item.equals("live") ? "wave" : item,
            item.equals("live") ? "라이브" : item.equals("memory") ? "기억" : "설정",
            page.equals(item),
            v -> navigate(item));
      }
      root.addView(nav);
    }
    setContentView(root);
    root.requestApplyInsets();
    refreshIndicators();
  }

  private void welcome() {
    content.setGravity(Gravity.CENTER);
    content.addView(theme.orbit(), new LinearLayout.LayoutParams(-1, theme.dp(220)));
    content.addView(theme.title("보고, 듣고, 함께."));
    LinearLayout badges = row();
    badges.setGravity(Gravity.CENTER);
    theme.iconRow(
        badges,
        "shield",
        "기기 안에서 처리 · 서버 전송 없음",
        false,
        v -> detail("기기 안에서", "음성·화면을 서버로 보내지 않습니다. 모델은 로컬 ZIP으로 한 번 준비합니다."));
    theme.iconRow(
        badges,
        "memory",
        "이번 대화만 기억 · 종료하면 삭제",
        false,
        v -> detail("이번 대화만", "질문할 때 본 최근 관측만 잠시 기억합니다. 앱을 벗어나면 대화와 관측을 지웁니다."));
    content.addView(badges);
    LinearLayout next = row();
    next.setGravity(Gravity.CENTER);
    theme.iconRow(
        next,
        "arrow",
        "시작하기",
        true,
        v -> {
          page = "setup";
          render();
        });
    content.addView(next);
    content.addView(theme.text("●  ○  ○", 12, LiveTheme.MUTED));
  }

  private void setup() {
    content.setGravity(Gravity.CENTER);
    content.addView(theme.title("모델 준비"));
    LinearLayout action = row();
    action.setGravity(Gravity.CENTER);
    LiveTheme.IconButton install = theme.icon("download", "모델 ZIP 선택", true, v -> chooseModels());
    action.addView(install, new LinearLayout.LayoutParams(theme.dp(88), theme.dp(88)));
    content.addView(action);
    LinearLayout sizes = row();
    sizes.setGravity(Gravity.CENTER);
    theme.chip(
        sizes,
        "5.5 GB",
        "모델 ZIP 약 5.5 GB",
        false,
        v -> detail("모델 가져오기", "검증된 Qwen 모델 ZIP을 기기에서 선택하세요. 준비 중에는 앱을 열어 두세요."));
    theme.chip(
        sizes,
        "+6 GB",
        "설치 여유 공간 6 GB 이상 · 원본 ZIP 별도",
        false,
        v -> detail("저장 공간", "새 설치에 6 GB 이상 여유 공간이 필요합니다. 원본 ZIP을 보관할 공간은 별도입니다."));
    content.addView(sizes);
    LinearLayout next = row();
    next.setGravity(Gravity.CENTER);
    theme.iconRow(
        next,
        "back",
        "처음으로",
        false,
        v -> {
          page = "welcome";
          render();
        });
    theme.iconRow(
        next,
        "arrow",
        "다음 · 사용 방식",
        true,
        v -> {
          page = "permissions";
          render();
        });
    content.addView(next);
    content.addView(theme.text("○  ●  ○", 12, LiveTheme.MUTED));
  }

  private void permissions() {
    content.setGravity(Gravity.CENTER);
    content.addView(theme.title("듣고 볼 준비"));
    LinearLayout sensors = row();
    sensors.setGravity(Gravity.CENTER);
    theme.iconRow(
        sensors,
        "mic",
        "마이크 · 라이브 시작 시 권한 요청",
        true,
        v -> detail("마이크", "말로 질문할 때 필요합니다. 권한을 거절하면 키보드로 질문할 수 있습니다."));
    LiveTheme.IconButton camera =
        theme.iconRow(
            sensors,
            cameraEnabled ? "camera" : "camera-off",
            "카메라와 함께 시작",
            cameraEnabled,
            v -> {
              cameraEnabled = !cameraEnabled;
              render();
            });
    camera.setTooltipText(cameraEnabled ? "카메라 포함 · 누르면 음성만 사용" : "카메라 제외 · 누르면 포함");
    content.addView(sensors);
    content.addView(theme.text("카메라는 선택", 14, LiveTheme.MUTED));
    LinearLayout next = row();
    next.setGravity(Gravity.CENTER);
    theme.iconRow(
        next,
        "check",
        "준비 화면으로",
        true,
        v -> {
          preferences.edit().putBoolean("onboarded", true).apply();
          page = "live";
          render();
        });
    content.addView(next);
    content.addView(theme.text("○  ○  ●", 12, LiveTheme.MUTED));
  }

  private void live() {
    FrameLayout stage = new FrameLayout(this);
    stage.setBackground(theme.shape(LiveTheme.SURFACE, 28));
    stage.setClipToOutline(true);
    cameraPlaceholder = theme.assistant(new LiveTheme.OrbSignal() {
      public int mode() {
        long id = epoch.get();
        if (isVoicePlaying()) return 3;
        if (thinkingEpoch == id || (speakingEpoch == id && firstPlaybackMs < 0)) return 2;
        return listening ? 1 : 0;
      }
      public float level() { return currentVoiceLevel(); }
    });
    FrameLayout.LayoutParams orbit =
        new FrameLayout.LayoutParams(theme.dp(260), theme.dp(260), Gravity.CENTER);
    stage.addView(cameraPlaceholder, orbit);
    FrameLayout cameraCard = new FrameLayout(this);
    cameraCard.setBackground(theme.shape(LiveTheme.LINE, 16));
    cameraCard.setClipToOutline(true);
    cameraCard.setContentDescription("AI가 보고 있는 현재 화면");
    cameraCard.setTooltipText("AI가 보고 있는 현재 화면 · 카메라를 끄면 미리보기도 꺼집니다");
    preview = new TextureView(this);
    preview.setContentDescription("현재 카메라 미리보기");
    cameraCard.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    TextView cameraDot = theme.text("●", 12, LiveTheme.MINT);
    cameraDot.setPadding(theme.dp(8), 0, theme.dp(8), 0);
    cameraDot.setContentDescription("카메라 사용 중");
    cameraCard.addView(cameraDot, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
    FrameLayout.LayoutParams cameraLayout = new FrameLayout.LayoutParams(
        theme.dp(112), theme.dp(84), Gravity.TOP | Gravity.RIGHT);
    cameraLayout.setMargins(theme.dp(12), theme.dp(12), theme.dp(12), 0);
    stage.addView(cameraCard, cameraLayout);
    cameraPreviewCard = cameraCard;
    LinearLayout top = row();
    top.setPadding(theme.dp(8), theme.dp(8), theme.dp(8), 0);
    top.setGravity(Gravity.LEFT);
    memoryBadge = theme.iconRow(top, "memory", "이번 대화의 관측 기억", false, v -> navigate("memory"));
    watchButton = theme.iconRow(top, "bell", "먼저 알려주기", false, v -> watchDialog());
    cookingButton = theme.iconRow(top, "cook", "요리 함께하기", false, v -> cookingDialog());
    stage.addView(top, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
    LinearLayout toolbar = row();
    toolbar.setGravity(Gravity.CENTER);
    toolbar.setPadding(0, theme.dp(4), 0, theme.dp(8));
    cameraButton =
        theme.iconRow(
            toolbar,
            "camera",
            "카메라 사용",
            cameraEnabled,
            v -> {
              boolean resume = listening;
              stopCapture();
              sampleVideoUri = null;
              cameraEnabled = !cameraEnabled;
              memory.cameraOff();
              if (resume) startCapture();
              else refreshIndicators();
            });
    cameraButton.setOnLongClickListener(v -> {
      new AlertDialog.Builder(this).setTitle("영상 입력")
          .setItems(new String[]{"샘플 영상 선택", "선택한 영상 처음부터", "실제 카메라", "영상만 끄기"}, (dialog, which) -> {
            if (which == 3 && sampleVideo != null) { stopSampleVideo(); return; }
            stopCapture();
            if (which == 0) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), 12);
            else if (which == 1 && sampleVideoUri != null) { cameraEnabled = true; startCapture(); }
            else if (which == 2) { sampleVideoUri = null; cameraEnabled = true; startCapture(); }
          }).show();
      return true;
    });
    voiceButton =
        theme.iconRow(
            toolbar,
            "volume",
            "음성으로 답변 듣기",
            speechEnabled,
            v -> toggleSpeech());
    theme.iconRow(toolbar, "keyboard", "텍스트로 질문하기", false, v -> openConversation(true));
    replyButton = theme.iconRow(toolbar, "bubble", "답변 보기", false, v -> openConversation(false));
    stage.addView(toolbar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    microphoneCaption = theme.column();
    microphoneCaption.setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(8));
    microphoneCaption.setBackground(theme.shape(LiveTheme.BG, 16));
    microphoneCaption.setTooltipText("마이크 입력 크기와 인식한 말 · 말을 마치면 인식 결과가 표시됩니다");
    LinearLayout inputRow = row();
    inputRow.setGravity(Gravity.CENTER_VERTICAL);
    LiveTheme.IconButton inputIcon = theme.icon("mic", "마이크 입력과 음성 인식 결과", false,
        v -> detail("인식한 말", microphoneTranscript.isEmpty() ? "말을 마치면 인식 결과가 여기에 표시돼요." : microphoneTranscript));
    inputRow.addView(inputIcon, new LinearLayout.LayoutParams(theme.dp(36), theme.dp(36)));
    microphoneText = theme.text("", 15, LiveTheme.TEXT);
    microphoneText.setMaxLines(2);
    microphoneText.setEllipsize(android.text.TextUtils.TruncateAt.END);
    microphoneText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    inputRow.addView(microphoneText, new LinearLayout.LayoutParams(0, -2, 1));
    microphoneMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    microphoneMeter.setMax(100);
    microphoneMeter.setProgressTintList(android.content.res.ColorStateList.valueOf(LiveTheme.MINT));
    microphoneMeter.setContentDescription("마이크 입력 크기");
    inputRow.addView(microphoneMeter, new LinearLayout.LayoutParams(theme.dp(36), theme.dp(6)));
    microphoneCaption.addView(inputRow);
    FrameLayout.LayoutParams captionLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    captionLayout.setMargins(theme.dp(16), 0, theme.dp(16), theme.dp(76));
    stage.addView(microphoneCaption, captionLayout);
    content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    cookingPanel = theme.column();
    cookingPanel.setBackground(theme.shape(LiveTheme.SURFACE, 18));
    cookingPanel.setPadding(theme.dp(12), theme.dp(4), theme.dp(12), theme.dp(4));
    cookingStep = theme.text("", 16, LiveTheme.TEXT);
    cookingStep.setMaxLines(3); cookingPanel.addView(cookingStep);
    LinearLayout cookingControls = row();
    cookingControls.setGravity(Gravity.CENTER);
    theme.iconRow(cookingControls, "back", "이전 단계", false, v -> cookingControl("이전 단계"));
    cookingPause = theme.iconRow(cookingControls, "pause", "요리 안내 잠시 멈추기", false,
        v -> cookingControl(cooking.paused() ? "계속" : "잠깐"));
    cookingClock = theme.iconRow(cookingControls, "clock", "요리 타이머", false, v -> cookingTimerDialog());
    theme.iconRow(cookingControls, "check", "이 단계 완료 확인", true, v -> cookingControl("다 했어"));
    cookingPanel.addView(cookingControls); content.addView(cookingPanel);
    LinearLayout controls = row();
    controls.setGravity(Gravity.CENTER);
    controls.setPadding(0, theme.dp(16), 0, theme.dp(4));
    theme.iconRow(
        controls,
        "stop",
        "응답 즉시 중단",
        false,
        v -> {
          cancelMockInput();
          invalidate();
          show(listening ? "응답을 멈췄어요. 계속 듣고 있습니다." : "응답을 멈췄어요.");
        });
    startButton =
        theme.icon(
            "mic",
            "라이브 시작",
            true,
            v -> {
              if (listening) stopCapture();
              else startCapture();
            });
    LinearLayout.LayoutParams mic = new LinearLayout.LayoutParams(theme.dp(80), theme.dp(80));
    mic.setMargins(theme.dp(20), 0, theme.dp(20), 0);
    controls.addView(startButton, mic);
    theme.iconRow(
        controls,
        "close",
        "대화 종료 · 기억 지우기",
        false,
        v -> {
          endSession();
          render();
          show("대화와 관측 기억을 지웠습니다.");
        });
    content.addView(controls);
  }

  private void mockVoiceDialog() {
    if (mockVoice == null) return;
    if (activeSheet != null) activeSheet.dismiss();
    LinearLayout panel = theme.column();
    panel.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(8));
    panel.addView(theme.text("말할 문장을 음성으로 만들어 입력해요. AI가 답하는 중에도 보낼 수 있어요. 모의 입력 중에는 실제 마이크 소리를 사용하지 않아요.", 14, LiveTheme.TEXT));
    EditText text = new EditText(this);
    text.setTextColor(LiveTheme.TEXT);
    text.setHintTextColor(LiveTheme.MUTED);
    text.setHint("말할 문장"); text.setContentDescription("모의 음성 문장");
    text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
    text.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    text.setMinLines(2); text.setMaxLines(4); text.setText(mockText);
    panel.addView(text);
    CheckBox audible=new CheckBox(this);
    audible.setText("모의 사용자 음성도 듣기");audible.setTextColor(LiveTheme.TEXT);
    audible.setChecked(preferences.getBoolean("mock_audible",true));
    audible.setOnCheckedChangeListener((button,checked)->{
      preferences.edit().putBoolean("mock_audible",checked).apply();
      if(capture!=null)capture.mockAudible=checked;
    });panel.addView(audible);
    Spinner examples = new Spinner(this);
    String[] choices = {"예문 선택", "오이 샐러드 만드는 방법을 세 문장으로 알려 줘.",
        "잠깐만. 소금은 빼고 다시 알려 줘.", "삼십 초 타이머 시작해 줘.",
        "십 초 타이머로 바꿔 줘.", "타이머 취소해 줘.", "잠깐만.", "계속해.", "지금 작업은 뭐야?"};
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    examples.setAdapter(adapter); examples.setContentDescription("모의 음성 예문");
    examples.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position > 0) text.setText(choices[position]);
      }
      public void onNothingSelected(AdapterView<?> parent) {}
    });
    panel.addView(examples);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("모의 음성 입력").setView(panel)
        .setPositiveButton("음성 보내기", null)
        .setNeutralButton("실제 마이크로", (d, w) -> useRealMicrophone())
        .setNegativeButton("닫기", null).create();
    activeSheet = dialog; dialog.show();
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (sendMockVoice(text.getText().toString())) dialog.dismiss();
    });
  }

  private boolean sendMockVoice(String text) {
    if (mockVoice == null || !foreground || !loaded || modelBusy) {
      showError("모델을 준비한 뒤 모의 음성을 보내세요."); return false;
    }
    if (text.isBlank() || text.length() > 160) {
      showError("160자 이내의 짧은 문장을 입력하세요."); return false;
    }
    if (!mockVoice.ready()) { showError("오프라인 한국어 음성을 준비 중이거나 설치되어 있지 않아요. 잠시 후 다시 보내세요."); return false; }
    if (!listening) startCapture();
    if (!listening || capture == null) return false;
    if (mockVoice.busy() || capture.mockInput.feeding()) {
      showError("모의 음성이 아직 입력 중이에요. 끝난 뒤 다시 보내세요."); return false;
    }
    if (!capture.mockInput.enabled()) {
      invalidate(); userSpeaking = false; capture.mockInput.enable();
    }
    mockText = text.trim();
    mockVoiceCapture = captureEpoch.get(); mockVoiceEpoch = epoch.get();
    long id = ++mockVoiceId;
    microphoneState = "모의 음성 준비 중";
    mockVoice.prepare(id, mockText);
    show("모의 음성을 준비합니다. 실제 마이크로 돌아가려면 파형 버튼을 누르세요.");
    return true;
  }

  private void cancelMockInput() {
    mockVoiceId++;
    if (mockVoice != null) mockVoice.cancel();
    if (capture != null) capture.mockInput.clear();
  }

  private void useRealMicrophone() {
    cancelMockInput();
    if (capture != null) capture.mockInput.disable();
    invalidate(); userSpeaking = false; microphoneState = listening ? "마이크 연결 중" : "";
    show("실제 마이크 입력으로 돌아왔어요.");
  }

  private void openConversation(boolean compose) {
    if (compose) {
      stopCapture();
      speechEnabled = false;
    }
    replyUnread = false;
    if (activeSheet != null) activeSheet.dismiss();
    LinearLayout panel = theme.column();
    panel.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(12));
    panel.setBackgroundColor(LiveTheme.BG);
    LinearLayout toolbar = row();
    chatVoiceButton = theme.iconRow(toolbar, "volume-off", "채팅 음성 답변", false,
        v -> toggleSpeech());
    toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    theme.iconRow(toolbar, "stop", "응답 즉시 중단", false,
        v -> { invalidate(); working.state.hold(); show("응답을 멈췄어요."); });
    theme.iconRow(toolbar, "close", "채팅 닫기", false, v -> activeSheet.dismiss());
    panel.addView(toolbar);
    chatScroll = new ScrollView(this);
    chatScroll.setFillViewport(true);
    chatMessages = theme.column();
    chatMessages.setPadding(0, theme.dp(16), 0, theme.dp(16));
    chatScroll.addView(chatMessages);
    panel.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    rebuildChat();
    LinearLayout input = row();
    input.setPadding(0, theme.dp(8), 0, 0);
    question = new EditText(this);
    question.setHint("질문 입력");
    question.setTextColor(LiveTheme.TEXT);
    question.setHintTextColor(LiveTheme.MUTED);
    question.setBackground(theme.shape(LiveTheme.SURFACE, 20));
    question.setPadding(theme.dp(16), theme.dp(10), theme.dp(16), theme.dp(10));
    question.setSingleLine();
    question.setFilters(new InputFilter[] {new InputFilter.LengthFilter(300)});
    question.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
    question.setOnEditorActionListener((view, action, event) -> {
      if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { askText(); return true; }
      return false;
    });
    input.addView(question, new LinearLayout.LayoutParams(0, theme.dp(56), 1));
    theme.iconRow(input, "send", "질문 보내기", true, v -> askText());
    panel.addView(input);
    activeSheet = new Dialog(this);
    activeSheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
    activeSheet.setContentView(panel, new ViewGroup.LayoutParams(-1, -1));
    activeSheet.setOnDismissListener(dialog -> {
      question = null;
      responseView = null;
      chatMessages = null;
      chatScroll = null;
      chatVoiceButton = null;
      for (ChatTurn turn : chatTurns) { turn.questionView = null; turn.answerView = null; }
    });
    activeSheet.show();
    Window window = activeSheet.getWindow();
    window.setBackgroundDrawable(theme.shape(LiveTheme.BG, 24));
    window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels, theme.dp(640)), -1);
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        | (compose ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                   : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (compose) question.requestFocus();
    else { panel.setFocusableInTouchMode(true); panel.requestFocus(); }
    refreshIndicators();
    scrollChat(true);
  }

  private void toggleSpeech() {
    speechEnabled = !speechEnabled;
    // Turning audio off also cuts off an in-flight TTS request immediately.
    if (!speechEnabled) invalidate();
    show(speechEnabled ? "다음 답변부터 음성으로 들려드려요." : "텍스트 답변만 표시합니다.");
  }

  private void cookingDialog() {
    if (memoryCount == 0) { showError("기억을 켠 뒤 요리 안내를 시작하세요."); return; }
    if (activeSheet != null) activeSheet.dismiss();
    EditText input = new EditText(this);
    input.setText(cooking.request());
    input.setHint("만들 요리·있는 재료·빼고 싶은 재료");
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(400)});
    input.setTextColor(LiveTheme.TEXT); input.setHintTextColor(LiveTheme.MUTED);
    input.setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(12));
    LinearLayout form=theme.column();form.addView(input);
    CheckBox ambient=new CheckBox(this);ambient.setText("영상으로 진행을 살피고 다음 단계 알려주기");
    ambient.setTextColor(LiveTheme.TEXT);ambient.setChecked(cooking.ambient());form.addView(ambient);
    activeSheet = new AlertDialog.Builder(this).setTitle("요리 함께하기")
        .setMessage("말로 정해도 돼요. 진행 단계는 이번 대화에서만 기억해요.")
        .setView(form).setPositiveButton(cooking.active() ? "순서 다시 잡기" : "시작", (dialog, which) -> {
          if (!loaded) { showError("모델을 먼저 준비하세요."); return; }
          long id = invalidate();
          cooking.start(input.getText().toString()); cookingScenes.clear(); cookingTimerPending=false;
          cooking.ambient(ambient.isChecked());cookingGuidanceEpoch=-1;
          assistantMemory.stopWatching();
          if (cooking.request().isBlank()) {
            resetOutput(id); cookingReply(id, "요리 함께하기", "무엇을 만들까요? 있는 재료도 알려주세요.");
          } else submitCooking(null, cooking.request(), id, SystemClock.elapsedRealtime());
          refreshIndicators();
        }).setNeutralButton("요리 안내 종료", (dialog, which) -> {
          invalidate(); cooking.clear(); cookingScenes.clear(); cookingTimerPending=false; refreshIndicators();
        }).setNegativeButton("닫기", null).create();
    activeSheet.show();
  }

  private void cookingTimerDialog() {
    if (!cooking.active()) return;
    EditText input = new EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setHint("초 · 최대 3600");
    activeSheet = new AlertDialog.Builder(this).setTitle("타이머 · 초").setView(input)
        .setPositiveButton("시작", (dialog, which) -> {
          try { cooking.timer(Integer.parseInt(input.getText().toString()), SystemClock.elapsedRealtime()); refreshIndicators(); }
          catch (Exception e) { showError("1~3600초를 입력하세요."); }
        }).setNeutralButton("타이머 취소", (dialog, which) -> cooking.cancelTimer())
        .setNegativeButton("닫기", null).create();
    activeSheet.show();
  }

  private void cookingControl(String text) {
    if (!cooking.active()) return;
    long id = invalidate(); resetOutput(id);
    String answer = cooking.command(text);
    if (answer == null) answer = "먼저 만들 요리와 재료를 알려주세요.";
    cookingReply(id, text, answer);
  }

  private void cookingReply(long id, String user, String answer) {
    if (!alive || epoch.get() != id) return;
    if (firstTextMs < 0) firstTextMs = SystemClock.elapsedRealtime() - requestStarted;
    thinkingEpoch = -1;
    if(speechEnabled && localSpeech.ready()) {
      streamSpeechEpoch=id;sentenceVoice.start(id);
    }
    if(CookingSession.resumeQuestion(user) && !cooking.pendingGuidance().isEmpty()) {
      cookingGuidanceEpoch=id;cookingGuidanceRevision=cooking.revision();
    }
    onModelText(id, user, answer);
    refreshIndicators();
  }

  private void speakCooking(long id, String text) {
    if (epoch.get() != id || !foreground || !speechEnabled) return;
    if (!acquireAudioFocus()) {audioFailedEpoch=id;followupAudioFailed=true;return;}
    localSpeechEpoch = id; audioDoneEpoch = -1; thinkingEpoch = -1;
    // Voice is synthesized independently of the vision worker, never by slowing speech down.
    localSpeech.speak(id, text);
  }

  private void submitCooking(byte[] pcm, String text, long id, long at) {
    runOnUiThread(() -> {
      if (!alive || epoch.get() != id || !cooking.active()) return;
      resetOutput(id);
      ChatTurn turn = chatTurn(id); turn.question = text; updateChatTurn(turn);
      String direct = text.isBlank() ? null : cooking.command(text);
      lastEvidenceAt=lastEvidenceSourceMs=-1;lastEvidenceSource="unavailable";
      if (direct != null) { lastResponsePath="task-memory-policy";cookingReply(id, text, direct); return; }
      final ObservationMemory.Frame anchored = !cameraEnabled ? null :
          alignedTurn==finalizedAsrTurn && at==alignedAnchor
              ? (alignedEvidence.isEmpty()?null:alignedEvidence.get(0).frame) : memory.aligned(at);
      int revision = cooking.revision();
      inference.execute(() -> {
        File folder = new File(sessionCache, "cook-" + id);
        try {
          if (!alive || epoch.get() != id || !loaded) return;
          folder.mkdirs();
          String user = text;
          if (user.isBlank()) {
            File wav = new File(folder, "question.wav"); writeWav(wav, pcm);
            user = nativeTranscribe(handle, wav.getAbsolutePath(), id);
            showMicrophoneTranscript(id, user);
          }
          if (epoch.get() != id) return;
          final String transcript = user;
          FutureTask<Boolean> command = new FutureTask<>(() -> {
            if (epoch.get() != id || !cooking.active()) return true;
            String answer = cooking.command(transcript);
            if (answer == null) return false;
            lastResponsePath="task-memory-policy";cookingReply(id, transcript, answer); return true;
          });
          uiHandler.post(command);
          try { if (command.get(2, TimeUnit.SECONDS)) return; }
          catch (TimeoutException e) { command.cancel(false); throw e; }
          boolean planning = !cooking.ready();
          String image = "";
          ObservationMemory.Frame frame = anchored;
          if (frame != null) {
            lastEvidenceAt=frame.at;lastEvidenceSourceMs=frame.sourceMs;lastEvidenceSource=frame.source;
            File file = new File(folder, "current.jpg");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(frame.jpeg); }
            image = file.getAbsolutePath();
          }
          if (planning) {
            // Planning is grounded in the user's request, not incidental camera objects.
            String raw = nativeCooking(handle, transcript, "", "", id, true).trim();
            if (epoch.get() != id) return;
            if (raw.startsWith("```json\n") && raw.endsWith("```")) raw = raw.substring(8, raw.length()-3).trim();
            JSONObject json = new JSONObject(raw);
            if (json.length() != 1) throw new IllegalArgumentException("요리 순서 형식 확인 필요");
            JSONArray array = json.getJSONArray("steps"); ArrayList<String> steps = new ArrayList<>();
            for (int i=0; i<array.length(); i++) {
              if (!(array.get(i) instanceof String)) throw new IllegalArgumentException("단계 형식 확인 필요");
              steps.add(array.getString(i));
            }
            String issue=CookingPlanPolicy.issue(transcript,steps);
            if(!issue.isEmpty()){
              // One bounded correction, then abstain instead of speaking the known wrong technique.
              String repaired=nativeCooking(handle,transcript,"계획 검토: "+issue,"",id,true).trim();
              if(epoch.get()!=id)return;
              if(repaired.startsWith("```json\n") && repaired.endsWith("```"))repaired=repaired.substring(8,repaired.length()-3).trim();
              JSONObject correction=new JSONObject(repaired);
              if(correction.length()!=1)throw new IllegalArgumentException("조리법 확인 필요");
              JSONArray corrected=correction.getJSONArray("steps");steps.clear();
              for(int i=0;i<corrected.length();i++){
                if(!(corrected.get(i) instanceof String))throw new IllegalArgumentException("조리법 확인 필요");
                steps.add(corrected.getString(i));
              }
              if(!CookingPlanPolicy.issue(transcript,steps).isEmpty())throw new IllegalArgumentException("조리 방식 확인이 필요해요. 원하는 순서를 알려주세요.");
            }
            runOnUiThread(() -> {
              if (epoch.get() != id || cooking.revision() != revision) return;
              try {
                cooking.plan(transcript, steps, revision);
                cookingReply(id, transcript, cooking.current());
              } catch (Exception e) { thinkingEpoch = -1; showError(e.getMessage()); }
            });
          } else {
            String observation = image.isEmpty() ? "현재 화면 없음." : "사용자 발화 시각에 맞춘 관측(at="+frame.at+
                ", source="+frame.source+", source_ms="+frame.sourceMs+"): <img>" + image + "</img>";
            String context = JSONObject.quote(cooking.context() + "\n" + assistantMemory.context(SystemClock.elapsedRealtime()));
            nativeInfer(handle, "", observation, transcript, context, id, false);
          }
        } catch (Exception e) {
          runOnUiThread(() -> { if (epoch.get() == id) { thinkingEpoch = -1; showError("요리 안내를 준비하지 못했어요. " + e.getMessage()); } });
        } finally { remove(folder); }
      });
    });
  }

  static String parseCookingNotice(String raw) throws org.json.JSONException {
    if(raw.length()>512)throw new org.json.JSONException("notice limit");
    Object value=new org.json.JSONTokener(raw).nextValue();
    // This model sometimes emits one JSON-encoded string containing the object.
    // Unwrap exactly once; never infer a positive notice from arbitrary output.
    if(value instanceof String)value=new org.json.JSONTokener((String)value).nextValue();
    if(!(value instanceof JSONObject))throw new org.json.JSONException("notice object");
    JSONObject json=(JSONObject)value;
    if(json.length()!=1 || !(json.get("notice") instanceof String))throw new org.json.JSONException("notice schema");
    String notice=json.getString("notice").trim();
    if(notice.length()>100)throw new org.json.JSONException("notice length");
    return notice;
  }

  static JSONObject parseTaskObservation(String raw) throws org.json.JSONException {
    if(raw.length()>768)throw new org.json.JSONException("observation limit");
    org.json.JSONTokener tokens=new org.json.JSONTokener(raw);Object value=tokens.nextValue();
    if(tokens.nextClean()!=0)throw new org.json.JSONException("trailing observation data");
    if(value instanceof String){tokens=new org.json.JSONTokener((String)value);value=tokens.nextValue();
      if(tokens.nextClean()!=0)throw new org.json.JSONException("trailing wrapped data");}
    if(!(value instanceof JSONObject))throw new org.json.JSONException("observation object");
    JSONObject json=(JSONObject)value;
    if(json.length()!=2 || !(json.get("phase") instanceof String) || !(json.get("evidence") instanceof String)
        || !Arrays.asList("working","complete","unknown").contains(json.getString("phase"))
        || json.getString("evidence").isBlank() || json.getString("evidence").length()>120)
      throw new org.json.JSONException("observation schema");
    return json;
  }

  private long conversationScopeStart;
  private String recentContext(long at) {
    String history=assistantMemory.context(at,conversationScopeStart);
    return history.length()<=600?history:history.substring(history.length()-600);
  }

  private boolean memoryResponseValid(long id) {
    return responseMemoryEpoch!=id || working.state.valid(responseMemoryLease);
  }

  private boolean audioDelivered(long id) {
    synchronized(audioGuard) {
      return epoch.get()==id && audioDoneEpoch==id && audioFailedEpoch!=id && firstPlaybackMs>=0 && playback.isEmpty()
          && audioQueuedSamples>0 && audioWrittenSamples==audioQueuedSamples
          && track!=null && playbackFramesWritten>0
          && Integer.toUnsignedLong(track.getPlaybackHeadPosition())>=playbackFramesWritten;
    }
  }

  /** Timer receipts only queue obligations. Actual re-entry waits for final ASR and an open floor. */
  private void workingTick() {
    if(!foreground)return;
    long now=SystemClock.elapsedRealtime();working.tick(now);
    if(pendingHeardEpoch==epoch.get() && audioDelivered(pendingHeardEpoch)) {
      lastDeliveredEpoch = pendingHeardEpoch;
      assistantMemory.turn(pendingHeardQuestion,pendingHeardAnswer,now);pendingHeardEpoch=-1;
    }
    WorkingMemory.Followup current=activeFollowup;
    if(current!=null) {
      if(followupAudioFailed) {
        working.state.deliveryFailed(current,now);activeFollowup=null;followupEpoch=-1;
      } else if(audioDelivered(followupEpoch)) {
        working.state.delivered(current);activeFollowup=null;followupEpoch=-1;
      }
      return;
    }
    if(!listening || !loaded || userSpeaking || working.state.snapshot().userActive || cooking.away())return;
    boolean busy=thinkingEpoch==epoch.get() || isVoicePlaying() || !playback.isEmpty()
        || (localSpeechEpoch==epoch.get() && audioDoneEpoch!=epoch.get())
        || (speakingEpoch==epoch.get() && audioFailedEpoch!=epoch.get() && !audioDelivered(epoch.get()));
    // Claim after output invalidation: the resulting lease belongs to the new response epoch.
    if(working.state.next(now,busy)==null)return;
    long id=invalidate(true);
    WorkingMemory.Followup next=working.state.next(now,false);
    if(next==null)return;
    activeFollowup=next;followupEpoch=id;followupAudioFailed=false;
    responseMemoryEpoch=id;responseMemoryLease=next.lease;
    resetOutput(id);localSpeechEpoch=id;
    onModelText(id,"작업 결과",next.text);
    if(!speechEnabled){working.state.delivered(next);activeFollowup=null;followupEpoch=-1;}
  }

  private void cookingTick() {
    if (!cooking.active() || !foreground) return;
    long now = SystemClock.elapsedRealtime();
    if(cookingGuidanceEpoch>=0) {
      if(epoch.get()!=cookingGuidanceEpoch)cookingGuidanceEpoch=-1;
      else if(!speechEnabled || audioDelivered(cookingGuidanceEpoch)) {
        cooking.guidanceDelivered(cookingGuidanceRevision);cookingGuidanceEpoch=-1;
      } else if(audioFailedEpoch==cookingGuidanceEpoch)cookingGuidanceEpoch=-1;
    }
    boolean floorBusy=userSpeaking || working.state.snapshot().userActive || working.state.snapshot().speechHeld
        || isVoicePlaying() || thinkingEpoch==epoch.get() || !playback.isEmpty()
        || (localSpeechEpoch==epoch.get() && audioDoneEpoch!=epoch.get());
    if(listening && loaded && cookingGuidanceEpoch<0 && !floorBusy && !cooking.pendingGuidance().isEmpty()) {
      String notice=cooking.pendingGuidance();int revision=cooking.revision();
      long id=invalidate();resetOutput(id);cookingGuidanceEpoch=id;cookingGuidanceRevision=revision;
      cookingReply(id,"요리 중 확인",notice);return;
    }
    if (cooking.timerDue(now)) cookingTimerPending=true;
    if (cookingTimerPending && !floorBusy && !cooking.away() && !cooking.paused()) {
      cookingTimerPending=false;
      long id = invalidate(); resetOutput(id);
      cookingReply(id, "타이머", "설정한 시간이 됐어요. 요리 상태를 직접 확인해 주세요.");
      return;
    }
    if (!cooking.ready() || cooking.index()>=cooking.count() || (cooking.paused() && !cooking.ambient()) || !listening || !cameraEnabled || !loaded || userSpeaking
        || modelBusy || proactiveBusy || inference.getActiveCount()!=0 || !inference.getQueue().isEmpty()
        || thinkingEpoch==epoch.get() || isVoicePlaying() || !playback.isEmpty()
        || (localSpeechEpoch==epoch.get() && audioDoneEpoch!=epoch.get())
        || !(cooking.ambient()?cookingScenes.taskDue(now):cookingScenes.due(now))) return;
    ObservationMemory.Frame frame = memory.current(now); if (frame == null) return;
    long id = epoch.get(); int revision = cooking.revision();
    String task = cooking.request(), step = cooking.current();
    boolean ambient=cooking.ambient();String state=cooking.observationContext();
    String visualQuestion = fastObserver && !ambient ? CookingVisualPolicy.question(step) : "";
    if (fastObserver && !ambient && visualQuestion.isEmpty()) return;
    cookingScenes.checked(now); proactiveBusy = true;
    inference.execute(() -> {
      File folder = new File(sessionCache, "cook-watch-" + id);
      try {
        if (epoch.get()!=id || SystemClock.elapsedRealtime()-frame.at>3000) return;
        folder.mkdirs(); File file = new File(folder,"current.jpg");
        try(FileOutputStream out = new FileOutputStream(file)){ out.write(frame.jpeg); }
        String reply;
        if(ambient)reply=nativeTaskObserve(handle,task,state,file.getAbsolutePath(),id).trim();
        else if(fastObserver){
          String answer=nativeVisualCheck(handle,visualQuestion,file.getAbsolutePath(),id);
          reply=new JSONObject().put("notice",CookingVisualPolicy.reminder(step,answer)).toString();
        }else reply=nativeCooking(handle, task, step, file.getAbsolutePath(), id, false).trim();
        runOnUiThread(() -> {
          long finished = SystemClock.elapsedRealtime();
          if (epoch.get()!=id || !listening || !cameraEnabled || !foreground || userSpeaking
              || cooking.revision()!=revision) return;
          if(ambient) {
            lastTaskFrame=frame;taskAttempts++;
            JSONObject diagnostic=new JSONObject();
            try {
              diagnostic.put("attempt",taskAttempts).put("source_ms",frame.sourceMs).put("at",frame.at)
                  .put("finished",finished).put("model_ms",finished-now).put("age_ms",finished-frame.at)
                  .put("owner_epoch",id).put("raw",reply.length()<=1024?reply:reply.substring(0,1024))
                  .put("raw_truncated",reply.length()>1024).put("step_before",cooking.index());
              if(finished-frame.at>15000)diagnostic.put("status","stale_age_rejected");
              else {
                JSONObject observed=parseTaskObservation(reply);
                String key=java.util.Arrays.toString(java.security.MessageDigest.getInstance("SHA-256").digest(frame.jpeg));
                cooking.observed(observed.getString("phase"),observed.getString("evidence"),key,
                    frame.source,frame.sourceMs,frame.at,finished,revision);
                diagnostic.put("status",cooking.observationStatus()).put("parsed",observed);
                cookingChecks++; cookingObservationMs=finished-now;lastCookingObservation=reply;lastCookingError="";
              }
            }catch(Exception e){try{diagnostic.put("status","schema_rejected").put("error",e.toString());}catch(Exception ignored){}
              lastCookingError="진행 관측 형식 확인 필요";}
            try{diagnostic.put("step_after",cooking.index());}catch(Exception ignored){}
            lastTaskDiagnostic=diagnostic.toString();refreshIndicators();return;
          }
          if(finished-frame.at>(fastObserver?2500:6000))return;
          cookingChecks++; cookingObservationMs = finished-now; lastCookingObservation=reply; lastCookingError="";
          String notice;
          try { notice=parseCookingNotice(reply); }
          catch(Exception e) { lastCookingError="관측 형식 확인 필요"; return; }
          if (!cooking.notice(notice, finished, revision)) return;
          long noticeId = invalidate(); resetOutput(noticeId);
          cookingReply(noticeId, "요리 중 확인", notice);
          Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
        });
      } catch(Exception e) { runOnUiThread(()->{if(epoch.get()!=id)return;lastCookingError=e.toString();
        if(ambient){lastTaskFrame=frame;taskAttempts++;
          try{lastTaskDiagnostic=new JSONObject().put("attempt",taskAttempts).put("source_ms",frame.sourceMs)
              .put("model_ms",SystemClock.elapsedRealtime()-now).put("raw","").put("status","inference_error")
              .put("error",e.toString()).put("step_before",cooking.index()).put("step_after",cooking.index()).toString();}
          catch(Exception ignored){}}
      }); }
      finally { remove(folder); proactiveBusy = false; }
    });
  }

  private void watchDialog() {
    if (memoryCount == 0) { showError("기억을 켠 뒤 먼저 알려주기를 사용하세요."); return; }
    if (activeSheet != null) activeSheet.dismiss();
    EditText input = new EditText(this);
    input.setText(assistantMemory.task(SystemClock.elapsedRealtime()));
    input.setHint("예: 컵이 보이면 알려줘");
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(160)});
    input.setTextColor(LiveTheme.TEXT);
    input.setHintTextColor(LiveTheme.MUTED);
    input.setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(12));
    activeSheet = new AlertDialog.Builder(this).setTitle("먼저 알려주기")
        .setMessage("이번 대화에서만 기억해요. 비워 두면 중요한 장면 변화를 알려줘요.")
        .setView(input).setPositiveButton("기억하고 살피기", (dialog, which) -> {
          invalidate();
          assistantMemory.watch(input.getText().toString(), SystemClock.elapsedRealtime());
          show(listening ? "조건을 기억했어요. 화면을 살펴볼게요." : "조건을 기억했어요. 라이브를 시작하면 화면을 살펴봐요.");
        }).setNeutralButton("중지", (dialog, which) -> {
          invalidate(); assistantMemory.stopWatching(); show("먼저 알려주기를 멈췄어요.");
        }).setNegativeButton("닫기", null).create();
    activeSheet.show();
  }

  private void proactiveTick() {
    if (cooking.active()) return;
    long now = SystemClock.elapsedRealtime();
    if (!alive || !foreground || !loaded || !listening || !cameraEnabled || userSpeaking
        || modelBusy || proactiveBusy || inference.getActiveCount() != 0 || !inference.getQueue().isEmpty()
        || thinkingEpoch == epoch.get() || isVoicePlaying() || !playback.isEmpty()
        || !assistantMemory.due(now)) return;
    ObservationMemory.Frame frame = memory.current(now);
    if (frame == null) return;
    String previous = assistantMemory.scene(now), task = assistantMemory.task(now);
    long id = epoch.get();
    assistantMemory.checked(now);
    proactiveBusy = true;
    inference.execute(() -> {
      File folder = new File(sessionCache, "observe-" + id);
      try {
        if (!alive || epoch.get() != id || SystemClock.elapsedRealtime() - frame.at > 3000) return;
        folder.mkdirs();
        File image = new File(folder, "current.jpg");
        try (FileOutputStream out = new FileOutputStream(image)) { out.write(frame.jpeg); }
        String result = nativeObserve(handle, image.getAbsolutePath(), previous, task, id);
        if (epoch.get() != id) return;
        runOnUiThread(() -> { if (alive && epoch.get() == id) lastObservation = result; });
        // Some local model outputs quote JSON booleans. Accept only the two exact
        // literals; arbitrary strings/numbers never become positive evidence.
        JSONObject json = new JSONObject(result);
        for (String key : new String[] {"match", "changed"}) {
          Object flag = json.get(key);
          if ("true".equals(flag)) json.put(key, true);
          else if ("false".equals(flag)) json.put(key, false);
        }
        if (!(json.get("scene") instanceof String) || !(json.get("notice") instanceof String)
            || !(json.get("match") instanceof Boolean) || !(json.get("changed") instanceof Boolean)
            || json.length() != 4 || json.getString("scene").length() > 240
            || json.getString("notice").length() > 160) throw new IllegalArgumentException("관측 형식 확인 필요");
        runOnUiThread(() -> {
          if (!alive || !foreground || !listening || !cameraEnabled || userSpeaking || epoch.get() != id) return;
          long finished = SystemClock.elapsedRealtime();
          if (finished - frame.at > 12000) { show("관측이 늦어져 다음 화면에서 다시 확인합니다."); return; }
          proactiveChecks++; lastObservation = result;
          String notice = assistantMemory.observed(json.optString("scene"), json.optBoolean("match"),
              json.optBoolean("changed"), json.optString("notice"), frame.at, finished);
          if (!notice.isEmpty()) proactiveNotice(id, notice);
          refreshIndicators();
        });
      } catch (Exception e) {
        runOnUiThread(() -> { if (alive && epoch.get() == id) lastProactiveError = e.toString(); });
        if (epoch.get() == id) showFor(id, "이번 관측은 확신할 수 없어 알리지 않았어요.");
      } finally { remove(folder); proactiveBusy = false; }
    });
  }

  private void proactiveNotice(long id, String notice) {
    if (epoch.get() != id) return;
    // A new response epoch gives each autonomous message its own chat bubble.
    long noticeId = invalidate();
    proactiveNotices++;
    ChatTurn turn = chatTurn(noticeId);
    turn.question = "먼저 알려드려요"; turn.answer = notice; turn.complete = true;
    response = notice; replyUnread = chatMessages == null;
    updateChatTurn(turn); scrollChat(true);
    Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
    show("새 알림이 있어요 · 말풍선에서 확인하세요.");
    if (speechEnabled) {
      resetOutput(noticeId);
      if(localSpeech.ready()){speakCooking(noticeId,notice);return;}
      inference.execute(() -> {
        try {
          if (epoch.get() == noticeId) nativeSpeak(handle, notice, noticeId);
        } catch (Exception e) { showFor(noticeId, "음성은 준비하지 못했지만 알림은 글로 남겼어요."); }
        finally { if (epoch.get() == noticeId) { audioDoneEpoch = noticeId; thinkingEpoch = -1; } }
      });
    }
  }

  private ChatTurn chatTurn(long id) {
    for (ChatTurn turn : chatTurns) if (turn.id == id) return turn;
    ChatTurn turn = new ChatTurn(id);
    chatTurns.add(turn);
    if (chatTurns.size() > 8) chatTurns.remove(0);
    if (chatMessages != null) rebuildChat();
    return turn;
  }

  private void rebuildChat() {
    if (chatMessages == null) return;
    chatMessages.removeAllViews();
    responseView = null;
    if (chatTurns.isEmpty()) {
      TextView empty = theme.text("질문을 입력해 주세요", 16, LiveTheme.MUTED);
      empty.setGravity(Gravity.CENTER);
      chatMessages.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }
    for (ChatTurn turn : chatTurns) {
      turn.questionView = chatBubble(true);
      turn.answerView = chatBubble(false);
      updateChatTurn(turn);
    }
  }

  private TextView chatBubble(boolean user) {
    LinearLayout line = row();
    line.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
    line.setPadding(user ? theme.dp(36) : 0, 0, user ? 0 : theme.dp(36), theme.dp(12));
    TextView bubble = theme.text("", 17, user ? LiveTheme.BG : LiveTheme.TEXT);
    bubble.setTextIsSelectable(true);
    bubble.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(12));
    bubble.setBackground(theme.shape(user ? LiveTheme.MINT : LiveTheme.SURFACE, 20));
    line.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
    chatMessages.addView(line, new LinearLayout.LayoutParams(-1, -2));
    return bubble;
  }

  private void updateChatTurn(ChatTurn turn) {
    if (turn.questionView == null) return;
    turn.questionView.setText(turn.question.isEmpty() ? "…" : turn.question);
    turn.questionView.setContentDescription("내 질문: " + turn.question);
    String text = turn.answer.isEmpty() ? (turn.stopped ? "중단됨" : "…") : turn.answer;
    turn.answerView.setText(text + (!turn.complete && !turn.stopped && !turn.answer.isEmpty() ? " ▍" : ""));
    turn.answerView.setContentDescription("답변: " + text);
    turn.answerView.setTooltipText(turn.stopped ? "응답이 중단되었습니다" : turn.complete ? "답변 완료" : "답변 생성 중");
    if (turn == chatTurns.get(chatTurns.size() - 1)) responseView = turn.answerView;
  }

  private void scrollChat(boolean force) {
    ScrollView view = chatScroll;
    if (view == null) return;
    if (force || !view.canScrollVertically(1))
      view.post(() -> { if (chatScroll == view) view.fullScroll(View.FOCUS_DOWN); });
  }

  private void clearChat() {
    response = "";
    microphoneTranscript = ""; microphoneState = ""; microphoneTurnEpoch = -1;
    replyUnread = false;
    pendingText.set(null);
    chatTurns.clear();
    rebuildChat();
  }

  private void askText() {
    if (!loaded || modelBusy) {
      showError("먼저 설정에서 모델을 준비해 주세요.");
      return;
    }
    if (question == null) return;
    String text = question.getText().toString().trim();
    if (text.isEmpty()) return;
    if (speechEnabled && !acquireAudioFocus()) return;
    if (sessionStarted == 0) sessionStarted = SystemClock.elapsedRealtime();
    question.setText("");
    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(question.getWindowToken(), 0);
    submit(null, text, invalidate(), SystemClock.elapsedRealtime());
    scrollChat(true);
  }

  private String memoryLabel() {
    return memoryCount == 0
        ? "최근 관측 저장 안 함"
        : "최근 " + memoryCount + "개 · " + (memoryDuration / 60000) + "분 이내";
  }

  private void memoryPage() {
    String remembered = assistantMemory.visualContext(SystemClock.elapsedRealtime())+assistantMemory.context(SystemClock.elapsedRealtime());
    if (!remembered.isEmpty()) {
      LinearLayout card = theme.card(content);
      theme.iconRow(card, "bell", "부탁과 과거 관측 · 현재 화면의 증거가 아님", false, v -> watchDialog());
      TextView facts = theme.text(remembered, 15, LiveTheme.TEXT);
      facts.setTextIsSelectable(true);
      card.addView(facts);
    }
    LinearLayout options = row();
    theme.iconRow(
        options,
        memoryCount > 0 ? "memory" : "memory-off",
        "최근 관측 잠시 기억하기",
        memoryCount > 0,
        v -> {
          memoryCount = memoryCount > 0 ? 0 : 2;
          applyMemorySettings();
          render();
        });
    if (memoryCount > 0)
      for (int count : new int[] {1, 2})
        theme.chip(
            options,
            count + "개",
            "최근 관측 " + count + "개 보관",
            memoryCount == count,
            v -> {
              memoryCount = count;
              applyMemorySettings();
              render();
            });
    theme.iconRow(
        options,
        "info",
        "기억 정책 안내",
        false,
        v ->
            detail(
                "기억",
                "부탁·최근 대화·과거 관측을 선택한 시간 동안 기억합니다. 과거 관측은 현재 화면의 증거가 아닙니다.\n\n"
                    + "앱을 벗어나거나 대화를 끝내면 지웁니다. 설정값만 기기에 저장합니다."));
    content.addView(options);
    if (memoryCount > 0) {
      LinearLayout durations = row();
      theme.iconRow(
          durations,
          "clock",
          "관측 보관 시간",
          false,
          v -> detail("보관 시간", "기간이 지난 관측은 자동으로 지우고 질문 근거에서도 제외합니다."));
      for (int minutes : new int[] {1, 5, 15})
        theme.chip(
            durations,
            minutes + "분",
            "관측 " + minutes + "분 이내 보관",
            memoryDuration == minutes * 60000L,
            v -> {
              memoryDuration = minutes * 60000L;
              applyMemorySettings();
              render();
            });
      content.addView(durations);
    }
    List<ObservationMemory.Frame> frames = memory.snapshot(SystemClock.elapsedRealtime());
    displayedMemories = frames.size();
    if (frames.isEmpty()) {
      LinearLayout empty = theme.card(content);
      empty.setGravity(Gravity.CENTER);
      empty.addView(theme.orbit(), new LinearLayout.LayoutParams(-1, theme.dp(180)));
      theme.iconRow(
          empty,
          "memory-off",
          "저장된 사진 없음 · 부탁과 관측은 위에서 확인",
          false,
          v -> detail("저장된 사진 없음", "질문에 사용한 사진은 여기에, 먼저 살펴본 내용과 부탁은 위의 기억에 표시됩니다."));
    }
    for (ObservationMemory.Frame frame : frames) {
      LinearLayout card = theme.card(content);
      ImageView thumb = new ImageView(this);
      thumb.setImageBitmap(BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length));
      thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
      thumb.setContentDescription("과거 관측 사진 · 현재 화면 아님");
      card.addView(thumb, new LinearLayout.LayoutParams(-1, theme.dp(150)));
      LinearLayout meta = row();
      theme.iconRow(
          meta,
          "clock",
          "과거 관측 · 현재 화면의 증거가 아님",
          false,
          v -> detail("과거 관측", "이 사진은 이전 질문에 사용한 관측이며 지금 화면의 증거가 아닙니다."));
      TextView age = theme.text("", 14, LiveTheme.WARNING);
      memoryAgeViews.put(frame.at, age);
      meta.addView(age, new LinearLayout.LayoutParams(0, -2, 1));
      theme.iconRow(
          meta,
          "trash",
          "이 관측 삭제",
          false,
          v -> {
            invalidate();
            memory.forget(frame.at);
            clearAssistantMemory();
            clearChat();
            render();
          });
      card.addView(meta);
    }
    LinearLayout erase = row();
    erase.setGravity(Gravity.RIGHT);
    theme.iconRow(
        erase,
        "trash",
        "모든 관측과 대화 지우기",
        false,
        v -> {
          invalidate();
          memory.reset();
          clearAssistantMemory();
          clearChat();
          completedTurns = 0;
          render();
          show("이번 대화의 기억을 지웠습니다.");
        });
    content.addView(erase);
  }

  private void applyMemorySettings() {
    invalidate();
    memory.configure(memoryCount, memoryDuration);
    assistantMemory.configure(memoryCount > 0, memoryDuration);
    assistantMemory.visualCapacity(memoryCount);
    if (memoryCount == 0) { clearChat(); clearAssistantMemory(); }
    preferences
        .edit()
        .putInt("memory_count", memoryCount)
        .putLong("memory_duration", memoryDuration)
        .apply();
  }

  private void settings() {
    LinearLayout models = theme.card(content);
    models.addView(theme.text("모델", 16, LiveTheme.TEXT));
    LinearLayout modelActions = row();
    theme.iconRow(modelActions, "download", "모델 가져오기 / 교체", false, v -> chooseModels());
    theme.iconRow(
        modelActions,
        "retry",
        "모델 다시 준비",
        false,
        v -> {
          if (!modelBusy) {
            modelBusy = true;
            refreshIndicators();
            inference.execute(() -> load(new File(getFilesDir(), "models")));
          }
        });
    theme.iconRow(
        modelActions,
        "info",
        "모델과 기기 정보",
        false,
        v -> {
          ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
          ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(info);
          detail(
              "모델과 기기",
              String.format(
                  Locale.KOREA,
                  "RAM %.1f GB · 저장 공간 여유 %.1f GB\n\n"
                      + "Qwen ASR → Qwen3.5 → Qwen TTS\n"
                      + "모델과 답변 준비에는 시간이 걸릴 수 있습니다. 기기가 뜨거워지면 쉬어 주세요.",
                  info.totalMem / 1e9,
                  getFilesDir().getUsableSpace() / 1e9));
        });
    models.addView(modelActions);
    LinearLayout privacy = theme.card(content);
    privacy.addView(theme.text("권한 · 개인정보", 16, LiveTheme.TEXT));
    LinearLayout privacyActions = row();
    theme.iconRow(
        privacyActions,
        "shield",
        "Android 권한 설정",
        false,
        v ->
            startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
    theme.iconRow(
        privacyActions,
        "info",
        "개인정보 처리 안내",
        false,
        v ->
            detail(
                "개인정보",
                "라이브에서만 마이크·카메라를 사용합니다. 서버 전송, 녹음 저장, 자동 백업을 하지 않습니다. 대화와 관측은 앱을 벗어나면 지웁니다."));
    privacy.addView(privacyActions);
    LinearLayout help = theme.card(content);
    help.addView(theme.text("안내", 16, LiveTheme.TEXT));
    LinearLayout helpActions = row();
    theme.iconRow(
        helpActions,
        "play",
        "첫 안내 다시 보기",
        false,
        v -> {
          page = "welcome";
          render();
        });
    theme.iconRow(
        helpActions,
        "info",
        "앱 정보",
        false,
        v ->
            detail(
            "Live 0.7",
                "온디바이스 Qwen 시스템 듀플렉스 연구용 미리보기입니다. 답변이 느리거나 틀릴 수 있습니다. 실제 Galaxy S 음향·발열 검증은 남아"
                    + " 있습니다."));
    help.addView(helpActions);
  }

  private void navigate(String destination) {
    if (page.equals(destination)) return;
    stopCapture();
    page = destination;
    render();
  }

  private void chooseModels() {
    if (modelBusy) {
      show("모델 준비가 끝날 때까지 기다려 주세요.");
      return;
    }
    if (listening) stopCapture();
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("application/zip")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent, 10);
  }

  private void refreshIndicators() {
    if (microphoneCaption != null) {
      microphoneCaption.setVisibility(listening || !microphoneTranscript.isEmpty() || !microphoneState.isEmpty() ? View.VISIBLE : View.GONE);
      String caption = !microphoneState.isEmpty() ? microphoneState : microphoneTranscript.isEmpty() ? "듣는 중" : microphoneTranscript;
      if (capture != null && capture.mockInput.enabled()) caption = "모의 사용자 · " + caption;
      if (!microphoneText.getText().toString().equals(caption)) microphoneText.setText(caption);
      microphoneMeter.setProgress(listening ? Math.min(100, (int)(microphoneLevel * 100)) : 0);
    }
    if(cookingButton!=null)cookingButton.update("cook", "요리 도우미 · 메뉴와 재료 설정", cooking.active(), false);
    if (mockButton != null) {
      boolean enabled = capture != null && capture.mockInput.enabled();
      mockButton.update("wave", "모의 음성 입력", enabled, false);
      mockButton.badge(enabled ? "M" : "");
      mockButton.setTooltipText(enabled ? "모의 입력 켜짐 · 새 문장 보내기 / 실제 마이크로 돌아가기" : "말하지 않고 음성 입력하기");
    }
    if(cookingPanel!=null)cookingPanel.setVisibility(cooking.active()?View.VISIBLE:View.GONE);
    if(cookingStep!=null)cookingStep.setText((cooking.ready()&&cooking.index()<cooking.count()?(cooking.index()+1)+" / "+cooking.count()+"  ":"")+cooking.current());
    if(cookingPause!=null)cookingPause.update(cooking.paused()?"play":"pause", cooking.paused()?"요리 안내 재개":"요리 안내 일시정지", cooking.paused(),false);
    if(cookingClock!=null){long left=cooking.remaining(SystemClock.elapsedRealtime());cookingClock.badge(left<0?"":left>=60?(left/60)+"m":left+"s");cookingClock.setTooltipText(left<0?"타이머 설정 · 앱을 열어 둔 동안 작동": "남은 시간 "+left+"초 · 안내를 멈춰도 타이머는 계속됩니다");}

    long id = epoch.get();
    boolean thinking = thinkingEpoch == id,
        speaking = isVoicePlaying();
    String glyph =
        needsAttention
            ? "alert"
            : modelBusy
                ? "hourglass"
                : !loaded
                    ? "download"
                    : speaking ? "volume" : thinking ? "hourglass" : listening ? "wave" : "check";
    String summary =
        needsAttention
            ? lastStatus
            : modelBusy
                ? "모델 준비 중"
                : !loaded
                    ? "모델 설치 필요"
                    : speaking
                        ? "음성 출력 중"
                        : thinking ? "답변 준비 중" : listening ? "듣는 중" : "모델 준비됨 · 오프라인";
    if (statusButton != null)
      statusButton.update(glyph, summary + " · 눌러 상세 보기", false, needsAttention || !loaded);
    if (preview != null) preview.setAlpha(listening && cameraEnabled ? 1f : 0f);
    if (cameraPreviewCard != null) {
      cameraPreviewCard.setContentDescription(sampleVideoUri != null ? "샘플 영상 재생" : "AI가 보고 있는 현재 화면");
      cameraPreviewCard.setTooltipText(sampleVideoUri != null ? "샘플 영상 · 소리는 입력하지 않으며 마이크와 함께 사용합니다" : "실제 카메라 화면");
      cameraPreviewCard.setAlpha(listening && cameraEnabled ? 1f : 0f);
      cameraPreviewCard.setImportantForAccessibility(listening && cameraEnabled
          ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    if (cameraPlaceholder != null) cameraPlaceholder.invalidate();
    if (startButton != null)
      startButton.update(
          listening ? "pause" : "mic", listening ? "마이크 일시정지" : "라이브 시작", true, false);
    if (cameraButton != null) {
      cameraButton.update(
          listening && cameraEnabled ? "camera" : "camera-off",
          sampleVideoUri != null ? "샘플 영상 사용" : "카메라 사용",
          listening && cameraEnabled,
          false);
      cameraButton.badge(sampleVideoUri != null ? "V" : "");
      cameraButton.setTooltipText(
          sampleVideoUri != null ? "샘플 영상 · 길게 눌러 영상 선택 / 처음부터 / 실제 카메라" : cameraEnabled
              ? (listening ? "카메라 켜짐 · 누르면 끄기" : "카메라 대기 · 라이브 시작 후 켜짐")
              : "카메라 꺼짐 · 누르면 사용");
    }
    if (voiceButton != null) {
      voiceButton.update(
          speechEnabled ? "volume" : "volume-off", "음성으로 답변 듣기", speechEnabled, false);
      voiceButton.setTooltipText(speechEnabled ? "음성 답변 켜짐 · 누르면 끄기" : "텍스트 답변만 · 누르면 음성 사용");
    }
    if (chatVoiceButton != null) {
      chatVoiceButton.update(speechEnabled ? "volume" : "volume-off", "채팅 음성 답변", speechEnabled, false);
      chatVoiceButton.setTooltipText(speechEnabled ? "음성 켜짐 · 누르면 끄기" : "무음 채팅 · 누르면 다음 답변부터 음성 사용");
    }
    if (replyButton != null) {
      replyButton.badge(response.isEmpty() || !replyUnread ? "" : "•");
      replyButton.setTooltipText(
          response.isEmpty() ? "답변 보기 · 아직 답변 없음" : replyUnread ? "새 답변 · 눌러 보기" : "답변 다시 보기");
    }
    if (memoryBadge != null) {
      int count = memory.snapshot(SystemClock.elapsedRealtime()).size() + assistantMemory.size(SystemClock.elapsedRealtime());
      memoryBadge.badge(String.valueOf(count));
      memoryBadge.setTooltipText("이번 대화에서 기억한 항목 " + count + "개 · 눌러 확인·삭제");
    }
    if (watchButton != null) {
      boolean watching = assistantMemory.watching(SystemClock.elapsedRealtime());
      watchButton.update("bell", "먼저 알려주기", watching, false);
      watchButton.badge(!watching ? "" : assistantMemory.fulfilled() ? "✓" : "•");
      watchButton.setTooltipText(!watching ? "먼저 알려주기 꺼짐 · 눌러 부탁하기" : !listening || !cameraEnabled
          ? "부탁 기억 중 · 카메라와 라이브를 켜면 살펴봐요" : assistantMemory.fulfilled()
          ? "부탁 알림 완료 · 새 조건은 눌러 설정" : "화면을 살피는 중 · 약 15초마다 확인");
    }
    for (Map.Entry<Long, TextView> age : memoryAgeViews.entrySet())
      age.getValue()
          .setText(Math.max(0, (SystemClock.elapsedRealtime() - age.getKey()) / 1000) + "초 전");
  }

  private void showError(String text) {
    runOnUiThread(
        () -> {
          if (alive) {
            needsAttention = true;
            lastStatus = text;
            refreshIndicators();
            if (foreground)
              Toast.makeText(this, "확인이 필요해요 · 느낌표를 눌러 보세요", Toast.LENGTH_SHORT).show();
          }
        });
  }

  private void show(String text) {
    runOnUiThread(
        () -> {
          if (alive) {
            lastStatus = text;
            needsAttention = false;
            refreshIndicators();
          }
        });
  }

  private void showFor(long id, String text) {
    runOnUiThread(
        () -> {
          if (alive && epoch.get() == id) show(text);
        });
  }

  private synchronized long invalidate() { return invalidate(false); }

  private synchronized long invalidate(boolean keepRecognition) {
    visualMemoryEpoch = -1; visualMemoryFrame = null;
    if(!keepRecognition && streamingAsr!=null)streamingAsr.cancel();
    long previous=epoch.get();
    working.state.interrupt(); activeFollowup=null; followupEpoch=-1;
    draft.clear(); streamSpeechEpoch=-1;
    uiHandler.post(()->{if(sentenceVoice!=null && sentenceVoice.owns(previous))sentenceVoice.cancel();});
    long next = epoch.incrementAndGet();
    android.util.Log.i("LiveVoice", "invalidate epoch=" + next + " reason=" + new Throwable().getStackTrace()[1].getMethodName());
    pendingText.set(null);
    uiHandler.post(() -> {
      if (!alive) return;
      for (ChatTurn turn : chatTurns) {
        if (turn.id < next && !turn.complete) { turn.stopped = true; updateChatTurn(turn); }
      }
    });
    thinkingEpoch = -1;
    speakingEpoch = -1;
    localSpeechEpoch = -1;
    if (localSpeech != null) localSpeech.cancel();
    nativeCancel(handle, next);
    playback.clear();
    synchronized (audioGuard) {
      playbackFramesWritten = 0;
      meterEnergy = 0; meterSamples = 0;
      voiceLevels.clear();
      AudioTrack current = track;
      if (current != null)
        try {
          current.pause();
          current.flush();
        } catch (IllegalStateException ignored) {
        }
    }
    return next;
  }

  private void startCapture() {
    if (!foreground || !loaded || modelBusy) {
      showError("먼저 모델을 준비하세요.");
      return;
    }
    if (listening) return;
    ArrayList<String> missing = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.RECORD_AUDIO);
    if (cameraEnabled && sampleVideoUri == null
        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.CAMERA);
    if (!missing.isEmpty()) {
      requestPermissions(missing.toArray(new String[0]), 11);
      return;
    }
    if (cameraEnabled && (preview == null || !preview.isAvailable())) {
      show("카메라 화면을 준비 중입니다. 잠시 후 다시 시작하세요.");
      return;
    }
    if (!acquireAudioFocus()) return;
    final long captureId = captureEpoch.incrementAndGet();
    final AtomicLong speechId = new AtomicLong(epoch.get());
    final AtomicLong asrTurn = new AtomicLong();
    capture =
        new Capture(
            new Capture.Listener() {
              private DiagnosticSpeechWindow diagnosticWindow;
              public void samples(short[] pcm,int count) {
                if(!diagnosticCapture || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0){diagnosticWindow=null;return;}
                if(diagnosticWindow==null)diagnosticWindow=new DiagnosticSpeechWindow();
                byte[] raw=diagnosticWindow.samples(pcm,count);
                if(raw!=null)diagnosticRawPcm=raw;
              }
              public void frame(byte[] data, long at) {
                synchronized (memory) {
                  if (listening && captureEpoch.get() == captureId) memory.observe(data, at);
                }
                if (cooking.active() && listening && captureEpoch.get() == captureId) {
                  android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                  if (bitmap != null) {
                    android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 12, true);
                    byte[] signature = new byte[192];
                    for (int y=0; y<12; y++) for (int x=0; x<16; x++) {
                      int pixel=tiny.getPixel(x,y);
                      signature[y*16+x]=(byte)((android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel))/3);
                    }
                    tiny.recycle(); if (tiny != bitmap) bitmap.recycle();
                    if (listening && captureEpoch.get() == captureId) cookingScenes.frame(signature, at);
                  }
                }
              }

              public void speechStart() {
                if(diagnosticWindow!=null)diagnosticWindow.start();
                if (listening && captureEpoch.get() == captureId) {
                  userSpeaking = true;
                  working.state.userStarted();
                  long previous = epoch.get();
                  long interruptAt = SystemClock.elapsedRealtime();
                  speechId.set(invalidate());
                  lastBargeInStopMs = SystemClock.elapsedRealtime() - interruptAt;
                  asrTurn.set(streamingAsr.begin(working.recognitionContext()));
                  android.util.Log.i("LiveVoice", "interrupt old=" + previous + " new=" + speechId.get());
                  microphoneTurnEpoch = speechId.get();
                  final long microphoneId = speechId.get();
                  runOnUiThread(() -> {
                    if (epoch.get() == microphoneId && captureEpoch.get() == captureId) {
                      microphoneTranscript = capture!=null && capture.mockInput.enabled()?"모의 사용자: "+mockText:""; microphoneState = "듣는 중"; refreshIndicators();
                    }
                  });
                  showFor(speechId.get(), "듣고 있어요. 말을 마치면 답변합니다.");
                }
              }

              public void partial(byte[] pcm,long at) {
                if(diagnosticCapture)return;
                if(listening && captureEpoch.get()==captureId)streamingAsr.accept(asrTurn.get(),pcm,false,at);
              }
              public void utterance(byte[] pcm, long at) {
                if (listening && captureEpoch.get() == captureId) {
                  if(diagnosticCapture && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){
                    if(diagnosticPcm==null)diagnosticPcm=pcm;
                    userSpeaking=false;return;
                  }
                  userSpeaking = false;
                  // Freeze evidence before ASR: future frames cannot replace the utterance's view.
                  alignedEvidence = cameraEnabled ? memory.alignedQuestion(at) : Collections.emptyList();
                  alignedAnchor = at; alignedTurn = asrTurn.get();
                  streamingAsr.accept(asrTurn.get(),pcm,true,at);
                }
              }
              public void inputReady(boolean ready) {
                runOnUiThread(() -> {
                  if (!listening || captureEpoch.get() != captureId) return;
                  if (!ready) {
                    if(asrTurn.getAndSet(0)!=0)invalidate();
                    userSpeaking = false; microphoneLevel = 0; microphoneState = "마이크 연결 중";
                  }
                  else if (microphoneState.equals("마이크 연결 중")) microphoneState = "";
                  refreshIndicators();
                });
              }
              public void level(float rms) {
                if (listening && captureEpoch.get() == captureId)
                  microphoneLevel = Math.max(Math.min(1f, rms * 16f), microphoneLevel * 0.85f);
              }

              public void error(String message) {
                runOnUiThread(
                    () -> {
                      if (captureEpoch.get() == captureId) {
                        stopCapture();
                        showError(message);
                      }
                    });
              }
            });
    listening = true;
    try {
      capture.start(this, preview, cameraEnabled && sampleVideoUri == null);
      if (cameraEnabled && sampleVideoUri != null) {
        sampleVideo = new SampleVideoInput(this, sampleVideoUri, preview, new SampleVideoInput.Output() {
          public void frame(byte[] jpeg, long at, long sourceMs) {
            synchronized (memory) {
              if (listening && cameraEnabled && captureEpoch.get() == captureId)
                memory.observe(jpeg, at, "sample-preview-copy", sourceMs);
            }
          }
          public void ended() { runOnUiThread(() -> {
            if (captureEpoch.get() != captureId) return;
            stopSampleVideo();
            show("샘플 영상이 끝났습니다. 음성 입력은 계속됩니다. 영상 버튼을 길게 눌러 다시 재생하세요.");
          }); }
          public void error(String message) { runOnUiThread(() -> {
            if (captureEpoch.get() != captureId) return;
            stopSampleVideo(); showError(message);
          }); }
        });
      }
      if (sessionStarted == 0) sessionStarted = SystemClock.elapsedRealtime();
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      show("듣고 있습니다. 말하는 동안에도 카메라와 마이크는 계속 켜져 있습니다.");
    } catch (Exception e) {
      stopCapture();
      showError("시작 실패: " + e.getMessage());
    }
  }

  private boolean acquireAudioFocus() {
    if (!foreground) return false;
    if (audioFocus != null) return true;
    AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
    audioFocus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change < 0) runOnUiThread(() -> stopCapture());
                })
            .build();
    if (manager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      audioFocus = null;
      showError("오디오를 사용할 수 없습니다. 다른 통화를 종료한 뒤 다시 시작하세요.");
      return false;
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != 11) return;
    if (cameraEnabled && sampleVideoUri == null
        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      cameraEnabled = false;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) {
      uiHandler.post(
          () -> {
            if (page.equals("live")) {
              render();
              uiHandler.postDelayed(() -> startCapture(), 300);
            }
          });
    } else {
      showError("마이크 권한이 꺼져 있어요. 텍스트로 질문하거나 설정에서 권한을 허용하세요.");
    }
  }

  private void stopSampleVideo() {
    if (sampleVideo != null) { sampleVideo.close(); sampleVideo = null; }
    synchronized (memory) {
      cameraEnabled = false; memory.cameraOff();
      alignedTurn = -1; alignedEvidence = Collections.emptyList();
    }
    invalidate(true); refreshIndicators();
  }

  private void stopCapture() {
    if (sampleVideo != null) { sampleVideo.close(); sampleVideo = null; }
    alignedTurn = -1; alignedEvidence = Collections.emptyList();
    cancelMockInput();
    cookingScenes.clear();
    synchronized (memory) {
      listening = false;
      userSpeaking = false;
      captureEpoch.incrementAndGet();
      memory.cameraOff();
    }
    invalidate();
    microphoneLevel = 0; microphoneState = "";
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    Capture old = capture;
    capture = null;
    if (old != null) old.close();
    AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
    if (audioFocus != null) {
      manager.abandonAudioFocusRequest(audioFocus);
      audioFocus = null;
    }
    show("대화를 중지했습니다. 과거 관측은 기억 지우기로 삭제할 수 있습니다.");
  }

  private void acceptRecognition(long turn,String raw,boolean last,long at) {
    if(!alive||!loaded||!listening||streamingAsr.current()!=turn||finalizedAsrTurn==turn)return;
    String text=raw==null?"":raw.split("<\\|",2)[0].trim();
    if(!text.codePoints().anyMatch(Character::isLetterOrDigit))text="";
    lastAsrMs=SystemClock.elapsedRealtime()-at;
    android.util.Log.i("LiveStream","asr turn="+turn+" final="+last+" chars="+text.length()+" delay_ms="+lastAsrMs);
    microphoneTurnEpoch=epoch.get();showMicrophoneTranscript(epoch.get(),text);
    if(!cooking.active() && SpeechIntent.stopOnly(text)) {
      if(draft.hidden(epoch.get())||last){
        long id=invalidate(true);microphoneTurnEpoch=id;showMicrophoneTranscript(id,text);
      }
      if(last){finalizedAsrTurn=turn;working.state.input(text,SystemClock.elapsedRealtime());working.state.hold();microphoneState="듣는 중";refreshIndicators();}
      return;
    }
    // Partial recognition may update the caption, never memory, tools or a reusable answer.
    // A final transcript changes the decision revision; inference starts with that snapshot.
    if(!last){asrPartialUpdates++;return;}
    finalizedAsrTurn=turn;
    if(text.isEmpty()){invalidate(true);working.state.userEnded(SystemClock.elapsedRealtime());microphoneState="잘 듣지 못했어요";refreshIndicators();return;}
    long id=invalidate(true);microphoneTurnEpoch=id;
    submit(null,text,id,at);requestStarted=at;showMicrophoneTranscript(id,text);
  }

  private void submit(byte[] pcm, String text, long id, long at) {
    lastResponsePath="model";
    // The explicit away mode preserves the task while excluding incidental
    // dialogue from actions and answers. It does not identify speakers.
    if(cooking.active() && cooking.away() && !CookingSession.resumeQuestion(text)) {
      working.state.userEnded(SystemClock.elapsedRealtime());thinkingEpoch=-1;return;
    }
    if(cooking.active() && CookingSession.resumeQuestion(text))working.state.resume(SystemClock.elapsedRealtime());
    String direct;
    String previousGoal=working.state.snapshot().goal;
    long inputAt=SystemClock.elapsedRealtime();
    try {direct=working.input(text,inputAt);
      if(!previousGoal.equals(working.state.snapshot().goal))conversationScopeStart=inputAt;
    }
    catch(IllegalArgumentException | IllegalStateException e) {
      working.state.userEnded(SystemClock.elapsedRealtime());
      showError("요청을 반영하지 못했어요. 진행 중인 작업을 확인하고 짧게 다시 말씀해 주세요.");return;
    }
    responseMemoryEpoch=id;responseMemoryLease=working.state.lease();
    if(direct!=null){lastResponsePath="tool-adapter";resetOutput(id);localSpeechEpoch=id;onModelText(id,text,direct);return;}
    if (cooking.active()) { submitCooking(pcm, text, id, at); return; }
    final List<ObservationMemory.Evidence> evidence = !cameraEnabled ? Collections.emptyList()
        : (alignedTurn == finalizedAsrTurn && at == alignedAnchor)
            ? alignedEvidence : memory.alignedQuestion(at);
    lastEvidenceAt = evidence.isEmpty() ? -1 : evidence.get(0).frame.at;
    lastEvidenceSourceMs = evidence.isEmpty() ? -1 : evidence.get(0).frame.sourceMs;
    lastEvidenceSource = evidence.isEmpty() ? "unavailable" : evidence.get(0).frame.source;
    if(evidence.isEmpty()) {
      String recall=assistantMemory.unavailableVisualReply(text,inputAt);
      if(recall!=null){
        lastResponsePath="visual-memory-policy";resetOutput(id);localSpeechEpoch=id;
        if(speechEnabled && localSpeech.ready()) {
          streamSpeechEpoch=id;
          runOnUiThread(()->{if(epoch.get()==id)sentenceVoice.start(id);});
        }
        onModelText(id,text,recall);return;
      }
    }
    final String remembered = JSONObject.quote(working.state.context()+"\n"+assistantMemory.visualContext(at)+"\n"+recentContext(at));
    final boolean speak = speechEnabled;
    resetOutput(id);
    runOnUiThread(() -> {
      if (!alive || epoch.get() != id) return;
      ChatTurn turn = chatTurn(id);
      turn.question = text;
      updateChatTurn(turn);
      scrollChat(true);
    });
    showFor(id, "기억과 관측을 확인하고 답변을 준비합니다…");
    runInference(pcm, text, id, at, evidence, remembered, speak);
  }

  private void resetOutput(long id) {
    thinkingEpoch = id;
    speakingEpoch = -1;
    requestStarted = SystemClock.elapsedRealtime();
    firstAudioMs = -1;
    firstPlaybackMs = -1;
    audioDoneEpoch = -1;
    audioProducerWaits = 0;
    audioStarvations = 0;
    audioWrittenSamples = 0;
    audioQueuedSamples = 0;
    maxVoiceLevel = 0;
    audioFullyBuffered = false;
    synchronized (audioChunks) { audioChunks.clear(); }
    firstTextMs = -1;
    textUpdates = 0;
  }

  private void runInference(byte[] pcm, String text, long id, long at,
      List<ObservationMemory.Evidence> evidence, String remembered, boolean speak) {
    visualMemoryEpoch = id;
    visualMemoryFrame = evidence.isEmpty() ? null : evidence.get(0).frame;
    final WorkingMemory.Lease requestLease=working.state.lease();
    responseMemoryEpoch=id;responseMemoryLease=requestLease;
    final boolean localVoice=speak && localSpeech.ready();
    if(localVoice){localSpeechEpoch=id;streamSpeechEpoch=id;
      runOnUiThread(()->{if(epoch.get()==id)sentenceVoice.start(id);});
    }
    inference.execute(
        () -> {
          if (!alive || !loaded || epoch.get() != id) return;
          File folder = new File(sessionCache, "request-" + id);
          folder.mkdirs();
          try {
            StringBuilder observation = new StringBuilder();
            boolean current = false;
            int index = 0;
            for (ObservationMemory.Evidence item : evidence) {
              File image = new File(folder, "frame-" + (index++) + ".jpg");
              try (FileOutputStream out = new FileOutputStream(image)) {
                out.write(item.frame.jpeg);
              }
              current |= item.current;
              observation
                  .append(item.current ? "질문 발화 시점 화면 (그 이후 변화는 확인하지 않음): " : "과거 관측, 현재 화면 아님: ")
                  .append((at - item.frame.at) / 1000.0)
                  .append("초 전.<img>")
                  .append(image.getAbsolutePath())
                  .append("</img>");
            }
            if (current && AssistantMemory.rememberVisualRequest(text))
              observation.append("기억 요청에 대해 첨부 이미지에 실제로 보이는 대상과 위치를 구체적으로 설명하세요. 기억하겠다는 말만 하지 마세요.");
            if (!current && (text.matches("(?s).*(보이|보여|화면|영상|이거|저거|여기|어디|거기|아까|지금도).*")))
              observation.append("현재 화면은 제공되지 않았습니다. 저장된 과거 관측이 있으면 그때의 위치는 답할 수 있으나 현재 상태는 확인할 수 없습니다. 과거 관측도 없으면 기억이 없다고 답하세요.");
            if (text.matches("(?s).*(아까|이전|그때|지금도|거기).*"))
              observation.append("질문의 과거와 현재를 각각 답하세요. 먼저 저장된 과거 관측의 대상과 당시 위치를 구체적으로 한 문장으로 답하세요. 이어서 첨부된 현재 이미지에 그 대상이 보이는지와 현재 모습을 답하세요. 현재 사진에 없는 과거 대상을 지금도 있다고 단정하지 마세요.");
            File wav = new File(folder, "question.wav");
            if (pcm != null) writeWav(wav, pcm);
            showFor(id, "관측을 확인하고 있습니다…");
            android.util.Log.i("LiveInference", "begin epoch=" + id + " images=" + evidence.size());
            if(!working.state.valid(requestLease))return;
            nativeInfer(handle, wav.getAbsolutePath(), observation.toString(), text, remembered, id, speak && !localVoice && !draft.hidden(id));
            android.util.Log.i("LiveInference", "end epoch=" + id + " current=" + (epoch.get() == id));
            runOnUiThread(
                () -> {
                  if (epoch.get() == id && !draft.hidden(id)) thinkingEpoch = -1;
                });
            if(!draft.hidden(id))showFor(id, speak ? "답변을 전달하고 있습니다." : "답변이 준비됐어요. 이어서 질문해 주세요.");
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (alive && epoch.get() == id) {
                    invalidate();
                    if (microphoneTurnEpoch == id) microphoneState = "인식·응답 실패";
                    showError("응답 실패: " + e.getMessage() + " 다시 질문해 주세요.");
                  }
                });
          } finally {
            if (epoch.get() == id && !localVoice) audioDoneEpoch = id;
            remove(folder);
          }
        });
  }

  // Native sends complete UTF-8 snapshots as tokens arrive. Keep only the newest
  // snapshot and at most one scheduled UI drain, even when generation is fast.
  public void onModelTextDelta(long id, String transcript, String answer) {
    if (!alive || epoch.get() != id || !memoryResponseValid(id) || !draft.offer(id,answer,false)) return;
    if (!answer.isEmpty()) {
      if (firstTextMs < 0) android.util.Log.i("LiveInference", "first_text epoch=" + id);
      if (firstTextMs < 0) firstTextMs = SystemClock.elapsedRealtime() - requestStarted;
      textUpdates++;
    }
    pendingText.set(new TextUpdate(id, transcript, answer));
    scheduleTextDrain();
  }

  private void scheduleTextDrain() {
    if (textDrainPosted.compareAndSet(false, true)) uiHandler.postDelayed(this::drainText, 32);
  }

  private void drainText() {
    TextUpdate update = pendingText.getAndSet(null);
    textDrainPosted.set(false);
    if (update != null && alive && epoch.get() == update.id) {
      showMicrophoneTranscript(update.id, update.question);
      ChatTurn turn = chatTurn(update.id);
      if (!turn.complete && !turn.stopped) {
        boolean follow = chatScroll == null || !chatScroll.canScrollVertically(1);
        turn.question = update.question;
        turn.answer = update.answer;
        if(streamSpeechEpoch==update.id && speechEnabled)sentenceVoice.append(update.id,update.answer,false);
        response = update.question.trim() + "\n\n" + update.answer.trim();
        replyUnread = chatMessages == null;
        updateChatTurn(turn);
        scrollChat(follow);
        refreshIndicators();
      }
    }
    if (pendingText.get() != null) scheduleTextDrain();
  }

  private void showMicrophoneTranscript(long id, String transcript) {
    runOnUiThread(() -> {
      if (!alive || epoch.get() != id || microphoneTurnEpoch != id) return;
      microphoneTranscript = transcript.trim();
      microphoneState = microphoneTranscript.isEmpty() ? "잘 듣지 못했어요" : "";
      refreshIndicators();
    });
  }

  public void onModelText(long id, String transcript, String answer) {
    if(!alive||epoch.get()!=id||!memoryResponseValid(id)||!draft.offer(id,answer,true))return;
    runOnUiThread(
        () -> {
          if (!alive || epoch.get() != id || !memoryResponseValid(id)) return;
          if(firstTextMs<0 && !answer.isEmpty())firstTextMs=SystemClock.elapsedRealtime()-requestStarted;
          thinkingEpoch=-1;
          pendingText.set(null);
          showMicrophoneTranscript(id, transcript);
          ChatTurn turn = chatTurn(id);
          turn.question = transcript;
          turn.answer = answer;
          if(visualMemoryEpoch==id && visualMemoryFrame!=null) {
            ObservationMemory.Frame viewed=visualMemoryFrame;
            assistantMemory.rememberVisual(transcript,answer,viewed.at,SystemClock.elapsedRealtime(),viewed.source,viewed.sourceMs);
          }
          if (!transcript.equals("요리 중 확인") && !transcript.equals("타이머") && !transcript.equals("작업 결과")) {
            if(!speechEnabled)assistantMemory.turn(transcript, answer, SystemClock.elapsedRealtime());
            else {pendingHeardEpoch=id;pendingHeardQuestion=transcript;pendingHeardAnswer=answer;}
          }
          turn.complete = true;
          turn.stopped = false;
          response = transcript.trim() + "\n\n" + answer.trim();
          replyUnread = chatMessages == null;
          completedTurns++;
          boolean follow = chatScroll == null || !chatScroll.canScrollVertically(1);
          updateChatTurn(turn);
          scrollChat(follow);
          show(speechEnabled ? "답변을 음성으로 준비하고 있어요…" : "답변이 준비됐어요.");
          if(speechEnabled && streamSpeechEpoch==id)sentenceVoice.append(id,answer,true);
          else if (speechEnabled && (cooking.active() || localSpeechEpoch==id)) speakCooking(id, answer);
        });
  }

  // Backpressure belongs only to the model worker. Microphone capture and
  // invalidate never acquire this producer's lock or wait for queue capacity.
  public boolean onPcm(long id, float[] pcm) {
    if (!alive || epoch.get() != id || !memoryResponseValid(id) || pcm.length == 0 || pcm.length > 24000 * 3) return false;
    if (firstAudioMs < 0) {
      speakingEpoch = id;
      firstAudioMs = SystemClock.elapsedRealtime() - requestStarted;
      showFor(id, "음성을 준비하고 있습니다…");
    }
    try {
      while (alive && epoch.get() == id) {
        if (playback.offer(new Audio(id, pcm), 25, TimeUnit.MILLISECONDS)) {
          if (epoch.get() != id) return false;
          audioQueuedSamples+=pcm.length;
          synchronized (audioChunks) {
            if (audioChunks.size() < 64) audioChunks.add(new long[] {
                SystemClock.elapsedRealtime() - requestStarted, pcm.length});
          }
          return true;
        }
        audioProducerWaits++;
      }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    return false;
  }

  private float currentVoiceLevel() {
    synchronized (audioGuard) {
      if (!isVoicePlaying()) return 0;
      long position = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
      VoiceLevel frame = voiceLevels.peek();
      while (frame != null && frame.end <= position) { voiceLevels.poll(); frame = voiceLevels.peek(); }
      if (frame != null && frame.start <= position) return frame.value;
      if (meterSamples > 0 && position >= playbackFramesWritten - meterSamples)
        return Math.min(1f, (float)Math.sqrt(meterEnergy / meterSamples) * 5f);
      return 0;
    }
  }

  private boolean isVoicePlaying() {
    synchronized (audioGuard) {
      if (speakingEpoch != epoch.get() || track == null
          || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return false;
      long position = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
      return position > 0 && position < playbackFramesWritten;
    }
  }

  private void play() {
    int size = Math.max(24000 * 4,
        AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT));
    AudioTrack output = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
    track = output;
    // Use a short preroll for fast generation. If the first two chunks arrive
    // slower than playback, collect the bounded utterance before speaking.
    // Text is already streaming while slow CPU speech synthesis finishes.
    ArrayDeque<Audio> ready = new ArrayDeque<>();
    final int preroll = 24000 * 12 / 10;
    final int maxBuffered = 24000 * 24; // native TTS cap is 256 frames / 20.48s
    int buffered = 0;
    long active = -1;
    boolean primed = false, collectAll = false;
    try {
      while (alive) {
        long id = epoch.get();
        if (active != id) {
          ready.clear(); buffered = 0; primed = false; collectAll = false; active = id;
        }
        if (!primed) {
          Audio next = playback.poll(25, TimeUnit.MILLISECONDS);
          // Preserve the first PCM when a new epoch arrives during this poll.
          if (epoch.get() != id) {
            if (next == null || next.epoch != epoch.get()) continue;
            id = next.epoch; active = id; ready.clear(); buffered = 0;
            primed = false; collectAll = false;
          }
          if (next != null && next.epoch == id) {
            if (ready.size() == 1) {
              Audio first = ready.peekFirst();
              if (streamSpeechEpoch!=id && next.arrivedAt - first.arrivedAt > first.pcm.length * 900L / 24000) {
                collectAll = true;
                audioFullyBuffered = true;
              }
            }
            if (buffered + next.pcm.length > maxBuffered)
              throw new IllegalStateException("음성 응답 길이 제한을 넘었습니다. 더 짧게 질문해 주세요.");
            ready.add(next); buffered += next.pcm.length;
          }
          if (ready.isEmpty()) continue;
          boolean sentenceMode=streamSpeechEpoch==id;
          if (audioDoneEpoch != id && ((!sentenceMode&&collectAll) || buffered < (sentenceMode?4800:preroll) || ready.size() < (sentenceMode?1:2))) continue;
          primed = true;
        }
        Audio audio = ready.pollFirst();
        if (audio != null) buffered -= audio.pcm.length;
        else audio = playback.poll(25, TimeUnit.MILLISECONDS);
        if (audio == null) {
          boolean drained = false;
          if (firstPlaybackMs >= 0) {
            synchronized (audioGuard) {
              if (epoch.get() == id && output.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                  && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) >= playbackFramesWritten) {
                output.pause(); drained = true;
              }
            }
            if (drained && audioDoneEpoch != id) {
              audioStarvations++; primed = false; collectAll = streamSpeechEpoch!=id; audioFullyBuffered = collectAll;
            }
          }
          continue;
        }
        if (audio.epoch != epoch.get()) continue;
        int written = 0;
        while (alive && audio.epoch == epoch.get() && written < audio.pcm.length) {
          int count;
          boolean first = false;
          synchronized (audioGuard) {
            if (audio.epoch != epoch.get()) break;
            count = output.write(audio.pcm, written,
                Math.min(2400, audio.pcm.length - written), AudioTrack.WRITE_NON_BLOCKING);
            if (count > 0) {
              long now = SystemClock.elapsedRealtime();
              double energy = 0;
              for (int i = written; i < written + count; i++) energy += audio.pcm[i] * audio.pcm[i];
              float level = Math.min(1f, (float) Math.sqrt(energy / count) * 5f);
              audioWrittenSamples += count;
              maxVoiceLevel = Math.max(maxVoiceLevel, level);
              // AudioTrack can accept tiny partial writes. Fixed 20 ms bins
              // keep the bounded meter ahead of playback without evicting the
              // currently audible level merely because writes were fragmented.
              for (int i = written; i < written + count; i++) {
                meterEnergy += audio.pcm[i] * audio.pcm[i];
                meterSamples++; playbackFramesWritten++;
                if (meterSamples == 480) {
                  VoiceLevel meter = new VoiceLevel(playbackFramesWritten - 480,
                      playbackFramesWritten, Math.min(1f, (float)Math.sqrt(meterEnergy / 480) * 5f));
                  if (!voiceLevels.offer(meter)) { voiceLevels.poll(); voiceLevels.offer(meter); }
                  meterSamples = 0; meterEnergy = 0;
                }
              }
              if (firstPlaybackMs < 0) { firstPlaybackMs = now - requestStarted; first = true; }
              if (output.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) output.play();
            }
          }
          if (first) showFor(audio.epoch, "음성 출력 중 · 말하거나 중단 버튼으로 끼어들 수 있어요.");
          View assistant = cameraPlaceholder;
          if (count > 0 && assistant != null) assistant.postInvalidateDelayed(33);
          if (count < 0) throw new IllegalStateException("오디오 재생 실패");
          written += count;
          if (count == 0) Thread.sleep(5);
        }
      }
    } catch (Exception e) {
      invalidate();
      showError(e.getMessage());
    } finally {
      synchronized (audioGuard) { track = null; output.release(); }
    }
  }

  private static void writeWav(File path, byte[] pcm) throws IOException {
    ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    h.put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(36 + pcm.length)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(pcm.length);
    try (FileOutputStream out = new FileOutputStream(path)) {
      out.write(h.array());
      out.write(pcm);
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 12 && result == RESULT_OK && data != null && data.getData() != null) {
      sampleVideoUri = data.getData(); cameraEnabled = true;
      uiHandler.postDelayed(this::startCapture, 300); return;
    }
    if (request == 10 && result == RESULT_OK && data != null) {
      Uri uri = data.getData();
      if (uri == null || modelBusy) return;
      invalidate();
      loaded = false;
      modelBusy = true;
      show("모델을 가져오고 검증합니다. 잠시 기다려 주세요…");
      inference.execute(
          () -> {
            try {
              File root =
                  ModelBundle.install(getContentResolver().openInputStream(uri), getFilesDir());
              load(root);
            } catch (Exception e) {
              modelBusy = false;
              showError("모델 가져오기 실패: " + e.getMessage() + " 설정에서 다시 시도하세요.");
            }
          });
    }
  }

  private void load(File root) {
    try {
      show("로컬 모델을 확인하고 준비합니다.");
      ModelBundle.verify(root);
      gpuActive = nativeLoad(handle, root.getAbsolutePath(),
          Build.VERSION.SDK_INT >= 31 && "MT6989".equals(Build.SOC_MODEL));
      fastObserver=new File(root,"observer/config.json").isFile();
      if(fastObserver){
        // Compile the observer's fixed image shape before reporting readiness.
        File warm=new File(getCacheDir(),"observer-warmup.jpg");
        android.graphics.Bitmap blank=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888);
        blank.eraseColor(android.graphics.Color.GRAY);
        try(FileOutputStream out=new FileOutputStream(warm)){blank.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);}
        finally{blank.recycle();}
        try{nativeVisualCheck(handle,"Is a cup or drinking glass clearly visible?",warm.getAbsolutePath(),epoch.get());}
        finally{warm.delete();}
      }
      loaded = true;
      modelBusy = false;
      show("오프라인 모델이 준비되었습니다.");
    } catch (Exception e) {
      loaded = false;
      modelBusy = false;
      showError("모델 준비 실패: " + e.getMessage());
    }
  }

  static void remove(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) remove(child);
    }
    file.delete();
  }

  private void endSession() {
    stopCapture();
    memory.reset();
    clearAssistantMemory();
    clearChat();
    sessionStarted = 0;
    completedTurns = 0;
    firstAudioMs = -1;
    if (question != null) question.setText("");
  }

  private void clearAssistantMemory() {
    visualMemoryEpoch = -1; visualMemoryFrame = null;
    alignedTurn = -1; alignedEvidence = Collections.emptyList();
    lastDeliveredEpoch = -1;
    cancelMockInput();
    mockText = "안녕. 오늘 할 일을 짧게 정리해 줘.";
    assistantMemory.clear();working.reset();activeFollowup=null;followupEpoch=-1;
    pendingHeardEpoch=-1;pendingHeardQuestion=pendingHeardAnswer="";
    cooking.clear(); cookingScenes.clear(); cookingTimerPending=false;
    cookingGuidanceEpoch=-1;cookingGuidanceRevision=-1;
    lastTaskFrame=null;lastTaskDiagnostic="";taskAttempts=0;
    lastObservation = lastProactiveError = "";
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (theme != null && page.equals("memory")) render();
  }

  @Override
  protected void onPause() {
    foreground = false;
    if (activeSheet != null) activeSheet.dismiss();
    endSession();
    show("앱을 벗어나 센서·음성을 멈추고 대화와 기억을 지웠습니다.");
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    if (page.equals("live") || page.equals("welcome")) {
      endSession();
      super.onBackPressed();
      return;
    }
    navigate("live");
  }

  @Override
  protected void onDestroy() {
    alive = false;
    if (localSpeech != null) localSpeech.close();
    if (mockVoice != null) mockVoice.close();
    uiHandler.removeCallbacksAndMessages(null);
    stopCapture();
    memory.reset();
    clearAssistantMemory();
    if(streamingAsr!=null)streamingAsr.close();
    inference.getQueue().clear();
    inference.execute(
        () -> {
          try{if(streamingAsr!=null)streamingAsr.awaitClosed();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
          nativeRelease(handle);
          remove(sessionCache);
        });
    inference.shutdown();
    super.onDestroy();
  }
}
