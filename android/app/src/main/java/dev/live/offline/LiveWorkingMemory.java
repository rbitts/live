package dev.live.offline;

import dev.live.memory.WorkingMemory;
import java.util.regex.*;

/** Live adapter: exact control grammar after final ASR; timer is a real local tool.
 * Unsupported language is retained as input, never guessed into a tool call. */
final class LiveWorkingMemory {
  final WorkingMemory state=new WorkingMemory();
  private String timerId="";
  private int seconds;
  private long deadline;
  private static final String N="([0-9]+|[일이삼사오육칠팔구십백]+)";
  private static final String END="(?:로|으로|만)?(?:해|해줘|해줄래|해주세요|할래|바꿔|바꿔줘|바꿔줄래|맞춰줘|재줘|재줄래|설정해줘|부탁해|요)?";
  private static final Pattern CORRECTION=Pattern.compile("^"+N+"초(?:는|가|짜리|타이머)?(?:말고|아니고|대신)"+N+"초"+END+"$");
  private static final Pattern SHORT=Pattern.compile("^"+N+"초"+END+"$");
  private static final Pattern REMIND=Pattern.compile("^"+N+"초(?:뒤에|후에|뒤|후)(?:알려줘|알려줄래|알려주세요|알려줘요|알려주라)$");
  private static final Pattern CANCEL=Pattern.compile("^(?:그|지금|방금)?(?:타이머|알림)(?:은|는|을|를)?(?:취소|취소해|취소해줘|취소해줄래|없애줘|필요없어)$");

  private static String conversational(String text) {
    return text.replaceAll("[\\s.!?,。！？]", "")
        .replaceFirst("^(?:(?:아잠깐만?|잠깐만?|아니요|아니야|아니|아냐|아(?!까)|어|음|응|그럼|그러면|일단|다시|그런데|근데))+", "");
  }
  private static final Pattern TIMER=Pattern.compile("^(?:잠깐만[.。!?, ]*|아니[.。!?, ]*)?(?:타이머를?\\s*)?([0-9]+|[일이삼사오육칠팔구십백]+)\\s*초(?:짜리)?\\s*(?:타이머)?\\s*(?:로)?\\s*(?:설정해\\s*줘|설정해|시작해\\s*줘|시작해|맞춰\\s*줘|맞춰|바꿔\\s*줘|바꿔|변경해\\s*줘|변경해|해\\s*줘|해)?[.。!?]*$");

