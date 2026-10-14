package dev.live.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;

/** Real on-device model; controlled frames are NOT human Live acceptance. */
public final class ProactiveInstrumentation extends Instrumentation {
  private MainActivity activity;
  private final JSONObject report=new JSONObject();
  private final JSONArray observations=new JSONArray();
  public void onCreate(Bundle args){super.onCreate(args);start();}
  private Object get(String name)throws Exception{
    Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
  }
  private void set(String name,Object value)throws Exception{
    Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);
  }
  private Object call(String name,Class<?>[] types,Object... args)throws Exception{
    Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);
  }
  private void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private void idle(long timeout)throws Exception{
    long end=SystemClock.elapsedRealtime()+timeout;
    ThreadPoolExecutor executor=(ThreadPoolExecutor)get("inference");
    do{Thread.sleep(100);if(executor.getActiveCount()==0&&executor.getQueue().isEmpty()){waitForIdleSync();return;}}
    while(SystemClock.elapsedRealtime()<end);
    throw new AssertionError("inference timeout");
  }
  private void checks(byte[] jpeg,int count)throws Exception{
    int start=(int)get("proactiveChecks"),seen=start;
    long end=SystemClock.elapsedRealtime()+65000;
    ObservationMemory memory=(ObservationMemory)get("memory");
    while(SystemClock.elapsedRealtime()<end&&(int)get("proactiveChecks")<start+count){
      memory.observe(jpeg,SystemClock.elapsedRealtime());
      runOnMainSync(()->{try{call("proactiveTick",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      Thread.sleep(200);
      int current=(int)get("proactiveChecks");
      if(current>seen){observations.put(new JSONObject((String)get("lastObservation")));seen=current;}
    }
    require((int)get("proactiveChecks")>=start+count,"observation timeout: "+get("lastProactiveError")+"; raw="+get("lastObservation"));
  }
  public void onStart(){
    Bundle result=new Bundle();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      idle(90000);require((boolean)get("loaded"),"model loaded");
      report.put("gpu_active",get("gpuActive"));
      runOnMainSync(()->{try{set("speechEnabled",false);call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      long deadline=SystemClock.elapsedRealtime()+8000;
      ObservationMemory memory=(ObservationMemory)get("memory");
      while(memory.current(SystemClock.elapsedRealtime())==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      require(memory.current(SystemClock.elapsedRealtime())!=null,"physical camera fresh frame");
      report.put("physical_camera_fresh_frame",true);
      runOnMainSync(()->{try{call("stopCapture",new Class<?>[]{});set("listening",true);set("speechEnabled",false);}catch(Exception e){throw new RuntimeException(e);}});
      AssistantMemory assistant=(AssistantMemory)get("assistantMemory");assistant.configure(true,300000);
      byte[] jpeg=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"scene.jpg").toPath());
      assistant.watch("보라색 코끼리가 보이면 알려줘",SystemClock.elapsedRealtime());
      checks(jpeg,2);require((int)get("proactiveNotices")==0,"negative condition must stay silent");
      report.put("negative_condition_silent",true);
      assistant.watch("컵이 보이면 알려줘",SystemClock.elapsedRealtime());
      long began=SystemClock.elapsedRealtime();checks(jpeg,2);
      require((int)get("proactiveNotices")==1,"matched task must alert once");
      report.put("condition_to_notice_ms",SystemClock.elapsedRealtime()-began);report.put("notice",get("response"));
      checks(jpeg,1);require((int)get("proactiveNotices")==1,"duplicate task alert");report.put("duplicate_suppressed",true);
      runOnMainSync(()->{try{set("listening",false);memory.cameraOff();long id=(long)call("invalidate",new Class<?>[]{});
        call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,"내가 무엇을 보면 알려달라고 부탁했지?",id,SystemClock.elapsedRealtime());
      }catch(Exception e){throw new RuntimeException(e);}});
      idle(45000);String answer=(String)get("response");report.put("memory_recall_without_camera",answer);
      require(answer.contains("컵"),"task recall without image");
      assistant.clear(); assistant.watch("",SystemClock.elapsedRealtime());
      runOnMainSync(()->{try{set("listening",true);}catch(Exception e){throw new RuntimeException(e);}});
      int before=(int)get("proactiveNotices");
      checks(jpeg,1);require((int)get("proactiveNotices")==before,"scene baseline silent");
      byte[] changed=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"changed-scene.jpg").toPath());
      checks(changed,1);require((int)get("proactiveNotices")==before+1,"scene change must alert");
      report.put("scene_change_notice",get("response"));
      report.put("scene_memory",assistant.context(SystemClock.elapsedRealtime()));
      require(assistant.size(SystemClock.elapsedRealtime())>=2,"old and new observations remembered");
      runOnMainSync(()->{try{set("listening",false);memory.cameraOff();long id=(long)call("invalidate",new Class<?>[]{});
        call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,"나는 파란색 컵을 써. 기억해줘.",id,SystemClock.elapsedRealtime());
      }catch(Exception e){throw new RuntimeException(e);}});
      idle(45000);
      runOnMainSync(()->{try{long id=(long)call("invalidate",new Class<?>[]{});
        call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,"내 컵은 무슨 색이지?",id,SystemClock.elapsedRealtime());
      }catch(Exception e){throw new RuntimeException(e);}});
      idle(45000);report.put("conversation_recall",get("response"));
      require(((String)get("response")).contains("파란"),"conversation memory is used by real model");
      report.put("observations",observations);
      report.put("scope","SM-X926N real model; physical capture separately checked; automatic loop driven by fixed scene; not human acceptance");
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){
      try{report.put("result","FAIL");report.put("error",e.toString());report.put("observations",observations);}catch(Exception ignored){}
      result.putString("result","FAIL");
    }finally{
      if(activity!=null)runOnMainSync(()->{try{call("endSession",new Class<?>[]{});}catch(Exception ignored){}});
      result.putString("report",report.toString());
      try(FileWriter out=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"proactive-report.json"))){out.write(report.toString(2));}catch(Exception ignored){}
      finish(Activity.RESULT_OK,result);
    }
  }
}
