package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;import java.io.*;import java.lang.reflect.*;
/** Real VLM checks; explicitly text-input only, not an ASR/voice acceptance test. */
public final class AnswerFixInstrumentation extends VoiceConversationInstrumentation {
  public void onStart(){Bundle result=new Bundle();textOnly=true;
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long until=SystemClock.elapsedRealtime()+90000;while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      require((boolean)get("loaded"),"models");Field f=MainActivity.class.getDeclaredField("speechEnabled");f.setAccessible(true);f.setBoolean(activity,false);
      report.put("input_mode","direct text, real VLM").put("turns",turns);
      for(String question:new String[]{"소금은 빼고 샐러드 만드는 방법 알려 줘.","오이를 씻고 보관하는 방법 알려 줘.","하나 더하기 하나는 얼마야?"}){
        ((LiveWorkingMemory)get("working")).reset();long id=submit(null,question);awaitAudio(id);awaitDrain(id);
      }
      report.put("status","completed");result.putString("result","COMPLETE");
    }catch(Throwable e){try{report.put("error",e.toString());}catch(Exception ignored){}}
    finally{try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"answer-fix.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);finish(Activity.RESULT_OK,result);}
  }
}
