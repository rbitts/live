package dev.live.offline;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import dev.live.memory.WorkingMemory;
import java.io.*;
import java.nio.file.*;
import org.json.*;

/** Fixed repeated evaluation. No production state patches, transcript injection,
 * automatic corrections, or early exit on a semantic/recognition failure. */
public final class RepeatedVoiceInstrumentation extends VoiceConversationInstrumentation {
  interface Check {boolean ready() throws Exception;}
  private final JSONArray rounds=new JSONArray();
  private LiveWorkingMemory memory;
  private long started;
  private int repeatCount=5;
  @Override public void onCreate(Bundle args){
    repeatCount=Integer.parseInt(args.getString("rounds","5"));
    if(repeatCount<1||repeatCount>5)throw new IllegalArgumentException("rounds 1..5");
    super.onCreate(args);
  }

  private boolean waitFor(Check check,long limit)throws Exception {
    long until=SystemClock.elapsedRealtime()+limit;
    while(SystemClock.elapsedRealtime()<until){
      if(!(boolean)get("foreground"))throw new IllegalStateException("app left foreground");
      if(memory!=null&&get("capture")==null)throw new IllegalStateException("capture stopped: "+get("lastStatus"));
      if(check.ready())return true;Thread.sleep(20);
    }
    return check.ready();
  }
  private void save()throws Exception {
    report.put("elapsed_ms",SystemClock.elapsedRealtime()-started);
    File dest=new File(getTargetContext().getExternalFilesDir(null),"repeated-voice.json");
    File temp=new File(dest.getParentFile(),"repeated-voice.tmp");
    try(FileWriter f=new FileWriter(temp)){f.write(report.toString(2));}
    Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING);
  }
  private JSONObject turn(JSONArray entries,String key,String text,boolean reply)throws Exception {
    JSONObject row=new JSONObject().put("key",key).put("input",text);
    entries.put(row);save();
    Capture capture=(Capture)get("capture");
    if(!waitFor(()->!capture.mockInput.feeding()&&!((MockVoiceInput)get("mockVoice")).busy(),12000)){
      row.put("error","mock input busy");save();return row;
    }
    long previous=epoch(),priorFinal=(long)get("finalizedAsrTurn"),at=SystemClock.elapsedRealtime();
    row.put("during_playback",call("isVoicePlaying",new Class<?>[]{}));
    final boolean[] sent={false};
    runOnMainSync(()->{try{sent[0]=(boolean)call("sendMockVoice",new Class<?>[]{String.class},text);}
      catch(Exception e){throw new RuntimeException(e);}});
    row.put("sent",sent[0]);
    if(!sent[0]){row.put("error","send refused");save();return row;}
    boolean onset=waitFor(()->epoch()!=previous,15000);
    row.put("onset",onset).put("send_to_onset_ms",SystemClock.elapsedRealtime()-at);
    if(onset)row.put("old_pcm_rejected",!activity.onPcm(previous,new float[480]));
    boolean finalized=waitFor(()->(long)get("finalizedAsrTurn")!=priorFinal
        &&(long)get("finalizedAsrTurn")==((StreamingAsr)get("streamingAsr")).current()
        &&!memory.state.snapshot().userActive,25000);
    row.put("asr_final",finalized).put("send_to_final_ms",SystemClock.elapsedRealtime()-at);
    if(!finalized){row.put("error","final ASR timeout").put("caption",get("microphoneTranscript"));save();return row;}
    row.put("recognized",memory.state.snapshot().latestInput).put("goal",memory.state.snapshot().goal)
        .put("constraints",new JSONArray(memory.state.snapshot().constraints));
    if(reply){
      boolean audible=waitFor(()->(long)get("speakingEpoch")==epoch()&&(long)get("firstPlaybackMs")>=0,45000);
      row.put("audio_started",audible).put("epoch",epoch()).put("response",get("response"))
          .put("first_text_ms",get("firstTextMs")).put("first_write_ms",get("firstPlaybackMs"));
      if(audible)row.put("playing",waitFor(()->(boolean)call("isVoicePlaying",new Class<?>[]{}),4000));
    }
    save();return row;
  }
  private void round(int number)throws Exception {
    runOnMainSync(()->{try{
      call("endSession",new Class<?>[]{});call("startCapture",new Class<?>[]{});
      activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      ((Capture)get("capture")).mockInput.enable();
    }catch(Exception e){throw new RuntimeException(e);}});
    Thread.sleep(300);
    JSONObject round=new JSONObject().put("round",number).put("status","running");
    JSONArray entries=new JSONArray();round.put("turns",entries);rounds.put(round);save();
    turn(entries,"start30","삼십 초 타이머 시작해 줘.",true);
    turn(entries,"change10","십 초 타이머로 바꿔 줘.",true);
    turn(entries,"change20","이십 초 타이머로 바꿔 줘.",true);
    round.put("changed_to20",memory.state.snapshot().goal.equals("20초 타이머"));
    boolean oldCancelled=true;
    for(WorkingMemory.Action action:memory.state.snapshot().actions)
      if(action.input.equals("30초")&&action.status!=WorkingMemory.Status.CANCELLED)oldCancelled=false;
    round.put("old_timer_cancelled",oldCancelled);
    long lastFinal=(long)get("finalizedAsrTurn"),waitStart=SystemClock.elapsedRealtime();
    boolean returned=waitFor(()->get("activeFollowup")!=null&&(boolean)call("isVoicePlaying",new Class<?>[]{}),35000);
    round.put("spontaneous_result",returned&&(long)get("finalizedAsrTurn")==lastFinal)
        .put("result_wait_ms",SystemClock.elapsedRealtime()-waitStart).put("first_result",get("response"));save();
    turn(entries,"stop","잠깐만.",false);
    long heldEpoch=epoch();Thread.sleep(1800);
    round.put("held",memory.state.snapshot().speechHeld).put("pending_after_stop",memory.state.snapshot().pending)
        .put("delivered_after_stop",memory.state.snapshot().delivered).put("no_intrusion_while_held",epoch()==heldEpoch);
    turn(entries,"resume","계속해.",true);
    boolean delivered=waitFor(()->memory.state.snapshot().delivered==1&&memory.state.snapshot().pending==0,30000);
    round.put("result_delivered_once",delivered).put("resumed_result",get("response"));
    long after=epoch();Thread.sleep(1800);round.put("no_duplicate",epoch()==after);save();
    JSONObject recipe=turn(entries,"recipe","소금은 빼고 샐러드 만드는 방법 알려 줘.",true);
    long answerEpoch=epoch();
    boolean drained=waitFor(()->(boolean)call("audioDelivered",new Class<?>[]{long.class},answerEpoch),30000);
    recipe.put("audio_delivered",drained).put("response",get("response"))
        .put("producer_starvations",get("audioStarvations"));
    round.put("context",memory.state.context()).put("events",new JSONArray(memory.state.snapshot().events))
        .put("capture_samples",((Capture)get("capture")).capturedSamples.get())
        .put("mock_monitor_samples",((Capture)get("capture")).mockMonitorSamples)
        .put("mock_monitor_played",((Capture)get("capture")).mockMonitorPlayedSamples)
        .put("mock_monitor_dropped",((Capture)get("capture")).mockMonitorDroppedSamples)
        .put("mock_monitor_route",((Capture)get("capture")).mockMonitorRouteType).put("status","completed");
    save();
  }
  public void onStart(){Bundle result=new Bundle();started=SystemClock.elapsedRealtime();
    try {
      report.put("schema",1).put("planned_rounds",repeatCount).put("rounds",rounds).put("status","running");
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      // Foreground callback may arrive just after startActivitySync returns.
      long until=SystemClock.elapsedRealtime()+5000;while(!(boolean)get("foreground")&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
      require(waitFor(()->(boolean)get("loaded")&&!(boolean)get("modelBusy"),90000),"model load");
      require(waitFor(()->((MockVoiceInput)get("mockVoice")).ready(),10000),"offline mock voice");
      require(!(boolean)get("cameraEnabled"),"camera off");
      report.put("output_voice",((LocalSpeech)get("localSpeech")).voice()).put("input_voice",((MockVoiceInput)get("mockVoice")).voice());
      memory=(LiveWorkingMemory)get("working");
      for(int i=1;i<=repeatCount;i++)round(i);
      report.put("status","completed");result.putString("result","MEASUREMENT_COMPLETE");
    }catch(Throwable e){try{report.put("status","aborted").put("error",e.toString()).put("last_status",get("lastStatus")).put("foreground",get("foreground")).put("listening",get("listening"));}catch(Exception ignored){}}
    finally {
      try{save();}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("MEASUREMENT_COMPLETE".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
