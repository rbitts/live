"""Compile the platform-independent cooking policy and exercise race/expiry boundaries."""
from pathlib import Path
import subprocess
import shutil
import pytest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / '.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'


def test_runtime_cooking_state_and_scene_gate(tmp_path):
    javac = str(JAVA / 'javac') if (JAVA / 'javac').exists() else shutil.which('javac')
    java = str(JAVA / 'java') if (JAVA / 'java').exists() else shutil.which('java')
    if not javac or not java:
        pytest.skip('JDK required for Android state policy test')
    source = tmp_path / 'CookingCheck.java'
    source.write_text('''package dev.live.offline;
import java.util.*;
public class CookingCheck {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  CookingSession c=new CookingSession();
  c.start("있는 채소로 샐러드, 소금 제외");int revision=c.revision();
  c.plan(c.request(),Arrays.asList("채소를 씻으세요","먹기 좋게 잘라 주세요"),revision);
  ok(c.index()==0 && c.context().contains("소금 제외"));
  ok(c.notice("채소를 씻었나요?",1000,c.revision()));ok(c.index()==0);
  ok(!c.notice("채소를 씻었나요?",20000,c.revision()));
  ok(!c.notice("다른 질문",2000,c.revision()));
  c.command("잠깐");c.command("다했어");ok(c.index()==0);
  ok(!c.notice("다른 질문",20000,c.revision()));
  c.timer(1,20000);ok(!c.timerDue(20999));ok(c.timerDue(21000));ok(!c.timerDue(22000));
  c.command("계속");c.command("다 했어!");ok(c.index()==1);
  ok(c.context().contains("사용자가 완료 확인: 채소를 씻으세요"));
  ok(c.command("어디까지 했지?").contains("잘라"));
  c.command("이전");ok(c.index()==0);
  int old=c.revision();c.start("달걀로 요리");
  c.plan("옛 요청",Arrays.asList("옛 단계"),old);ok(!c.ready());
  c.plan(c.request(),Arrays.asList("달걀을 준비하세요"),c.revision());
  ok(!c.context().contains("채소"));c.command("완료");ok(c.index()==c.count());
  ok(!c.notice("다른 질문",30000,c.revision()));
  c.clear();ok(!c.active()&&c.remaining(0)==-1&&c.command("계속")==null);
  SceneGate gate=new SceneGate();byte[] dark=new byte[192],light=new byte[192];Arrays.fill(light,(byte)200);
  gate.frame(dark,1000);ok(gate.due(1000));gate.checked(1000);
  gate.frame(light,2000);ok(!gate.due(3499));ok(gate.due(3500));gate.checked(3500);
  gate.frame(light,4000);ok(!gate.due(6000));ok(!gate.due(13500));
  gate.frame(light,13500);ok(gate.due(13500));gate.checked(13500);
  gate.frame(dark,13000);ok(!gate.due(16000));
  gate.clear();ok(!gate.due(17000));
  try{gate.frame(new byte[0],18000);throw new AssertionError();}catch(IllegalArgumentException expected){}
  ok(CookingVisualPolicy.question("컵에 물을 담아 주세요.").contains("cup or drinking glass"));
  ok(CookingVisualPolicy.question("양파를 잘라 주세요.").contains("onion"));
  ok(CookingVisualPolicy.question("물 한 잔을 준비해 주세요.").contains("cup or drinking glass"));
  ok(CookingVisualPolicy.question("마무리해 주세요.").isEmpty());
  ok(CookingVisualPolicy.question("칼국수와 팬케이크를 준비해 주세요.").isEmpty());
  ok(CookingVisualPolicy.positive(" Yes.\\n"));
  for(String value:new String[]{"NO","YES, cooked","{yes:true}","Maybe",""})ok(!CookingVisualPolicy.positive(value));
  ok(CookingVisualPolicy.reminder("컵을 준비해 주세요.","NO").isEmpty());
  ok(CookingPlanPolicy.issue("달걀프라이",Arrays.asList("달걀을 저어 주세요.")).length()>0);
  ok(CookingPlanPolicy.issue("스크램블 에그",Arrays.asList("달걀을 저어 주세요.")).isEmpty());
  ok(CookingPlanPolicy.issue("달걀을 프라이팬에 스크램블로 요리",Arrays.asList("달걀을 저어 주세요.")).isEmpty());
  SceneGate fast=new SceneGate(1000);fast.frame(dark,1000);fast.checked(1000);fast.frame(light,1500);
  ok(!fast.due(1999)&&fast.due(2000));
  fast.checked(2000);fast.frame(light,2500);ok(!fast.due(3000)&&fast.taskDue(3000));
 }
}''')
    production = ROOT / 'android/app/src/main/java/dev/live/offline'
    subprocess.run([javac, '-d', str(tmp_path), str(source),
                    str(production / 'CookingSession.java'), str(production / 'SceneGate.java'),
                    str(production / 'CookingVisualPolicy.java'), str(production / 'CookingPlanPolicy.java')], check=True)
    subprocess.run([java, '-cp', str(tmp_path), 'dev.live.offline.CookingCheck'], check=True)


