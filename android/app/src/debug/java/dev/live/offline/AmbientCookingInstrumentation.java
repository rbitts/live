package dev.live.offline;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Whole chronological video + production mock PCM/ASR. No annotation triggers. */
public final class AmbientCookingInstrumentation extends Instrumentation {
  private MainActivity activity;
  private File directory;
  private int seenChecks;
  private long lastPosition;
  private final JSONObject report=new JSONObject();
  private final JSONArray observations=new JSONArray(),turns=new JSONArray();
  private Object get(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
  private void set(String n,Object v)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,v);}
  private Object call(String n,Class<?>[] t,Object...a)throws Exception {Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,a);}
  private long epoch()throws Exception{return ((AtomicLong)get("epoch")).get();}
  private void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private void main(Runnable action) {
    final Throwable[] failure={null};
    runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
    if(failure[0]!=null)throw new RuntimeException(failure[0]);
  }
  private String hash(byte[] b)throws Exception{StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format("%02x",v));return s.toString();}
  private void save()throws Exception{try(FileWriter out=new FileWriter(new File(directory,"ambient-cooking-results.json"))){out.write(report.toString(2));}}
  private void record()throws Exception {
    int count=(int)get("taskAttempts");if(count==seenChecks || observations.length()>=512)return;seenChecks=count;
    ObservationMemory.Frame f=(ObservationMemory.Frame)get("lastTaskFrame");if(f==null)return;
    String name="task-evidence-"+count+".jpg";Files.write(new File(directory,name).toPath(),f.jpeg);
    CookingSession c=(CookingSession)get("cooking");
    observations.put(new JSONObject((String)get("lastTaskDiagnostic")).put("at",f.at).put("source_ms",f.sourceMs)
        .put("file",name).put("sha256",hash(f.jpeg)).put("step_after",c.index())
        .put("away",c.away()).put("pending_guidance",c.pendingGuidance()).put("response",get("response")));
    save();
  }
  interface Check{boolean ok()throws Exception;}
  private void waitFor(Check check,long timeout,String why)throws Exception {
    long end=SystemClock.elapsedRealtime()+timeout;
    do{record();if(check.ok())return;Thread.sleep(100);}while(SystemClock.elapsedRealtime()<end);
    throw new AssertionError(why);
  }
  private long position()throws Exception {
    SampleVideoInput v=(SampleVideoInput)get("sampleVideo");
    if(v!=null){lastPosition=Math.max(lastPosition,v.positionMs);return lastPosition;}
    if(lastPosition>=425000)return 427500;
    throw new AssertionError("video stopped before the end");
  }
  private void at(long ms)throws Exception{waitFor(()->position()>=ms,ms+15000,"video did not advance");}
  private JSONObject say(String text,boolean answer)throws Exception {
    JSONObject row=new JSONObject().put("spoken",text).put("source_ms",position());turns.put(row);
    Capture capture=(Capture)get("capture");
    waitFor(()->!capture.mockInput.feeding()&&!((MockVoiceInput)get("mockVoice")).busy(),15000,"input busy");
    long old=epoch(),finalTurn=(long)get("finalizedAsrTurn"),start=SystemClock.elapsedRealtime();
    int completed=(int)get("completedTurns");long samples=capture.capturedSamples.get();
    row.put("during_playback",call("isVoicePlaying",new Class<?>[]{}));
    main(()->{try{require((boolean)call("sendMockVoice",new Class<?>[]{String.class},text),"mock send");}catch(Exception e){throw new RuntimeException(e);}});
    waitFor(()->epoch()!=old,15000,"voice onset");
    row.put("old_pcm_rejected",!activity.onPcm(old,new float[480])).put("stop_ms",get("lastBargeInStopMs"));
    waitFor(()->(long)get("finalizedAsrTurn")!=finalTurn && !((LiveWorkingMemory)get("working")).state.snapshot().userActive,35000,"ASR final");
    row.put("recognized",get("microphoneTranscript")).put("final_ms",SystemClock.elapsedRealtime()-start);
    if(answer) {
      waitFor(()->(int)get("completedTurns")>completed,45000,"answer publication");
      waitFor(()->(boolean)call("isVoicePlaying",new Class<?>[]{}),15000,"answer playback");
      row.put("response",get("response")).put("first_write_ms",get("firstPlaybackMs"));
      row.put("response_path",get("lastResponsePath")).put("evidence_at",get("lastEvidenceAt"))
          .put("evidence_source_ms",get("lastEvidenceSourceMs")).put("voice_anchor",get("alignedAnchor"));
      if((long)get("lastEvidenceAt")>=0)require((long)get("lastEvidenceAt")<=(long)get("alignedAnchor"),"future image replaced voice evidence");
    }
    row.put("task_state",((CookingSession)get("cooking")).context()).put("captured_samples",capture.capturedSamples.get()-samples);
    if(!answer){long until=SystemClock.elapsedRealtime()+3000;while(SystemClock.elapsedRealtime()<until){record();Thread.sleep(100);}
      row.put("incidental_answer_count",(int)get("completedTurns")-completed);}
    save();return row;
  }
  public void onCreate(Bundle args){super.onCreate(args);start();}
  public void onStart(){Bundle result=new Bundle();long started=SystemClock.elapsedRealtime();
    try {
      directory=getTargetContext().getExternalFilesDir(null);
      JSONObject fixture=new JSONObject(new String(Files.readAllBytes(new File(directory,"ambient-cooking-case.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
      File video=new File(directory,"P18_03-continuous.mp4");require(hash(Files.readAllBytes(video.toPath())).equals(fixture.getString("sample_sha256")),"sample changed");
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitFor(()->(boolean)get("foreground")&&(boolean)get("loaded")&&!(boolean)get("modelBusy"),90000,"foreground/model readiness");
      main(()->{try{call("endSession",new Class<?>[]{});activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}catch(Exception e){throw new RuntimeException(e);}});
      CookingSession c=(CookingSession)get("cooking");c.start(fixture.getString("request"));c.ambient(true);
      List<String> steps=new ArrayList<>();JSONArray plan=fixture.getJSONArray("steps");for(int i=0;i<plan.length();i++)steps.add(plan.getString(i));
      c.plan(c.request(),steps,c.revision());
      seenChecks=(int)get("taskAttempts");report.put("observations",observations).put("turns",turns)
          .put("scope","original full-duration silent cooking video + paced synthetic PCM through production VAD/ASR/TTS; uncorrected model observations; human acceptance pending")
          .put("gpu_active",get("gpuActive")).put("sample_sha256",fixture.getString("sample_sha256"));
      report.put("stage","replay_start");save();
      MainActivity.parseTaskObservation("{\"phase\":\"unknown\",\"evidence\":\"불확실\"}");
      for(String bad:new String[]{"[]","{\"phase\":true,\"evidence\":\"x\"}","{\"phase\":\"complete\",\"evidence\":\"x\",\"extra\":1}",
          "{\"phase\":\"complete\",\"evidence\":\"x\"} trailing"}) {
        try{MainActivity.parseTaskObservation(bad);throw new AssertionError("invalid observation accepted");}catch(JSONException expected){}
      }
      report.put("parser_boundaries",true);
      main(()->{try{set("sampleVideoUri",Uri.fromFile(video));set("cameraEnabled",true);set("speechEnabled",true);
        call("refreshIndicators",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      waitFor(()->get("preview")!=null&&((android.view.TextureView)get("preview")).isAvailable(),15000,"preview readiness");
      waitFor(()->((MockVoiceInput)get("mockVoice")).ready(),15000,"mock voice readiness");
      report.put("input_voice",((MockVoiceInput)get("mockVoice")).voice()).put("output_voice",((LocalSpeech)get("localSpeech")).voice());
      main(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      waitFor(()->get("capture")!=null&&(boolean)get("listening"),10000,"capture start");
      main(()->{try{((Capture)get("capture")).mockInput.enable();}catch(Exception e){throw new RuntimeException(e);}});
      waitFor(()->get("sampleVideo")!=null&&((SampleVideoInput)get("sampleVideo")).frames>=3,15000,"sample frames");
      at(50000);say("일단 삼십 초 뒤에 알려 줄래?",true);
      say("아 잠깐, 삼십 초 말고 이십 초.",true);
      at(170000);say("잠깐 옆 사람이랑 얘기하고 올게.",true);
      at(190000);say("오늘 저녁에는 치킨 시킬래?",false);
      at(230000);say("돌아왔어. 뭐 하고 있었더라?",true);
      at(285000);say("지금 보이는 작업은 뭐야?",true);
      at(335000);say("잠깐 옆 사람이랑 얘기하고 올게.",true);
      at(345000);say("내일 몇 시에 만날까?",false);
      at(365000);say("돌아왔어. 뭐 하고 있었더라?",true);
      at(427500);record();report.put("final_state",c.context()).put("final_resume",c.recall());
      Capture capture=(Capture)get("capture");report.put("captured_samples",capture.capturedSamples.get())
          .put("monitor_drops",capture.mockMonitorDroppedSamples).put("model_checks",observations.length())
          .put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("result","MEASURED; semantic review required");
      result.putString("result","MEASURED");
    }catch(Throwable e){try{report.put("error",e.toString()).put("last_status",activity==null?"not started":get("lastStatus"));}catch(Exception ignored){}result.putString("result","FAIL");}
    finally {
      if(activity!=null)main(()->{try{call("endSession",new Class<?>[]{});}catch(Exception ignored){}});
      try{save();}catch(Exception ignored){}
      result.putString("report",report.toString());finish(Activity.RESULT_OK,result);
    }
  }
}
