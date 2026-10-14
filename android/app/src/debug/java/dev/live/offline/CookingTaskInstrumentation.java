package dev.live.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real model checkpoint diagnostic. Labels stay in the evaluator case file. */
public final class CookingTaskInstrumentation extends Instrumentation {
  private MainActivity activity;
  private final JSONObject report=new JSONObject();
  private File directory;
  private Object get(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
  private Object call(String name,Class<?>[] types,Object...args)throws Exception {
    Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);
  }
  private void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  private String hash(byte[] bytes)throws Exception {
    StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b));return s.toString();
  }
  private void save()throws Exception {try(FileWriter out=new FileWriter(new File(directory,"task-checkpoints-results.json"))){out.write(report.toString(2));}}
  public void onCreate(Bundle args){super.onCreate(args);start();}
  public void onStart(){Bundle result=new Bundle();
    try {
      directory=getTargetContext().getExternalFilesDir(null);
      JSONObject fixture=new JSONObject(new String(Files.readAllBytes(new File(directory,"task-checkpoints.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long until=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded") && SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      require((boolean)get("loaded"),"models not loaded");
      runOnMainSync(()->{try{call("endSession",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      report.put("scope","real model, chronological checkpoint images; no paced video/audio or human acceptance");
      report.put("observer_mode","native model output; no domain-rule correction");
      report.put("gpu_active",get("gpuActive")).put("app_version",getTargetContext().getPackageManager().getPackageInfo("dev.live.offline",0).versionName);
      report.put("case_sha256",hash(Files.readAllBytes(new File(directory,"task-checkpoints.json").toPath())));
      JSONArray groups=fixture.getJSONArray("groups"),results=new JSONArray();report.put("groups",results);
      for(int g=0;g<groups.length();g++) {
        JSONObject group=groups.getJSONObject(g),out=new JSONObject();results.put(out);
        CookingSession cooking=(CookingSession)get("cooking");
        cooking.start(fixture.getString("request"));cooking.ambient(true);
        List<String> steps=new ArrayList<>();JSONArray plan=fixture.getJSONArray("steps");
        for(int i=0;i<plan.length();i++)steps.add(plan.getString(i));
        cooking.plan(cooking.request(),steps,cooking.revision());
        int initial=group.getInt("initial_step");
        for(int i=0;i<initial;i++)cooking.command("다 했어");
        out.put("initial_state",cooking.context()).put("setup","Prior steps explicitly confirmed in fixture setup; not visual acceptance.");
        JSONArray frames=group.getJSONArray("frames"),rows=new JSONArray();out.put("observations",rows);
        for(int i=0;i<frames.length();i++) {
          JSONObject input=frames.getJSONObject(i),row=new JSONObject();rows.put(row);
          File file=new File(directory,input.getString("file"));byte[] jpeg=Files.readAllBytes(file.toPath());
          String key=hash(jpeg);require(key.equals(input.getString("sha256")),"frame differs from frozen case");
          long sourceMs=input.getLong("source_ms"),at=SystemClock.elapsedRealtime();
          int revision=cooking.revision(),before=cooking.index();
          long epoch=((java.util.concurrent.atomic.AtomicLong)get("epoch")).get(),handle=(long)get("handle");
          String context=cooking.observationContext();
          Future<String> inference=((ThreadPoolExecutor)get("inference")).submit(()->
              (String)call("nativeTaskObserve",new Class<?>[]{long.class,String.class,String.class,String.class,long.class},
                  handle,cooking.request(),context,file.getAbsolutePath(),epoch));
          String raw=inference.get(60,TimeUnit.SECONDS);long finished=SystemClock.elapsedRealtime();
          row.put("source_ms",sourceMs).put("file",input.getString("file")).put("sha256",key)
              .put("step_before",before).put("elapsed_ms",finished-at).put("raw",raw);
          try {
            JSONObject observation=MainActivity.parseTaskObservation(raw);
            boolean advanced=cooking.observed(observation.getString("phase"),observation.getString("evidence"),key,
                "epic-checkpoint",sourceMs,at,finished,revision);
            row.put("parsed",observation).put("advanced",advanced);
          } catch(Exception e){row.put("parse_error",e.toString());}
          row.put("step_after",cooking.index()).put("pending_guidance",cooking.pendingGuidance());save();
        }
        out.put("resume_answer",cooking.command("돌아왔어. 뭐 하고 있었더라?"));
        out.put("final_state",cooking.context());save();
      }
      report.put("result","MEASURED; semantic review required");result.putString("result","MEASURED");
    } catch(Throwable e) {try{report.put("error",e.toString());}catch(Exception ignored){}result.putString("result","FAIL");}
    finally {
      if(activity!=null)runOnMainSync(()->{try{call("endSession",new Class<?>[]{});}catch(Exception ignored){}});
      try{save();}catch(Exception ignored){}
      result.putString("report",report.toString());finish(Activity.RESULT_OK,result);
    }
  }
}
