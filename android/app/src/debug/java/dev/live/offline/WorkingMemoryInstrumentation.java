package dev.live.offline;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import dev.live.memory.WorkingMemory;
import java.io.*;
import org.json.*;

/** All user turns use offline synthesis -> paced microphone/VAD -> real Qwen ASR.
 * Never injects an ASR transcript, timer completion, floor event or tool receipt. */
public final class WorkingMemoryInstrumentation extends VoiceConversationInstrumentation {
  interface Condition { boolean ready() throws Exception; }
  private void until(Condition condition,long limit,String message)throws Exception {
    long end=SystemClock.elapsedRealtime()+limit;
    while(!condition.ready()&&SystemClock.elapsedRealtime()<end){
      require((boolean)get("foreground"),"app left foreground");Thread.sleep(20);
    }
    require(condition.ready(),message);
  }
  private LiveWorkingMemory memory()throws Exception{return (LiveWorkingMemory)get("working");}
  private void send(String text)throws Exception {
    Capture capture=(Capture)get("capture");
    until(()->!capture.mockInput.feeding()&&!((MockVoiceInput)get("mockVoice")).busy(),10000,"previous mock still feeding");
    runOnMainSync(()->{try{
      require((boolean)call("sendMockVoice",new Class<?>[]{String.class},text),"mock refused");
    }catch(Exception e){throw new RuntimeException(e);}});
  }
  private long answer(String expected)throws Exception {
    until(()->((String)get("response")).contains(expected)&&(long)get("firstPlaybackMs")>=0
        &&(long)get("speakingEpoch")==epoch()&&(long)get("finalizedAsrTurn")==((StreamingAsr)get("streamingAsr")).current(),45000,"missing spoken answer: "+expected);
    turns.put(new JSONObject().put("caption",get("microphoneTranscript")).put("response",get("response"))
        .put("epoch",epoch()).put("first_text_ms",get("firstTextMs")).put("first_audio_write_ms",get("firstPlaybackMs")));
    return epoch();
  }
  public void onStart(){Bundle result=new Bundle();
    try {
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      until(()->(boolean)get("loaded")&&!(boolean)get("modelBusy"),90000,"model load");
      until(()->((MockVoiceInput)get("mockVoice")).ready(),10000,"offline input TTS");
      require(!(boolean)get("cameraEnabled"),"camera off");
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      Capture capture=(Capture)get("capture");require(capture!=null,"capture missing");
      runOnMainSync(()->capture.mockInput.enable());Thread.sleep(300);
      send("삼십 초 타이머 시작해 줘.");long first=answer("30초 타이머를 시작");
      until(()->(boolean)call("isVoicePlaying",new Class<?>[]{}),5000,"no playback to interrupt");
      long at=SystemClock.elapsedRealtime();send("이십 초 타이머로 바꿔 줘.");
      until(()->epoch()!=first,15000,"replacement VAD onset");
      report.put("replace_send_to_onset_ms",SystemClock.elapsedRealtime()-at);
      require(!activity.onPcm(first,new float[480]),"stale PCM accepted");
      answer("20초 타이머를 시작");
      require(memory().state.snapshot().goal.equals("20초 타이머"),"goal not replaced from ASR");
      require(memory().state.snapshot().actions.get(0).status==WorkingMemory.Status.CANCELLED,"old timer not cancelled");
      long replacementFinal=(long)get("finalizedAsrTurn");
      long receiptWait=SystemClock.elapsedRealtime();
      until(()->get("activeFollowup")!=null&&(boolean)call("isVoicePlaying",new Class<?>[]{}),40000,"result did not re-enter without user input");
      require((long)get("finalizedAsrTurn")==replacementFinal,"unexpected user turn before result");
      require(((String)get("response")).contains("20초 타이머가 끝났어요"),"wrong result");
      report.put("result_wait_ms",SystemClock.elapsedRealtime()-receiptWait).put("first_result",get("response"));
      long resultEpoch=epoch();send("잠깐만.");
      until(()->memory().state.snapshot().speechHeld,20000,"spoken stop did not hold re-entry");
      require(epoch()!=resultEpoch&&!activity.onPcm(resultEpoch,new float[480]),"result interruption failed");
      require(memory().state.snapshot().pending==1&&memory().state.snapshot().delivered==0,"interrupted result marked delivered");
      long held=epoch();Thread.sleep(1800);require(epoch()==held,"held result intruded");
      report.put("held_caption",get("microphoneTranscript")).put("pending_after_interrupt",memory().state.snapshot().pending);
      send("계속해.");answer("이어서 안내할게요");
      until(()->memory().state.snapshot().delivered==1,30000,"resumed result did not finish actual playback");
      require(memory().state.snapshot().pending==0,"result still pending");
      report.put("delivered",memory().state.snapshot().delivered).put("final_result",get("response"));
      long completed=epoch();Thread.sleep(2000);require(epoch()==completed,"duplicate result delivery");
      // Exercise the real VLM with new final-ASR constraints in injected context.
      send("소금은 빼고 샐러드 만드는 방법 알려 줘.");
      until(()->!memory().state.snapshot().constraints.isEmpty(),25000,"exclusion not committed from final ASR");
      until(()->(long)get("firstPlaybackMs")>=0&&(long)get("speakingEpoch")==epoch()
          &&(long)get("finalizedAsrTurn")==((StreamingAsr)get("streamingAsr")).current(),45000,"VLM constraint answer");
      long modelEpoch=epoch();until(()->(boolean)call("audioDelivered",new Class<?>[]{long.class},modelEpoch),30000,"VLM playback drain");
      report.put("constraint_caption",get("microphoneTranscript")).put("constraint_response",get("response"))
          .put("context",memory().state.context()).put("events",new JSONArray(memory().state.snapshot().events))
          .put("capture_samples",capture.capturedSamples.get()).put("turns",turns);
      require((boolean)get("listening")&&capture.capturedSamples.get()>16000*30,"capture did not continue");
      runOnMainSync(()->{try{call("endSession",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      require(memory().state.snapshot().actions.isEmpty()&&memory().state.snapshot().pending==0,"reset leaked state");
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString()).put("caption",get("microphoneTranscript"))
        .put("response",get("response")).put("context",memory().state.context()).put("events",new JSONArray(memory().state.snapshot().events)).put("turns",turns);}catch(Exception ignored){}}
    finally {
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"working-memory.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
