package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;import java.io.*;import org.json.*;
/** Held-out paced speech, including the real word 시초 while a timer is active. */
public final class ContextVoiceInstrumentation extends VoiceConversationInstrumentation {
  public void onStart(){Bundle result=new Bundle();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long until=SystemClock.elapsedRealtime()+90000;while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      require((boolean)get("loaded"),"models");
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});((Capture)get("capture")).mockInput.enable();}catch(Exception e){throw new RuntimeException(e);}});
      until=SystemClock.elapsedRealtime()+10000;while(!((MockVoiceInput)get("mockVoice")).ready()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
      LiveWorkingMemory memory=(LiveWorkingMemory)get("working");report.put("turns",turns);
      for(String question:new String[]{"구십 초 타이머 시작해 줘.","사십 초 타이머로 바꿔 줘.","오십 초 타이머로 바꿔 줘.","이 사건의 시초가 뭐야?"}){
        until=SystemClock.elapsedRealtime()+10000;while((((Capture)get("capture")).mockInput.feeding()||((MockVoiceInput)get("mockVoice")).busy())&&SystemClock.elapsedRealtime()<until)Thread.sleep(25);
        long previous=(long)get("finalizedAsrTurn");String context=memory.recognitionContext();
        runOnMainSync(()->{try{require((boolean)call("sendMockVoice",new Class<?>[]{String.class},question),"send");}catch(Exception e){throw new RuntimeException(e);}});
        until=SystemClock.elapsedRealtime()+25000;
        while(((long)get("finalizedAsrTurn")==previous||memory.state.snapshot().userActive)&&SystemClock.elapsedRealtime()<until)Thread.sleep(25);
        require((long)get("finalizedAsrTurn")!=previous&&!memory.state.snapshot().userActive,"final ASR");
        turns.put(new JSONObject().put("input",question).put("context",context).put("recognized",memory.state.snapshot().latestInput).put("goal",memory.state.snapshot().goal));
      }
      report.put("status","completed");result.putString("result","COMPLETE");
    }catch(Throwable e){try{report.put("error",e.toString());}catch(Exception ignored){}}
    finally{try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"context-voice.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);finish(Activity.RESULT_OK,result);}
  }
}
