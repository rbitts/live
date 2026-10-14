"""Shared state/agent/Live adapters run the same JVM core, without Android or models."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def test_working_memory_contract(tmp_path):
    source = tmp_path / 'MemoryCheck.java'
    source.write_text(r'''package dev.live.offline;
import dev.live.memory.*;
public class MemoryCheck {
  static void ok(boolean b){if(!b)throw new AssertionError();}
  static void reject(Runnable r){try{r.run();throw new AssertionError("accepted");}catch(IllegalStateException|IllegalArgumentException expected){}}
  public static void main(String[] args)throws Exception {
    WorkingMemory m=new WorkingMemory();m.input("소금은 빼고 알려 줘",0);m.goal("샐러드");
    WorkingMemory.Lease stale=m.lease();m.constraint("소금","제외","소금은 빼고");
    ok(!m.valid(stale));reject(()->m.admit(stale,"write","x",true,1));
    reject(()->m.constraint("설탕","제외","설탕은 빼고"));
    reject(()->m.admit(m.lease(),"write","x",false,1));
    ok(m.context().contains("소금"));WorkingMemory.Snapshot snap=m.snapshot();
    m.input("소금은 넣어 줘",1);m.constraint("소금","허용","소금은 넣어 줘");
    ok(snap.constraints.get(0).contains("제외"));ok(!m.context().contains("제외"));
    String id=m.admit(m.lease(),"test","revision=abc",true,1);
    ok(m.next(501,false)==null);
    WorkingMemory.Followup start=m.next(2001,false);ok(start.kind.equals("started"));
    m.userStarted();ok(!m.delivered(start));ok(m.next(3000,false)==null);
    // Tool completion is a fact during speech, not an immediate permission to speak.
    ok(m.outcome(id,true,"tests passed at abc",3100));ok(!m.outcome(id,true,"duplicate",3200));
    ok(m.snapshot().pending==1);ok(m.next(3500,false)==null);
    m.input("결과 알려 줘",3600);ok(m.next(4000,false)==null);ok(m.next(4100,true)==null);
    WorkingMemory.Followup result=m.next(4100,false);ok(result.kind.equals("result"));
    m.interrupt();ok(!m.delivered(result));m.hold();ok(m.next(9999,false)==null);
    m.resume(10000);result=m.next(10500,false);ok(result!=null);ok(m.delivered(result));
    ok(!m.delivered(result));ok(m.next(11000,false)==null);
    String old=m.admit(m.lease(),"write","old target",true,12000);
    m.goal("new target");ok(m.outcome(old,true,"old write did finish",12500));
    ok(m.snapshot().actions.get(1).receipt.equals("old write did finish"));ok(m.next(13000,false)==null);
    String forgotten=m.admit(m.lease(),"read","x",true,13000);m.reset();
    ok(!m.outcome(forgotten,true,"late reset result",14000));ok(m.snapshot().actions.isEmpty());
    // Terminal results outrank progress and actual delivery failure is retryable, bounded.
    m.input("run",0);String a=m.admit(m.lease(),"test","a",true,0);
    String b=m.admit(m.lease(),"test","b",true,0);m.outcome(b,false,"test failed",2100);
    result=m.next(2100,false);ok(result.kind.equals("result"));m.deliveryFailed(result,2100);
    start=m.next(2200,false);ok(start.kind.equals("started"));ok(m.delivered(start));
    ok(m.next(7000,false)==null);result=m.next(7100,false);ok(m.delivered(result));
    m.outcome(a,true,"done a",7200);result=m.next(7200,false);ok(result.kind.equals("result"));
    ok(m.delivered(result));
    // Development adapter: real asynchronous local tool and receipt, no new user turn.
    AgentMemorySession agent=new AgentMemorySession();agent.message("verify local artifact",0);
    String tool=agent.beforeTool(agent.memory.lease(),"local_check","fixture",true,0);
    java.nio.file.Path artifact=java.nio.file.Files.createTempFile("agent-memory-", ".txt");
    java.nio.file.Files.writeString(artifact,"expected");
    Thread worker=new Thread(()->{
      try{agent.toolFinished(tool,java.nio.file.Files.readString(artifact).equals("expected"),"artifact checked",1000);}
      catch(Exception e){throw new RuntimeException(e);}
    });worker.start();worker.join();java.nio.file.Files.delete(artifact);
    WorkingMemory.Followup notice=agent.next(1000,false);ok(notice.text.equals("artifact checked"));
    ok(agent.published(notice));ok(agent.next(2000,false)==null);
    agent.memory.resourceChanged("tree-sha-a");WorkingMemory.Lease fileLease=agent.memory.lease();
    String running=agent.beforeTool(fileLease,"test","tree-sha-a",true,2000);
    agent.memory.resourceChanged("tree-sha-b");
    reject(()->agent.beforeTool(fileLease,"write","old tree",true,2100));
    ok(agent.toolFinished(running,true,"old tree passed",2500));
    ok(agent.next(3000,false)==null);ok(agent.memory.context().contains("재검증 필요"));
    agent.message("dependency excluded",4000);
    String beforeCondition=agent.beforeTool(agent.memory.lease(),"check","old assumptions",true,4000);
    agent.memory.constraint("dependency","excluded","dependency excluded");
    ok(agent.toolFinished(beforeCondition,true,"old assumptions passed",4500));ok(agent.next(5000,false)==null);
    // Live adapter: timer replacement, cancellation, retained completion and floor.
    LiveWorkingMemory live=new LiveWorkingMemory();
    ok(live.recognitionContext().isEmpty());
    ok(live.input("삼십 초 타이머 시작해 줘.",0).contains("30초"));ok(live.recognitionContext().equals("타이머"));
    live.state.userStarted();ok(live.input("십 초 타이머로 바꿔 줘.",5000).contains("10초"));
    live.tick(14999);ok(live.state.snapshot().pending==0);
    live.state.userStarted();live.tick(15000);ok(live.recognitionContext().isEmpty());ok(live.state.snapshot().pending==1);ok(live.state.next(16000,false)==null);
    live.state.input("잠깐만",17000);live.state.hold();ok(live.state.next(18000,false)==null);
    live.input("계속해",19000);notice=live.state.next(19500,false);ok(notice.text.contains("10초"));
    ok(live.state.delivered(notice));live.tick(40000);ok(live.state.snapshot().pending==0);
    live.input("20초 타이머 시작해 줘",40000);live.input("타이머 취소해 줘",41000);live.tick(99999);
    ok(live.state.snapshot().pending==0);live.input("5초 타이머 시작해 줘",100000);live.reset();live.tick(200000);ok(live.state.snapshot().pending==0);
    ok(live.input("타이머가 십 초인지 궁금해",200001)==null); // No substring-triggered tool.
    live.input("30초 타이머 시작해 줘",200002);live.tick(230002);
    live.input("30초 타이머 시작해 줘",230003);ok(live.state.snapshot().pending==0); // Same duration is a new task.
    ok(live.input("시초 타이머로 바꿔줘",230004).contains("정확히 듣지"));
    ok(live.state.snapshot().goal.equals("30초 타이머"));
    // A new question retires only a timer whose terminal result was delivered.
    live.reset();live.input("10초 타이머 시작해 줘",0);live.tick(10000);
    live.input("오늘 할 일을 알려 줘",11000);ok(live.state.snapshot().goal.equals("10초 타이머"));
    notice=live.state.next(11500,false);ok(notice!=null);ok(live.state.delivered(notice));
    live.input("소금은 빼고 샐러드 만드는 방법 알려 줘",12000);
    ok(live.state.snapshot().goal.contains("샐러드"));ok(live.state.snapshot().constraints.size()==1);
    live.input("더 자세히 알려 줘",13000);ok(live.state.snapshot().constraints.size()==1);
    live.reset();live.input("30초 타이머 시작해 줘",0);
    live.input("오이 손질하는 법 알려 줘",1000);ok(live.state.snapshot().goal.equals("30초 타이머"));
    live.tick(30000);ok(live.state.snapshot().pending==1);
    // Natural corrections resolve the existing task; old/new numbers stay distinct.
    live.reset();ok(live.input("일단 삼십 초 뒤에 알려 줄래?",0).contains("30초"));
    ok(live.input("아 잠깐, 삼십 초 말고 이십 초로 해 줘.",1000).contains("20초"));
    ok(live.state.snapshot().goal.equals("20초 타이머"));
    ok(live.input("그럼 십 초만 재 줘.",2000).contains("10초"));
    live.input("아냐, 그 타이머는 취소해 줘.",3000);live.tick(100000);
    ok(live.state.snapshot().pending==0 && live.state.snapshot().goal.isEmpty());
    ok(live.input("그럼 다시 이십 초 뒤에 알려 줘.",101000).contains("20초"));
    ok(live.input("아 잠깐 30초 말고 10초",102000).contains("지금 타이머는 20초"));
    ok(live.state.snapshot().goal.equals("20초 타이머")); // Wrong reference must not replace it.
    ok(live.input("20초 말고 15초",103000).contains("15초"));
    live.input("그거 취소해 줘",104000);live.tick(200000);ok(live.state.snapshot().pending==0);
    ok(live.input("30초 말고 20초",201000).contains("진행 중인 타이머가 없어요"));
    ok(live.input("그럼 10초만",202000)==null); // No active referent, no implicit timer.
    live.input("30초 후에 알려주세요",203000);
    ok(live.input("타이머 취소하지 마",203001)==null);
    ok(live.input("30초 말고 20초라는 말이 무슨 뜻이야?",203002)==null);
    ok(live.state.snapshot().goal.equals("30초 타이머"));
    live.tick(233000);live.state.hold();live.input("응, 아까 하던 말 계속해.",234000);
    ok(!live.state.snapshot().speechHeld && live.state.snapshot().pending==1);
    // Explicit image memory outlives unrelated dialogue, but remains labeled past.
    AssistantMemory visuals=new AssistantMemory();
    visuals.rememberVisual("기억해줘", "이미지를 볼 수 없으므로 설명할 수 없습니다",1,2,"sample",0);
    ok(visuals.visualContext(3).isEmpty()); // An image-denial answer is not an observation.
    visuals.rememberVisual("이 동물 어디 있는지 봐 두고 기억해 줘", "흰 토끼가 잔디밭에 서 있어요",1000,26000,"sample-preview-copy",3000);
    for(int i=0;i<12;i++)visuals.turn("timer","done",27000+i);
    ok(visuals.visualContext(30000).contains("흰 토끼"));
    ok(visuals.visualContext(30000).contains("현재 화면 아님"));
    ok(visuals.context(30000,29000).isEmpty()); // Independent of goal-scoped dialogue.
    String recallQuestion="화면은 껐어. 아까 그 동물이 어디 있었는지 다시 알려 줘.";
    String recalled=visuals.unavailableVisualReply(recallQuestion,30000);
    ok(recalled.contains("흰 토끼가 잔디밭") && recalled.contains("당시 화면") && recalled.contains("현재 영상이 없어"));
    ok(!visuals.unavailableVisualReply("그럼 지금도 거기 있어?",30000).contains("토끼"));
    ok(visuals.unavailableVisualReply("아까 타이머 몇 초였지?",30000)==null);
    ok(visuals.unavailableVisualReply("지금 할 일 알려줘",30000)==null);
    visuals.rememberVisual("아까 토끼 어디 있었지", "지금도 있어요",30000,31000,"sample",30000);
    ok(!visuals.visualContext(32000).contains("지금도 있어요")); // Recall isn't a new observation.
    visuals.configure(false,300000);ok(visuals.visualContext(32000).isEmpty());
    visuals.configure(true,1000);
    visuals.rememberVisual("기억해줘", "observed",40000,40001,"camera-request",-1);
    ok(visuals.visualContext(41001).isEmpty());
    visuals.rememberVisual("기억해줘", "observed",50000,50001,"sample",1);
    visuals.clear();ok(visuals.visualContext(50002).isEmpty());
    ok(visuals.unavailableVisualReply(recallQuestion,50002).contains("영상 관측이 없어요"));
    AssistantMemory conversation=new AssistantMemory();
    conversation.turn("old timer","old acknowledgement",1);conversation.turn("current question","current answer",2);
    ok(!conversation.context(3,2).contains("old timer"));ok(conversation.context(3,2).contains("current answer"));
    ok(conversation.context(3).contains("old timer")); // Scope selection never deletes history.
    for(int i=0;i<200;i++)m.input("message"+i,i);ok(m.snapshot().events.size()==64);
  }
}''')
    jdk = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    files = sorted((ROOT / 'shared/src/main/java').rglob('*.java'))
    files += [ROOT/'android/app/src/main/java/dev/live/offline/LiveWorkingMemory.java', ROOT/'android/app/src/main/java/dev/live/offline/AssistantMemory.java', source]
    subprocess.run([str(jdk/'javac'), '-d', str(tmp_path), *map(str, files)], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(tmp_path), 'dev.live.offline.MemoryCheck'], check=True, timeout=15)
