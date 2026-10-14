"""Streaming turn invariants: no stale speech, no speculative leak, bounded final ASR."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]

def test_streaming_turns(tmp_path):
    jdk=ROOT/'.runtime/ojt/toolchains/jdk-17.0.20.1+1/bin'
    source=tmp_path/'StreamingCheck.java'
    source.write_text('''package dev.live.offline;
import java.util.*;import java.util.concurrent.*;
public class StreamingCheck {
 static void ok(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  DiagnosticSpeechWindow window=new DiagnosticSpeechWindow();
  short[] block=new short[320];Arrays.fill(block,(short)1234);
  for(int i=0;i<150;i++)ok(window.samples(block,block.length)==null);
  window.start();Arrays.fill(block,(short)-2345);
  for(int i=0;i<249;i++)ok(window.samples(block,block.length)==null);
  byte[] raw=window.samples(block,block.length);ok(raw.length==224000);
  ok((short)((raw[0]&255)|(raw[1]<<8))==1234);
  ok((short)((raw[64000]&255)|(raw[64001]<<8))==-2345);
  ok(window.samples(block,block.length)==null);
  ok(SpeechIntent.stopOnly("잠깐만."));ok(!SpeechIntent.stopOnly("잠깐만, 하나 더하기 하나는?"));
  SpeculativeReply d=new SpeculativeReply();d.start(1,"하나 더하기 하나는?");
  ok(!d.offer(1,"두입니다.",true));ok(d.commit("하나 빼기 하나는?")==null);
  ok(d.commit("하나 더하기 하나는")!=null);ok(d.offer(1,"두입니다.",true));
  d.start(2,"아버지가 방에");ok(d.commit("아버지 가방에")==null);
  d.start(2,"1+1");ok(d.commit("11")==null);d.clear();ok(!d.hidden(2));
  d.start(3,"안녕");d.offer(3,"안녕하세요.",false);d.commit("안녕");
  ok(!d.offer(3,"안녕",false)); // Old commit snapshot cannot overwrite new output.
  List<String> spoken=new ArrayList<>(),done=new ArrayList<>();
  SentenceVoice v=new SentenceVoice(new SentenceVoice.Output(){
   public void speak(long id,String s){spoken.add(id+":"+s);}public void done(long id,String e){done.add(id+":"+e);}
  });
  v.start(1);v.append(1,"첫 문장",false);ok(spoken.isEmpty());
  v.append(1,"첫 문장. 둘째",false);ok(spoken.equals(List.of("1:첫 문장.")));
  v.append(1,"첫 문장. 둘째 문장!",true);ok(done.isEmpty());
  v.sentenceDone(1,"");ok(spoken.size()==2);v.sentenceDone(1,"");ok(done.equals(List.of("1:")));
  v.append(1,"첫 문장. 둘째 문장!",true);ok(spoken.size()==2);
  v.start(2);v.append(2,"3.",false);ok(spoken.size()==2);
  v.append(2,"3.14입니다. 다음",false);ok(spoken.get(2).equals("2:3.14입니다."));
  v.start(3);v.sentenceDone(2,"");ok(done.size()==1);v.append(3,"완료.",true);v.sentenceDone(3,"");ok(done.size()==2);
  v.start(4);v.append(4,"첫 문장.",false);v.append(4,"수정됨",true);ok(done.get(2).contains("변경"));
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finalized=new CountDownLatch(1);
  List<Integer> decodes=Collections.synchronizedList(new ArrayList<>());List<String> outputs=Collections.synchronizedList(new ArrayList<>());
  StreamingAsr asr=new StreamingAsr(new StreamingAsr.Decoder(){
   public void cancel(long t){}
   public String decode(byte[] pcm,long t)throws Exception {decodes.add((int)pcm[0]);if(pcm[0]==1){entered.countDown();release.await();}return "r"+pcm[0];}
  },new StreamingAsr.Output(){
   public void text(long t,String s,boolean last,long at){outputs.add(s);if(last)finalized.countDown();}
   public void error(long t,String e){throw new AssertionError(e);}
  });
  long t=asr.begin();asr.accept(t,new byte[]{1},false,0);ok(entered.await(2,TimeUnit.SECONDS));
  asr.accept(t,new byte[]{2},false,0);asr.accept(t,new byte[]{3},true,0);asr.accept(t,new byte[]{4},false,0);
  release.countDown();ok(finalized.await(2,TimeUnit.SECONDS));asr.close();asr.awaitClosed();
  ok(decodes.equals(List.of(1,3)));ok(outputs.equals(List.of("r1","r3")));
  CountDownLatch partialReady=new CountDownLatch(1),cachedFinal=new CountDownLatch(1);
  java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  StreamingAsr cached=new StreamingAsr(new StreamingAsr.Decoder(){
   public void cancel(long id){}public String decode(byte[] p,long id){calls.incrementAndGet();return "same";}
  },new StreamingAsr.Output(){public void text(long id,String s,boolean last,long at){if(last)cachedFinal.countDown();else partialReady.countDown();}public void error(long id,String e){throw new AssertionError(e);}});
  long cachedTurn=cached.begin();cached.accept(cachedTurn,new byte[]{3,4},false,0);ok(partialReady.await(2,TimeUnit.SECONDS));
  cached.accept(cachedTurn,new byte[]{3,4},true,1);ok(cachedFinal.await(2,TimeUnit.SECONDS));ok(calls.get()==1);
  cached.close();cached.awaitClosed();
  CountDownLatch hold=new CountDownLatch(1),started=new CountDownLatch(1);
  List<String> stale=new ArrayList<>();
  StreamingAsr cancelled=new StreamingAsr(new StreamingAsr.Decoder(){
   public void cancel(long id){}public String decode(byte[] p,long id)throws Exception{started.countDown();hold.await();return "late";}
  },new StreamingAsr.Output(){public void text(long id,String s,boolean last,long at){stale.add(s);}public void error(long id,String e){stale.add(e);}});
  long old=cancelled.begin();cancelled.accept(old,new byte[]{1},true,0);ok(started.await(2,TimeUnit.SECONDS));cancelled.cancel();hold.countDown();cancelled.close();cancelled.awaitClosed();ok(stale.isEmpty());
    CountDownLatch contextStarted=new CountDownLatch(1),contextRelease=new CountDownLatch(1),contextFinal=new CountDownLatch(1);
    List<String> contexts=Collections.synchronizedList(new ArrayList<>()),contextOutputs=Collections.synchronizedList(new ArrayList<>());
    StreamingAsr scoped=new StreamingAsr(new StreamingAsr.Decoder(){
      public void cancel(long id){}
      public String decode(byte[] p,long id){throw new AssertionError("context not passed");}
      public String decode(byte[] p,long id,String context)throws Exception {
        contexts.add(context);if(p[0]==1){contextStarted.countDown();contextRelease.await();}return context;
      }
    },new StreamingAsr.Output(){public void text(long id,String value,boolean last,long at){contextOutputs.add(value);if(last)contextFinal.countDown();}public void error(long id,String error){throw new AssertionError(error);}});
    long prior=scoped.begin("타이머");scoped.accept(prior,new byte[]{1},true,0);ok(contextStarted.await(2,TimeUnit.SECONDS));
    long next=scoped.begin("");scoped.accept(next,new byte[]{2},true,0);contextRelease.countDown();ok(contextFinal.await(2,TimeUnit.SECONDS));
    scoped.close();scoped.awaitClosed();ok(contexts.equals(List.of("타이머","")));ok(contextOutputs.equals(List.of("")));

 }
}''')
    folder=ROOT/'android/app/src/main/java/dev/live/offline'
    subprocess.run([str(jdk/'javac'),'-d',str(tmp_path),str(source),*[str(folder/f'{name}.java') for name in ('StreamingAsr','SpeculativeReply','SentenceVoice','SpeechIntent','DiagnosticSpeechWindow')]],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(tmp_path),'dev.live.offline.StreamingCheck'],check=True,timeout=15)
