package dev.live.offline;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import org.json.*;

/** Explicit sample-video + paced synthetic voice through production VAD/ASR/VLM/tools/audio. */
public final class VideoScenarioInstrumentation extends VoiceConversationInstrumentation {
  interface Check { boolean ready() throws Exception; }
  private LiveWorkingMemory memory;
  private int rounds=3;
  private long started;
  private final JSONArray results=new JSONArray();
  private File directory;
  private void set(String name,Object value)throws Exception {
    Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);
  }
  public void onCreate(Bundle args) {
    rounds=Integer.parseInt(args.getString("rounds","3"));
    if(rounds<1||rounds>5)throw new IllegalArgumentException("rounds 1..5");
    super.onCreate(args);
  }
  private boolean waitFor(Check check,long limit)throws Exception {
    long until=SystemClock.elapsedRealtime()+limit;
    while(SystemClock.elapsedRealtime()<until){
      if(!(boolean)get("foreground"))throw new IllegalStateException("app left foreground");
      if(check.ready())return true;
      Thread.sleep(20);
    }
    return check.ready();
  }
  private void main(Runnable action){runOnMainSync(action);}
  private void save()throws Exception {
    report.put("elapsed_ms",SystemClock.elapsedRealtime()-started);
    File temp=new File(directory,"video-scenario.tmp");
    try(FileWriter f=new FileWriter(temp)){f.write(report.toString(2));}
    Files.move(temp.toPath(),new File(directory,"video-scenario.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
  }
  private JSONObject say(JSONArray rows,String key,String text,boolean reply)throws Exception {
    JSONObject row=new JSONObject().put("key",key).put("input",text);
    rows.put(row);save();
    Capture capture=(Capture)get("capture");
    require(waitFor(()->!capture.mockInput.feeding()&&!((MockVoiceInput)get("mockVoice")).busy(),15000),"input ready");
    long old=epoch(),prior=(long)get("finalizedAsrTurn"),at=SystemClock.elapsedRealtime();
    long samples=capture.capturedSamples.get();
    SampleVideoInput video=(SampleVideoInput)get("sampleVideo");
    long frames=video==null?0:video.frames;
    row.put("during_playback",call("isVoicePlaying",new Class<?>[]{}));
    main(()->{try{require((boolean)call("sendMockVoice",new Class<?>[]{String.class},text),"send");}
      catch(Exception e){throw new RuntimeException(e);}});
    require(waitFor(()->epoch()!=old,15000),"voice onset");
    row.put("send_to_onset_ms",SystemClock.elapsedRealtime()-at)
        .put("old_pcm_rejected",!activity.onPcm(old,new float[480]))
        .put("detected_to_stop_ms",get("lastBargeInStopMs"));
    final boolean[] deferred={false};
    require(waitFor(()->{
      if(memory.state.snapshot().userActive && memory.state.snapshot().pending>0) {
        require(get("activeFollowup")==null,"timer interrupted active speech"); deferred[0]=true;
      }
      return (long)get("finalizedAsrTurn")!=prior&&!memory.state.snapshot().userActive;
    },30000),"ASR final");
    row.put("timer_deferred_while_user_active",deferred[0]);
    row.put("recognized",memory.state.snapshot().latestInput).put("goal",memory.state.snapshot().goal)
        .put("send_to_final_ms",SystemClock.elapsedRealtime()-at);
    if(reply){
      require(waitFor(()->(long)get("speakingEpoch")==epoch()&&(long)get("firstPlaybackMs")>=0,45000),"answer audio");
      require(waitFor(()->(boolean)call("isVoicePlaying",new Class<?>[]{}),5000),"actual playback");
      row.put("response",get("response")).put("first_text_ms",get("firstTextMs"))
          .put("first_write_ms",get("firstPlaybackMs")).put("response_path",get("lastResponsePath"));
    }
    row.put("capture_samples_during_turn",capture.capturedSamples.get()-samples)
        .put("video_frames_during_turn",video==null?0:video.frames-frames)
        .put("anchor_ms",get("alignedAnchor"));
    if(key.startsWith("visual")) {
      row.put("evidence_at",get("lastEvidenceAt")).put("evidence_source_ms",get("lastEvidenceSourceMs"))
          .put("evidence_source",get("lastEvidenceSource"));
      ObservationMemory.Frame latest=((ObservationMemory)get("memory")).current(SystemClock.elapsedRealtime());
      row.put("latest_source_ms",latest==null?-1:latest.sourceMs);
      List<ObservationMemory.Evidence> evidence=(List<ObservationMemory.Evidence>)get("alignedEvidence");
      if(!evidence.isEmpty()) {
        String name="evidence-"+results.length()+"-"+key+".jpg";
        Files.write(new File(directory,name).toPath(),evidence.get(0).frame.jpeg);
        row.put("evidence_file",name);
      }
    }
    save();return row;
  }
  private void drain(JSONObject row)throws Exception {
    long id=epoch();final String[] answer={(String)get("response")};
    require(waitFor(()->{
      if(epoch()==id)answer[0]=(String)get("response");
      return (long)get("lastDeliveredEpoch")==id || (boolean)call("audioDelivered",new Class<?>[]{long.class},id);
    },45000),"audio drain");
    row.put("completed_response",answer[0]).put("audio_delivered",true);save();
  }
  private void round(int number)throws Exception {
    JSONObject result=new JSONObject().put("round",number);JSONArray rows=new JSONArray();
    results.put(result);result.put("turns",rows);save();
    main(()->{try{
      call("endSession",new Class<?>[]{});
      set("sampleVideoUri",Uri.fromFile(new File(directory,"scenario.mp4")));
      set("cameraEnabled",true);call("startCapture",new Class<?>[]{});
      ((Capture)get("capture")).mockInput.enable();
    }catch(Exception e){throw new RuntimeException(e);}});
    require(waitFor(()->get("sampleVideo")!=null&&((SampleVideoInput)get("sampleVideo")).frames>=3,10000),"sample frames");
    JSONObject first=say(rows,"visual_first","이 동물이 어디 있는지 봐 두고 기억해 줘.",true);
    require((long)get("lastEvidenceAt")>0,"first visual evidence");
    require((long)get("lastEvidenceAt")<=(long)get("alignedAnchor"),"no future image");
    drain(first);
    String retained=((AssistantMemory)get("assistantMemory")).visualContext(SystemClock.elapsedRealtime());
    require(!retained.isEmpty(),"explicit visual memory captured");
    result.put("remembered_visual",retained);
    say(rows,"visual_detail","그 동물 생김새와 배경도 조금 더 설명해 줘.",true);
    JSONObject interrupt=say(rows,"start30","일단 삼십 초 뒤에 알려 줄래?",true);
    result.put("visual_barge_in",interrupt.getBoolean("during_playback")&&interrupt.getBoolean("old_pcm_rejected"));
    say(rows,"change20","아 잠깐, 삼십 초 말고 이십 초.",true);
    require(memory.state.snapshot().goal.equals("20초 타이머"),"natural correction");
    require(memory.state.snapshot().goal.equals("20초 타이머"),"timer changes");
    Field deadlineField=LiveWorkingMemory.class.getDeclaredField("deadline");deadlineField.setAccessible(true);
    long due=deadlineField.getLong(memory);
    require(waitFor(()->SystemClock.elapsedRealtime()>=due-1700,22000),"near timer deadline");
    JSONObject overlap=say(rows,"visual_due","지금 화면은 어떤 모습이야?",true);
    require(overlap.getBoolean("timer_deferred_while_user_active"),"expiry occurred during user speech");
    require(get("activeFollowup")==null,"followup must wait for answer");
    drain(overlap);
    long prior=(long)get("finalizedAsrTurn");
    require(waitFor(()->get("activeFollowup")!=null&&(boolean)call("isVoicePlaying",new Class<?>[]{}),35000),"timer followup");
    result.put("spontaneous_result",prior==(long)get("finalizedAsrTurn")).put("timer_result",get("response"));save();
    say(rows,"hold","아 잠깐만.",false);
    long held=epoch();Thread.sleep(1800);
    require(memory.state.snapshot().speechHeld&&memory.state.snapshot().pending==1&&epoch()==held,"hold pending result");
    result.put("held_without_intrusion",true);
    say(rows,"resume","응, 아까 하던 말 계속해.",true);
    require(waitFor(()->memory.state.snapshot().delivered==1&&memory.state.snapshot().pending==0,30000),"delivered once");
    long done=epoch();Thread.sleep(1500);require(epoch()==done,"no duplicate result");
    result.put("result_delivered_once",true);
    require(waitFor(()->((SampleVideoInput)get("sampleVideo")).positionMs>=92000,70000),"scene transition");
    result.put("memory_after_task_changes",((AssistantMemory)get("assistantMemory")).visualContext(SystemClock.elapsedRealtime()));
    drain(say(rows,"visual_compare","아까 기억해 달라고 한 동물은 어디 있었지? 지금도 거기 있어?",true));
    Capture capture=(Capture)get("capture");long samples=capture.capturedSamples.get();
    main(()->{try{call("stopSampleVideo",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
    drain(say(rows,"visual_recall_off","화면은 껐어. 아까 그 동물이 어디 있었는지 다시 알려 줘.",true));
    drain(say(rows,"visual_off","그럼 지금도 거기 있어?",true));
    require((long)get("lastEvidenceAt")==-1,"video off has no current evidence");
    say(rows,"later_timer","십 초 뒤에 알려 줄래?",true);
    say(rows,"cancel","아냐, 그건 취소해 줘.",true);
    require(memory.state.snapshot().goal.isEmpty()&&memory.state.snapshot().pending==0,"natural cancellation");
    drain(rows.getJSONObject(rows.length()-1));
    long cancelled=epoch();Thread.sleep(11000);
    require(memory.state.snapshot().pending==0&&epoch()==cancelled,"cancelled deadline did not notify");
    result.put("cancelled_timer_stayed_silent",true);
    result.put("capture_continued_without_video",capture.capturedSamples.get()>samples)
        .put("capture_samples",capture.capturedSamples.get())
        .put("monitor_drops",capture.mockMonitorDroppedSamples)
        .put("events",new JSONArray(memory.state.snapshot().events));
    main(()->{try{call("endSession",new Class<?>[]{});set("cameraEnabled",false);
      call("startCapture",new Class<?>[]{});((Capture)get("capture")).mockInput.enable();}
      catch(Exception e){throw new RuntimeException(e);}});
    require(((AssistantMemory)get("assistantMemory")).visualContext(SystemClock.elapsedRealtime()).isEmpty(),"reset visual notes");
    drain(say(rows,"visual_reset","화면은 껐어. 아까 그 동물이 어디 있었는지 다시 알려 줘.",true));
    result.put("status","completed");save();
  }
  public void onStart(){Bundle result=new Bundle();started=SystemClock.elapsedRealtime();
    directory=getTargetContext().getExternalFilesDir(null);
    try {
      report.put("rounds",results).put("planned_rounds",rounds).put("status","running");
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      main(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long ready=SystemClock.elapsedRealtime()+5000;
      while(!(boolean)get("foreground")&&SystemClock.elapsedRealtime()<ready)Thread.sleep(50);
      require(waitFor(()->(boolean)get("loaded")&&!(boolean)get("modelBusy"),90000),"model load");
      require(waitFor(()->((MockVoiceInput)get("mockVoice")).ready(),10000),"mock voice");
      require(waitFor(()->((android.view.TextureView)get("preview")).isAvailable(),10000),"preview surface");
      memory=(LiveWorkingMemory)get("working");
      report.put("input_voice",((MockVoiceInput)get("mockVoice")).voice())
          .put("output_voice",((LocalSpeech)get("localSpeech")).voice()).put("gpu_active",get("gpuActive"));
      for(int i=1;i<=rounds;i++)round(i);
      main(()->{try{call("endSession",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      require(((ObservationMemory)get("memory")).snapshot(SystemClock.elapsedRealtime()).isEmpty(),"reset images");
      report.put("reset_cleared",true).put("status","completed");result.putString("result","MEASUREMENT_COMPLETE");
    }catch(Throwable e){try{report.put("status","failed").put("error",e.toString()).put("last_status",get("lastStatus"));}catch(Exception ignored){}}
    finally {
      try{save();}catch(Exception ignored){}
      if(activity!=null)main(activity::finish);
      finish(result.containsKey("result")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
