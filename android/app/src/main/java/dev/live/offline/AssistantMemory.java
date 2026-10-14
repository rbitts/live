package dev.live.offline;

import java.util.ArrayDeque;

/** Explicit, bounded session memory. Observations never become current-view facts. */
final class AssistantMemory {
  private static final class Entry {
    final long at;
    final String text;
    Entry(long at, String text) { this.at = at; this.text = text; }
  }
  private final ArrayDeque<Entry> turns = new ArrayDeque<>(), scenes = new ArrayDeque<>();
  private static final class VisualNote {
    final long at, sourceMs;
    final String summary, source;
    VisualNote(long at,String summary,String source,long sourceMs) {
      this.at=at;this.summary=summary;this.source=source;this.sourceMs=sourceMs;
    }
  }
  private final ArrayDeque<VisualNote> visualNotes = new ArrayDeque<>();
  private int visualCapacity=2;
  synchronized void visualCapacity(int count) {
    if(count<0||count>2)throw new IllegalArgumentException("visual memory capacity");
    visualCapacity=count;
    while(visualNotes.size()>count)visualNotes.removeFirst();
  }
  private boolean enabled = true, watching, fulfilled;
  private String task = "", lastScene = "", lastAlert = "";
  private long retention = 300000, taskAt, sceneAt, lastCheck = -1, lastNotice = -1, acceptedAt = -1;
  private int matches;

