package dev.live.offline;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioTrack;
import android.os.*;
import java.io.*;
import org.json.*;

/** Isolate output from ASR/barge-in: ordinary text requests, real timers and TTS,
 * paced capture held in explicit mock-silence mode. Never intentionally cuts speech. */
public final class OutputContinuityInstrumentation extends VoiceConversationInstrumentation {
  public void onStart(){Bundle result=new Bundle();
    try {
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long end=SystemClock.elapsedRealtime()+90000;
      while((!(boolean)get("loaded")||(boolean)get("modelBusy")||!(boolean)get("foreground"))&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      require((boolean)get("loaded")&&(boolean)get("foreground"),"model/foreground");
      LocalSpeech voice=(LocalSpeech)get("localSpeech");
      end=SystemClock.elapsedRealtime()+10000;while(!voice.ready()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
      require(voice.ready(),"offline voice");report.put("voice",voice.voice());
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      Capture capture=(Capture)get("capture");require(capture!=null,"capture");
      runOnMainSync(()->capture.mockInput.enable());Thread.sleep(300);
      LiveWorkingMemory memory=(LiveWorkingMemory)get("working");
      JSONArray outputs=new JSONArray();
      for(int duration:new int[]{10,20}){
        long id=submit(null,duration+"초 타이머 시작해 줘");
        outputs.put(observe(id,duration+"초 시작"));
        int delivered=memory.state.snapshot().delivered;
        end=SystemClock.elapsedRealtime()+(duration+15)*1000L;
        while(get("activeFollowup")==null&&SystemClock.elapsedRealtime()<end)Thread.sleep(10);
        require(get("activeFollowup")!=null,"no timer result");
        outputs.put(observe(epoch(),duration+"초 완료"));
        end=SystemClock.elapsedRealtime()+2000;
        while(memory.state.snapshot().delivered==delivered&&SystemClock.elapsedRealtime()<end)Thread.sleep(10);
        require(memory.state.snapshot().delivered==delivered+1,"missing delivery acknowledgement");
      }
      report.put("outputs",outputs).put("capture_samples",capture.capturedSamples.get()).put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString()).put("response",get("response"));}catch(Exception ignored){}}
    finally {
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"output-continuity.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
  private JSONObject observe(long id,String label)throws Exception {
    AudioTrack track=(AudioTrack)get("track");int underruns=track.getUnderrunCount();
    long end=SystemClock.elapsedRealtime()+20000,previous=0,lastMove=0,maxStall=0,firstHead=-1;
    while(SystemClock.elapsedRealtime()<end){
      require((boolean)get("foreground")&&epoch()==id,"unexpected output cancellation: "+label);
      long now=SystemClock.elapsedRealtime(),head=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
      if(head>0&&firstHead<0)firstHead=now;
      if(head!=previous){if(lastMove!=0&&previous>0)maxStall=Math.max(maxStall,now-lastMove);lastMove=now;previous=head;}
      if((boolean)call("audioDelivered",new Class<?>[]{long.class},id))break;
      Thread.sleep(10);
    }
    require((boolean)call("audioDelivered",new Class<?>[]{long.class},id),"audio incomplete: "+label);
    int starvations=(int)get("audioStarvations");require(starvations==0,"producer starvation: "+label);
    return new JSONObject().put("label",label).put("epoch",id).put("response",get("response"))
        .put("samples_queued",get("audioQueuedSamples")).put("samples_written",get("audioWrittenSamples"))
        .put("head",Integer.toUnsignedLong(track.getPlaybackHeadPosition())).put("starvations",starvations)
        .put("track_underruns_including_tail",track.getUnderrunCount()-underruns)
        .put("max_head_update_interval_ms",maxStall).put("first_write_ms",get("firstPlaybackMs"));
  }
}