  synchronized String input(String text,long now){
    state.input(text,now);
    String normalized=text.replaceAll("[\\s.!?,。]","");
    String spoken=conversational(text);
    if(normalized.equals("계속해")||normalized.equals("다시말해줘")||normalized.equals("재개")
        || spoken.matches("(?:아까하던말|아까말하던거|하던말|이어서)?(?:계속해|계속말해줘|계속해줘|말해줘)")){
      state.resume(now);return "이어서 안내할게요.";
    }
    if(normalized.equals("타이머취소")||normalized.equals("타이머취소해줘")||normalized.equals("작업취소")||normalized.equals("작업취소해줘")
        || CANCEL.matcher(spoken).matches()
        || (!timerId.isEmpty() && spoken.matches("(?:그거|그건|그거는)(?:취소해|취소해줘|없애줘|필요없어)"))){
      if(!normalized.startsWith("작업취소") && timerId.isEmpty()
          && !state.snapshot().goal.matches("[0-9]+초 타이머"))return "지금 진행 중인 타이머가 없어요.";
      cancelTimer();state.goal("");return "작업을 취소했어요.";
    }
    Matcher correction=CORRECTION.matcher(spoken), reminder=REMIND.matcher(spoken), shortTime=SHORT.matcher(spoken);
    if(correction.matches()) {
      if(timerId.isEmpty())return "지금 진행 중인 타이머가 없어요. 새 타이머를 맞출까요?";
      if(number(correction.group(1))!=seconds)return "지금 타이머는 "+seconds+"초로 설정되어 있어요. 이 타이머를 바꿀까요?";
      return startTimer(number(correction.group(2)),now,true);
    }
    if(reminder.matches())return startTimer(number(reminder.group(1)),now,!timerId.isEmpty());
    if(shortTime.matches()&&!timerId.isEmpty())return startTimer(number(shortTime.group(1)),now,true);
    Matcher timer=TIMER.matcher(text.trim());
    if(timer.matches()&& (text.contains("타이머")||!timerId.isEmpty())){
      int duration=number(timer.group(1));
      if(duration<1||duration>3600)return "타이머는 1초부터 60분까지 설정할 수 있어요.";
      return startTimer(duration,now,!timerId.isEmpty());
    }
    if(text.contains("타이머") && (text.contains("바꿔") || text.contains("변경") || text.contains("시작") || text.contains("설정")))
      return "타이머 시간을 정확히 듣지 못했어요. 몇 초인지 다시 말씀해 주세요. 기존 타이머는 그대로 유지해요.";
    if(text.startsWith("작업 변경")||text.startsWith("작업변경")){
      cancelTimer();state.goal(text);return "작업을 바꿨어요. "+text;
    }
    if(normalized.equals("지금작업은뭐야")||normalized.equals("어디까지했어"))return status(now);
    // Explicit exclusions are stored with their source, not inferred from model output.
    // A delivered, terminal timer must not remain the goal of a new question.
    // Keep an active timer and any undelivered result intact.
    if(state.snapshot().goal.isEmpty() || (timerId.isEmpty() && state.snapshot().pending==0
        && state.snapshot().goal.matches("[0-9]+초 타이머")))state.goal(text);
    Matcher exclusion=Pattern.compile("^(?:잠깐만[.。!?, ]*|아니[.。!?, ]*)?(.{1,40}?)(?:은|는|을|를)\\s*(?:빼고|빼\\s*줘|제외해)(.*)$").matcher(text);
    if(exclusion.matches()&&text.length()<=120)state.constraint(exclusion.group(1).trim(),exclusion.group(1).trim()+" 제외",text);
    if(state.snapshot().goal.isEmpty())state.goal(text);
    return null;
  }
  private String startTimer(int duration,long now,boolean replacement) {
    if(duration<1||duration>3600)return "타이머는 1초부터 60분까지 설정할 수 있어요.";
    cancelTimer();seconds=duration;state.replaceGoal(duration+"초 타이머");
    timerId=state.admit(state.lease(),"local_timer",duration+"초",true,now);
    deadline=now+duration*1000L;state.acknowledge(timerId);
    return replacement?"좋아요. 지금부터 "+duration+"초로 다시 맞췄어요. 시간이 되면 알려드릴게요.":duration+"초 뒤에 알려드릴게요.";
  }
  // Recognition bias is a task category only, never a guessed duration/transcript.
  synchronized String recognitionContext(){return timerId.isEmpty()?"":"타이머";}
  synchronized void tick(long now){
    if(!timerId.isEmpty()&&now>=deadline){
      String id=timerId;timerId="";
      state.outcome(id,true,seconds+"초 타이머가 끝났어요. 설정한 시간이 되었습니다.",now);
    }
  }
  synchronized void reset(){timerId="";deadline=0;state.reset();}
  private void cancelTimer(){if(!timerId.isEmpty())state.cancel(timerId,true);timerId="";deadline=0;}
  private String status(long now){
    if(!timerId.isEmpty())return seconds+"초 타이머가 진행 중이고 약 "+Math.max(0,(deadline-now+999)/1000)+"초 남았어요.";
    return state.snapshot().goal.isEmpty()?"진행 중인 작업이 없어요.":"현재 작업은 "+state.snapshot().goal+"입니다.";
  }
  private static int number(String s){
    if(s.matches("[0-9]+")){try{return Integer.parseInt(s);}catch(NumberFormatException e){return -1;}}
    if(!s.matches("(?:[일이삼사오육칠팔구]?백)?(?:[일이삼사오육칠팔구]?십)?[일이삼사오육칠팔구]?"))return -1;
    int total=0,digit=0;String digits="일이삼사오육칠팔구";
    for(char c:s.toCharArray()){
      int d=digits.indexOf(c)+1;
      if(d>0){if(digit!=0)return -1;digit=d;}
      else if(c=='십'||c=='백'){total+=(digit==0?1:digit)*(c=='십'?10:100);digit=0;}
      else return -1;
    }
    return total+digit;
  }
}