def test_observed_progress_interruption_and_resume(tmp_path):
    javac, java = str(JAVA / 'javac'), str(JAVA / 'java')
    if not Path(javac).exists():
        pytest.skip('JDK required')
    source = tmp_path / 'ObservedCookingCheck.java'
    source.write_text('''package dev.live.offline;
import java.util.*;
public class ObservedCookingCheck {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 static CookingSession task(){
  CookingSession c=new CookingSession();c.start("오이 샐러드");c.ambient(true);
  c.plan(c.request(),Arrays.asList("오이를 썰어 주세요.","썬 오이를 그릇에 넣어 주세요.","샐러드를 섞어 주세요."),c.revision());return c;
 }
 static boolean obs(CookingSession c,String phase,String key,long at){
  return c.observed(phase,"사진에서 확인한 근거",key,"sample-video",at,at,at+100,c.revision());
 }
 public static void main(String[] args){
  CookingSession c=task();
  ok(!obs(c,"complete","a",1000));ok(c.index()==0); // Never observed the action.
  ok(!obs(c,"working","b",2000));
  int old=c.revision();ok(!obs(c,"complete","c",3000));
  ok(!obs(c,"complete","c",4000));ok(c.index()==0); // Same image cannot confirm twice.
  ok(!obs(c,"unknown","d",5000));ok(!obs(c,"complete","e",6000));
  ok(!obs(c,"complete","f",6200));ok(c.index()==0); // Insufficient independent interval.
  ok(obs(c,"complete","g",7000));ok(c.index()==1);
  ok(c.context().contains("영상 관측으로 완료 추정"));ok(!c.pendingGuidance().isEmpty());
  ok(!c.observed("working","늦은 결과","late","sample-video",8000,8000,8100,old));
  ok(!c.observed("complete","오래된 결과","stale","sample-video",8000,8000,24000,c.revision()));
  c.command("잠깐 옆 사람이랑 얘기하고 올게.");ok(c.away()&&c.pendingGuidance().isEmpty());
  ok(!obs(c,"working","h",9000));ok(!obs(c,"complete","i",10000));
  ok(obs(c,"complete","j",11000));ok(c.index()==2&&c.pendingGuidance().isEmpty());
  String recall=c.command("돌아왔어. 뭐 하고 있었더라?");
  ok(!c.away()&&recall.contains("썰어")&&recall.contains("그릇")&&recall.contains("이어서"));
  c.guidanceDelivered(old);ok(!c.pendingGuidance().isEmpty());
  c.guidanceDelivered(c.revision());ok(c.pendingGuidance().isEmpty());
  c.command("이전 단계");ok(c.index()==1);ok(!obs(c,"complete","k",12000));
  ok(!obs(c,"working","l",13000));
  ok(!c.observed("complete","이전 재생","rewind","sample-video",1000,14000,14100,c.revision()));
  ok(c.context().contains("시간이 바뀌어"));ok(c.pendingGuidance().isEmpty());
  c.clear();ok(!c.active()&&!c.ambient()&&c.observationContext().isEmpty());
  CookingSession manual=task();manual.ambient(false);ok(!obs(manual,"working","m",1000));
  ok(!obs(manual,"complete","n",2000));ok(!obs(manual,"complete","o",3000));ok(manual.index()==0);
  manual.command("다 했어");ok(manual.context().contains("사용자가 완료 확인"));
 }
}''')
    production = ROOT / 'android/app/src/main/java/dev/live/offline'
    subprocess.run([javac, '-d', str(tmp_path), str(source),
                    str(production / 'CookingSession.java'), str(production / 'CookingPlanPolicy.java')], check=True)
    subprocess.run([java, '-cp', str(tmp_path), 'dev.live.offline.ObservedCookingCheck'], check=True)