  synchronized void configure(boolean enabled, long retention) {
    this.enabled = enabled; this.retention = retention;
    if (!enabled) clear();
  }
  synchronized void watch(String task, long now) {
    if (!enabled) throw new IllegalStateException("기억을 켠 뒤 사용하세요.");
    if (task.length() > 160) throw new IllegalArgumentException("부탁은 160자 이내로 입력하세요.");
    this.task = task.trim(); taskAt = now; watching = true; fulfilled = false;
    matches = 0; lastCheck = -1; lastNotice = -1; lastAlert = "";
  }
  synchronized void stopWatching() { watching = false; matches = 0; }
  synchronized boolean watching(long now) { expire(now); return watching; }
  synchronized String task(long now) { expire(now); return task; }
  synchronized boolean fulfilled() { return fulfilled; }
  synchronized String scene(long now) { expire(now); return lastScene; }
  synchronized int size(long now) { expire(now); return turns.size() + scenes.size() + visualNotes.size() + (task.isEmpty() ? 0 : 1); }
  static boolean rememberVisualRequest(String text) {
    String key=text.replaceAll("\\s+", "");
    return key.matches("(?s).*(?:기억해줘|기억해둬|기억해둬요|기억해주세요|봐두고기억해|기억해줄래).*" );
  }
  /** Explicitly requested, completed image-grounded answer, never a live-view assertion.
   * Caller checks epoch/lease and associates the actual selected frame, not user text. */
  synchronized void rememberVisual(String request,String answer,long observedAt,long now,String source,long sourceMs) {
    if(!enabled || visualCapacity==0 || !rememberVisualRequest(request) || answer.isBlank()
        || answer.matches("(?s).*(?:이미지|화면|영상)(?:를|을|이|가)?\\s*(?:볼\\s*수\\s*없|없으|없어|제공되지).*")
        || observedAt<0 || now<observedAt || now-observedAt>retention)return;
    expire(now);
    visualNotes.removeIf(e -> e.at==observedAt);
    visualNotes.addLast(new VisualNote(observedAt,bounded(answer,320),bounded(source,40),sourceMs));
    while(visualNotes.size()>visualCapacity)visualNotes.removeFirst();
  }
  synchronized String visualContext(long now) {
    expire(now);
    StringBuilder out=new StringBuilder();
    for(VisualNote e:visualNotes)out.append("사용자가 기억을 요청한 과거 영상, ").append((now-e.at)/1000)
        .append("초 전 관측: 출처=").append(e.source).append(", 영상위치=").append(e.sourceMs)
        .append("ms. 당시 이미지에 대한 모델 설명(현재 화면 아님): ").append(e.summary).append('\n');
    return out.toString();
  }
  /** No current image: retrieve explicitly saved descriptions, without inventing a new view.
   * This deliberately covers visual recall/location references, not all conversation. */
  synchronized String unavailableVisualReply(String request,long now) {
    expire(now);
    String key=request.replaceAll("[\\s.!?,。！？]", "");
    boolean past=key.matches(".*(?:아까|이전|그때|전에).*"),
        visual=key.matches(".*(?:동물|화면|영상|이미지|장면|보였|봤던|보이던).*"),
        question=key.matches(".*(?:어디|어떤|무엇|뭐|모습|생김새|다시알려|기억나).*"),
        current=key.matches(".*(?:지금|현재|아직).*(?:거기|그곳|그위치|보여|보이).*" );
    if(past && visual && question) {
      if(visualNotes.isEmpty())return "이 대화에는 기억해 둔 영상 관측이 없어요. 당시 모습을 다시 보여 주면 확인할 수 있어요.";
      StringBuilder reply=new StringBuilder("기억해 둔 당시 화면 설명을 그대로 읽어 드릴게요. ");
      for(VisualNote e:visualNotes)reply.append('“').append(e.summary.trim()).append('”').append(' ');
      return reply.append("지금 모습이나 위치는 현재 영상이 없어 확인할 수 없어요.").toString();
    }
    if(current)return "현재 확인할 수 있는 영상이 없어서 지금 모습이나 위치는 알 수 없어요.";
    return null;
  }
  synchronized boolean due(long now) {
    expire(now);
    return enabled && watching && (lastCheck < 0 || now - lastCheck >= 15000);
  }
  synchronized void checked(long now) { lastCheck = now; }
  synchronized void turn(String user, String answer, long now) {
    if (!enabled) return;
    expire(now);
    turns.addLast(new Entry(now, "사용자: " + bounded(user, 300) + "\n비서: " + bounded(answer, 300)));
    while (turns.size() > 4) turns.removeFirst();
  }
  synchronized String context(long now) { return context(now,Long.MIN_VALUE); }
  synchronized String context(long now,long since) {
    expire(now);
    StringBuilder out = new StringBuilder();
    if (!task.isEmpty()) out.append("사용자가 명시적으로 맡긴 부탁: ").append(task)
        .append(fulfilled ? " (이미 알림 전달함)" : " (아직 알림 전)").append('\n');
    for (Entry e : scenes) out.append("과거 관측 ").append((now-e.at)/1000).append("초 전: ").append(e.text).append('\n');
    for (Entry e : turns) if(e.at>=since)out.append(e.text).append('\n');
    return out.toString();
  }
  // Called only after a current-epoch, fresh-frame, strict JSON result is validated.
  synchronized String observed(String scene, boolean match, boolean changed, String notice,
      long observedAt, long now) {
    expire(now);
    if (!watching || observedAt <= acceptedAt || now < observedAt || now - observedAt > 12000 || scene.isBlank()) return "";
    acceptedAt = observedAt;
    boolean baseline = lastScene.isEmpty();
    boolean distinct = !scene.equals(lastScene);
    if (distinct) {
      scenes.addLast(new Entry(observedAt, bounded(scene, 240)));
      while (scenes.size() > 4) scenes.removeFirst();
    } else if (!scenes.isEmpty()) {
      scenes.removeLast();
      scenes.addLast(new Entry(observedAt, bounded(scene, 240)));
    }
    lastScene = bounded(scene, 240); sceneAt = observedAt;
    // Two independent checks, not two tokens/chunks from the same observation.
    matches = !task.isEmpty() && !fulfilled && match ? matches + 1 : 0;
    boolean taskReady = matches >= 2;
    boolean changeReady = task.isEmpty() && !baseline && distinct && changed;
    if ((!taskReady && !changeReady) || notice.isBlank() || notice.equals(lastAlert)
        || (lastNotice >= 0 && now - lastNotice < 45000)) return "";
    if (taskReady) { fulfilled = true; matches = 0; }
    lastNotice = now; lastAlert = bounded(notice, 160);
    return lastAlert;
  }
  private void expire(long now) {
    turns.removeIf(e -> now < e.at || now - e.at > retention);
    scenes.removeIf(e -> now < e.at || now - e.at > retention);
    visualNotes.removeIf(e -> now < e.at || now - e.at > retention);
    if (!lastScene.isEmpty() && (now < sceneAt || now - sceneAt > retention)) { lastScene = ""; matches = 0; }
    if (watching && (now < taskAt || now - taskAt > retention)) {
      watching = false; task = ""; fulfilled = false; matches = 0;
    }
    if (!task.isEmpty() && (now < taskAt || now - taskAt > retention)) task = "";
  }
  synchronized void clear() {
    turns.clear(); scenes.clear(); visualNotes.clear(); task = lastScene = lastAlert = "";
    watching = fulfilled = false; matches = 0; lastCheck = lastNotice = acceptedAt = -1;
  }
  private static String bounded(String text, int max) {
    int end = Math.min(text.length(), max);
    if (end > 0 && Character.isHighSurrogate(text.charAt(end-1))) end--;
    return text.substring(0, end);
  }
}
