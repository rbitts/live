package dev.live.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import android.media.AudioTrack;
import java.io.*;
import java.nio.file.Files;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** Repeated real camera-image questions through the production ASR/VLM/TTS owner. */
public final class VisionConversationInstrumentation extends Instrumentation {
  MainActivity activity;
  Object get(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
  Object call(String n,Class<?>[] t,Object...v)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,v);}
  public void onCreate(Bundle b){super.onCreate(b);start();}
  public void onStart(){JSONObject report=new JSONObject();JSONArray runs=new JSONArray();Bundle result=new Bundle();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long end=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      if(!(boolean)get("loaded"))throw new AssertionError("load");
      ObservationMemory memory=(ObservationMemory)get("memory");
      byte[] image=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"changed-scene.jpg").toPath());
      report.put("runs",runs);
      for(int i=0;i<4;i++){
        long at=SystemClock.elapsedRealtime();memory.observe(image,at);
        runOnMainSync(()->{try{long id=(long)call("invalidate",new Class<?>[]{});call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,"보이는 물건을 한 문장으로 말해 줘.",id,at);}catch(Exception e){throw new RuntimeException(e);}});
        ThreadPoolExecutor worker=(ThreadPoolExecutor)get("inference");end=at+30000;
        while((worker.getActiveCount()>0||!worker.getQueue().isEmpty())&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
        if(worker.getActiveCount()>0)throw new AssertionError("vision timeout turn "+i);
        waitForIdleSync();end=SystemClock.elapsedRealtime()+12000;
        while((long)get("firstPlaybackMs")<0&&SystemClock.elapsedRealtime()<end)Thread.sleep(25);
        runs.put(new JSONObject().put("turn",i).put("history",memory.snapshot(SystemClock.elapsedRealtime()).size()).put("first_text_ms",get("firstTextMs")).put("first_audio_write_ms",get("firstPlaybackMs")).put("response",get("response")));
        if((long)get("firstTextMs")<0||(long)get("firstPlaybackMs")<0)throw new AssertionError("answer/audio missing");
        while(SystemClock.elapsedRealtime()<end){AudioTrack track=(AudioTrack)get("track");if((long)get("audioWrittenSamples")>0&&Integer.toUnsignedLong(track.getPlaybackHeadPosition())>=(long)get("audioWrittenSamples"))break;Thread.sleep(25);}
      }
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}}
    finally{
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"vision-conversation.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
