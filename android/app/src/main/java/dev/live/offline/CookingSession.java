package dev.live.offline;

import java.util.*;

/** Bounded session task state. Opt-in visual progress stays distinct from user confirmation. */
final class CookingSession {
  private boolean active, paused;
  private boolean ambient, away, sourceChanged;
  private String request = "";
  private final ArrayList<String> steps = new ArrayList<>();
  private final HashSet<Integer> observedSteps = new HashSet<>();
  private final ArrayDeque<String> observations = new ArrayDeque<>();
  private boolean seenWorking;
  private int completeChecks;
  private long lastFrameAt=-1, lastSourceMs=-1, firstCompleteAt=-1;
  private String lastFrameKey="", source="", pendingGuidance="";
  private String observationStatus="";
  private int step, revision;
  private long timerUntil = -1, lastNoticeAt = -1;
  private String lastNotice = "";
  synchronized void start(String request) {
    String value=bounded(request,400); clear(); active = true; this.request = value; revision++;
  }
  synchronized boolean active() { return active; }
  synchronized boolean ready() { return active && !steps.isEmpty(); }
  synchronized boolean paused() { return paused; }
  synchronized boolean away() { return away; }
  synchronized void ambient(boolean enabled) { ambient=enabled; if(!enabled)pendingGuidance=""; resetEvidence(); }
  synchronized boolean ambient() { return ambient; }
  synchronized int revision() { return revision; }
  synchronized int index() { return step; }
  synchronized int count() { return steps.size(); }
  synchronized String request() { return request; }
  synchronized String current() { return !ready() ? (request.isEmpty()?"만들 요리와 재료를 말씀해 주세요":"요리 순서를 준비하고 있어요") : step >= steps.size() ? "요리 단계 완료" : steps.get(step); }
  synchronized void plan(String request, List<String> proposed, int expectedRevision) {
    if (!active || expectedRevision != revision) return;
    if (proposed.size() < 1 || proposed.size() > 8) throw new IllegalArgumentException("단계 수 확인 필요");
    for (String s : proposed) if (s.isBlank() || s.length() > 140) throw new IllegalArgumentException("단계 길이 확인 필요");
    if(!CookingPlanPolicy.issue(request,proposed).isEmpty())throw new IllegalArgumentException("조리 방식 확인 필요");
    this.request = bounded(request, 400); steps.clear(); steps.addAll(proposed); step = 0; paused = false;
    timerUntil = -1; lastNoticeAt = -1; lastNotice = ""; revision++;
    observedSteps.clear(); observations.clear(); away=false; sourceChanged=false; pendingGuidance="";
    source="";lastFrameAt=lastSourceMs=-1;resetEvidence();
  }
  synchronized String command(String text) {
    if (!active) return null;
    String s = text.replaceAll("[\\s.!?,。]", "");
    if (Arrays.asList("잠깐다른사람이랑얘기하고올게", "잠깐옆사람이랑얘기하고올게", "잠깐통화하고올게").contains(s)) {
      away=true; revision++; return "다녀오세요. 작업 과정은 기억하고 안내는 기다릴게요.";
    }
    if (resumeQuestion(text)) {
      away=false; paused=false; revision++; return recall();
    }
    if (Arrays.asList("잠깐", "잠깐만", "일시정지", "기다려", "기다려줘").contains(s)) {
      paused = true; revision++; return "잠시 멈출게요. 진행 단계는 기억하고 있어요.";
    }
    if (Arrays.asList("계속", "계속해", "이어서", "다시시작", "재개").contains(s)) {
      paused = false; revision++; return current();
    }
    if (Arrays.asList("어디까지했지", "어디까지했어", "지금뭐해야해", "다음에뭐해", "다시말해줘").contains(s)) return recall();
    if (!ready()) return null;
    if (Arrays.asList("다했어", "했어", "완료", "다됐어", "다했어요", "다음", "다음단계").contains(s)) {
      if (paused) return "멈춘 상태예요. 계속하려면 재개해 주세요.";
      if (step < steps.size()) step++;
      revision++; lastNoticeAt = -1; lastNotice = ""; pendingGuidance=""; resetEvidence();
      return current();
    }
    if (Arrays.asList("이전", "이전단계", "되돌려", "아직안했어").contains(s)) {
      if (step > 0) step--;
      observedSteps.remove(step); revision++; pendingGuidance=""; resetEvidence(); return current();
    }
    return null;
  }
  synchronized String context() {
    if (!active) return "";
    StringBuilder s = new StringBuilder("사용자가 요청한 요리·조건: ").append(request)
        .append("\n진행 상태: ").append(paused ? "일시정지" : "진행 중");
    for (int i=0; i<steps.size(); i++) s.append('\n').append(i < step ?
        (observedSteps.contains(i)?"영상 관측으로 완료 추정(사용자 확인 아님): ":"사용자가 완료 확인: ") :
        i==step ? "지금 안내할 단계(완료 미확인): " : "앞으로 할 단계: ").append(steps.get(i));
    if(away)s.append("\n다른 대화 중: 능동 안내 보류");
    if(sourceChanged)s.append("\n영상 소스/시간이 바뀌어 진행 관측을 멈췄습니다. 요리 순서를 다시 시작해야 합니다.");
    return s.toString();
  }
  static boolean resumeQuestion(String text) {
    String s=text.replaceAll("[\\s.!?,。]", "");
    return Arrays.asList("뭐하고있었더라", "뭐하고있었지", "나뭐하고있었지", "아까뭐하고있었지",
        "돌아왔어뭐하고있었더라", "돌아왔어어디까지했지", "어디까지했더라", "어디까지했지", "어디까지했어").contains(s);
  }
  synchronized String recall() {
    if(!ready())return current();
    StringBuilder out=new StringBuilder();
    if(step==0)out.append("아직 완료를 확인한 단계는 없어요. ");
    else {
      out.append("지금까지 ");
      for(int i=0;i<step;i++){if(i>0)out.append(" → ");out.append(steps.get(i));}
      out.append(" 단계까지 ").append(observedSteps.isEmpty()?"완료했다고 말씀하셨어요. ":"진행했어요. 영상으로 추정한 완료와 직접 확인한 완료를 구분해 기억하고 있어요. ");
    }
    out.append(step>=steps.size()?"계획된 단계는 끝났어요.":"이어서 할 일은 "+current());
    if(sourceChanged)out.append(" 영상이 바뀐 뒤의 진행은 다시 확인해야 해요.");
    return out.toString();
  }
  synchronized String observationContext() {
    if(!active)return "";
    return context()+"\n이전 모델 관측(현재 사진 아님): "+String.join(" / ",observations);
  }
  /** Model facts only: callers must not pass evaluator annotations as phase/evidence. */
  synchronized boolean observed(String phase, String evidence, String frameKey, String source,
      long sourceMs, long at, long now, int expectedRevision) {
    observationStatus="rejected";
    if(!ambient || !ready() || sourceChanged || step>=steps.size() || revision!=expectedRevision
        || at<0 || now<at || now-at>15000 || at<=lastFrameAt || frameKey.isEmpty()
        || frameKey.equals(lastFrameKey) || evidence.isBlank() || evidence.length()>120
        || !Arrays.asList("working","complete","unknown").contains(phase))return false;
    if(!this.source.isEmpty() && (!this.source.equals(source) ||
        (sourceMs>=0 && lastSourceMs>=0 && sourceMs<lastSourceMs))) {
      sourceChanged=true;pendingGuidance="";revision++;observationStatus="source_changed";return false;
    }
    this.source=source;lastSourceMs=sourceMs;lastFrameAt=at;lastFrameKey=frameKey;
    observationStatus="recorded";
    observations.addLast("["+source+" "+sourceMs+"ms] "+phase+": "+evidence);
    while(observations.size()>4)observations.removeFirst();
    if(phase.equals("working")){seenWorking=true;completeChecks=0;firstCompleteAt=-1;return false;}
    if(phase.equals("unknown")){completeChecks=0;firstCompleteAt=-1;return false;}
    if(!seenWorking)return false;
    if(completeChecks==0){completeChecks=1;firstCompleteAt=at;return false;}
    if(at-firstCompleteAt<500)return false;
    observedSteps.add(step);
    String completed=current();step++;revision++;
    pendingGuidance="영상에서는 방금 단계가 끝난 것으로 보여요. "+
        (step<steps.size()?"다음은 "+current():"계획된 단계까지 진행했어요.");
    // Keep the actual evidence trail; the completed step is not a current-view claim.
    observations.addLast("완료 추정: "+completed+"; 근거: "+evidence);
    while(observations.size()>4)observations.removeFirst();
    resetEvidence();observationStatus="advanced";return true;
  }
  synchronized String observationStatus(){return observationStatus;}
  synchronized String pendingGuidance() { return paused||away||sourceChanged?"":pendingGuidance; }
  synchronized void guidanceDelivered(int expectedRevision) { if(revision==expectedRevision)pendingGuidance=""; }
  private void resetEvidence(){seenWorking=false;completeChecks=0;firstCompleteAt=-1;lastFrameKey="";}
  synchronized boolean notice(String text, long now, int expectedRevision) {
    if (!ready() || paused || step >= steps.size() || revision != expectedRevision || text.isBlank()
        || text.length() > 100 || text.equals(lastNotice) || (lastNoticeAt >= 0 && now-lastNoticeAt < 12000)) return false;
    lastNotice = text; lastNoticeAt = now; return true;
  }
  synchronized void timer(int seconds, long now) {
    if (!active || seconds < 1 || seconds > 3600) throw new IllegalArgumentException("1초~60분을 입력하세요");
    timerUntil = now + seconds * 1000L;
  }
  synchronized long remaining(long now) { return timerUntil < 0 ? -1 : Math.max(0, (timerUntil-now+999)/1000); }
  synchronized boolean timerDue(long now) { if (timerUntil < 0 || now < timerUntil) return false; timerUntil = -1; return true; }
  synchronized void cancelTimer() { timerUntil = -1; }
  synchronized void clear() { active = paused = ambient = away = sourceChanged = false; request = ""; steps.clear(); step = 0; revision++; timerUntil = lastNoticeAt = -1; lastNotice = "";
    observedSteps.clear();observations.clear();pendingGuidance="";source="";lastFrameAt=lastSourceMs=-1;observationStatus="";resetEvidence(); }
  private static String bounded(String text, int max) { if (text.length()>max) throw new IllegalArgumentException("400자 이내로 입력하세요"); return text.trim(); }
}
