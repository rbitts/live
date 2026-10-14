package dev.live.offline;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;
/** Physical model and audio test. Fixture observations are not human kitchen acceptance. */
public final class CookingInstrumentation extends Instrumentation {
  private MainActivity activity;
  private boolean mute;
  private final JSONObject report=new JSONObject();
  private Object get(String key)throws Exception{Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);return f.get(activity);}
  private void set(String key,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);f.set(activity,v);}
  private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
  private void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}
  private void main(Runnable action){runOnMainSync(action);}
  private void idle(long timeout)throws Exception{
    ThreadPoolExecutor worker=(ThreadPoolExecutor)get("inference");long end=SystemClock.elapsedRealtime()+timeout;
    do{Thread.sleep(100);if(worker.getActiveCount()==0&&worker.getQueue().isEmpty()){waitForIdleSync();return;}}while(SystemClock.elapsedRealtime()<end);
    throw new AssertionError("model timeout");
  }
  private void submit(String text)throws Exception{
    main(()->{try{long id=(long)call("invalidate",new Class<?>[]{});call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,text,id,SystemClock.elapsedRealtime());}catch(Exception e){throw new RuntimeException(e);}});
    idle(90000);
  }
  private byte[] spokenCommand()throws Exception{
    LocalSpeech source=(LocalSpeech)get("localSpeech");Field f=LocalSpeech.class.getDeclaredField("engine");f.setAccessible(true);
    android.speech.tts.TextToSpeech engine=(android.speech.tts.TextToSpeech)f.get(source);
    File file=new File(activity.getCacheDir(),"cooking-command.wav");
    main(()->require(engine.synthesizeToFile("다 했어",new Bundle(),file,"test-fixture")==0,"fixture synthesis"));
    try{
      long end=SystemClock.elapsedRealtime()+10000;float[] samples=null;
      while(samples==null&&SystemClock.elapsedRealtime()<end){try{samples=LocalSpeech.readPcm(file);}catch(IOException e){Thread.sleep(50);}}
      require(samples!=null,"fixture WAV completed");ByteArrayOutputStream pcm=new ByteArrayOutputStream();
      for(int i=0;i<samples.length*2/3;i++){int sample=(int)(samples[(int)(i*1.5)]*32767);pcm.write(sample&255);pcm.write((sample>>8)&255);}return pcm.toByteArray();
    }finally{file.delete();}
  }
  private void verifyLongWav()throws Exception{
    int frames=240000;java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(44+frames*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952).putInt(36+frames*2).putInt(0x45564157).putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1);
    b.putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(frames*2);
    for(int i=0;i<frames;i++)b.putShort((short)16384);
    File file=new File(activity.getCacheDir(),"long-voice-test.wav");
    try{Files.write(file.toPath(),b.array());float[] samples=LocalSpeech.readPcm(file);require(samples.length==frames&&samples[frames-1]==.5f,"10 second WAV overflow regression");report.put("long_wav_resample",true);}finally{file.delete();}
  }
  public void onCreate(Bundle args){super.onCreate(args);mute=Boolean.parseBoolean(args.getString("mute","false"));start();}
  public void onStart(){Bundle result=new Bundle();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      main(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      idle(90000);require((boolean)get("loaded"),"model load");report.put("gpu_active",get("gpuActive"));
      main(()->{try{((android.media.AudioTrack)get("track")).setVolume(mute?0f:1f);}catch(Exception e){throw new RuntimeException(e);}});report.put("test_output_muted",mute);verifyLongWav();
      String object="{\"notice\":\"컵을 준비할까요?\"}";
      require(MainActivity.parseCookingNotice(object).equals("컵을 준비할까요?"),"notice object");
      require(MainActivity.parseCookingNotice(JSONObject.quote(object)).equals("컵을 준비할까요?"),"one quoted object");
      for(String invalid:new String[]{"[]","{\"notice\":true}","{\"notice\":\"x\",\"extra\":1}",JSONObject.quote(JSONObject.quote(object))}){
        try{MainActivity.parseCookingNotice(invalid);throw new AssertionError("invalid notice accepted");}catch(JSONException expected){}
      }
      report.put("notice_parser_boundaries",true);
      LocalSpeech voice=(LocalSpeech)get("localSpeech");long deadline=SystemClock.elapsedRealtime()+10000;
      while(!voice.ready()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      report.put("offline_korean_voice_ready",voice.ready());report.put("voice",voice.voice());
      CookingSession cooking=(CookingSession)get("cooking");
      main(()->{try{set("speechEnabled",false);}catch(Exception e){throw new RuntimeException(e);}});
      JSONArray plans=new JSONArray();report.put("plans",plans);
      for(String request:new String[]{"오이와 토마토로 샐러드를 만들고 싶어. 소금은 넣지 마.","달걀과 식용유로 달걀프라이를 만들래. 치즈는 빼줘.","물 한 잔을 준비할래. 컵에 물을 담을 거야."}){
        cooking.start(request);long began=SystemClock.elapsedRealtime();submit(request);
        JSONObject plan=new JSONObject().put("request",request).put("ms",SystemClock.elapsedRealtime()-began).put("state",cooking.context()).put("status",get("lastStatus"));plans.put(plan);
        plan.put("accepted",cooking.ready());
        if(!cooking.ready()){
          require(request.contains("달걀프라이"),"runtime plan: "+get("lastStatus"));
          report.put("egg_plan_rejected",true); // Rejection is not recipe-quality acceptance.
        }
        require(cooking.index()==0,"plan must not complete a step");
      }
      main(()->{try{set("speechEnabled",true);}catch(Exception e){throw new RuntimeException(e);}});
      submit("다 했어");require(cooking.index()==1,"local completion");report.put("control_first_text_ms",get("firstTextMs"));report.put("foreground",get("foreground"));
      deadline=SystemClock.elapsedRealtime()+15000;
      while((long)get("firstPlaybackMs")<0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
      report.put("voice_status",get("lastStatus"));report.put("control_first_pcm_ms",get("firstAudioMs"));report.put("control_first_playback_write_ms",get("firstPlaybackMs"));
      if(voice.ready())require((long)get("firstPlaybackMs")>=0,"offline audio playback");
      boolean amplitude=false;deadline=SystemClock.elapsedRealtime()+8000;
      while(SystemClock.elapsedRealtime()<deadline){
        float level=(float)call("currentVoiceLevel",new Class<?>[]{});amplitude|=level>0.002f;
        if(amplitude)break;Thread.sleep(20);
      }
      report.put("played_amplitude",amplitude);
      if(voice.ready())require(amplitude,"actual output amplitude");
      main(()->{try{call("invalidate",new Class<?>[]{});set("speechEnabled",false);}catch(Exception e){throw new RuntimeException(e);}});
      require((float)call("currentVoiceLevel",new Class<?>[]{})==0,"cancel silences animation");report.put("cancel_silences_animation",true);
      submit("어디까지 했지?");require(((String)get("response")).contains(cooking.current()),"step memory");report.put("recall",get("response"));
      byte[] commandPcm=spokenCommand();int previousStep=cooking.index();
      main(()->{try{long id=(long)call("invalidate",new Class<?>[]{});call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},commandPcm,"",id,SystemClock.elapsedRealtime());}catch(Exception e){throw new RuntimeException(e);}});
      idle(45000);report.put("synthetic_voice_command_response",get("response"));report.put("voice_command_first_text_ms",get("firstTextMs"));
      require(cooking.index()==previousStep+1,"real ASR completion command");
      cooking.start("물 한 잔을 준비할래. 컵에 물을 담을 거야.");submit(cooking.request());require(cooking.ready(),"water runtime plan");
      report.put("water_plan",cooking.context());
      // Real physical camera path is checked separately from the fixture inference.
      main(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      ObservationMemory memory=(ObservationMemory)get("memory");deadline=SystemClock.elapsedRealtime()+8000;
      while(memory.current(SystemClock.elapsedRealtime())==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      require(memory.current(SystemClock.elapsedRealtime())!=null,"physical camera fresh");report.put("physical_camera_fresh",true);
      cooking.command("잠깐");
      Capture capture=(Capture)get("capture");long micBefore=capture.capturedSamples.get();
      main(()->{try{set("speechEnabled",true);call("cookingControl",new Class<?>[]{String.class},"어디까지했지");}catch(Exception e){throw new RuntimeException(e);}});
      deadline=SystemClock.elapsedRealtime()+5000;boolean during=false;
      while(SystemClock.elapsedRealtime()<deadline){if((float)call("currentVoiceLevel",new Class<?>[]{})>0.002f && capture.capturedSamples.get()>micBefore+1600){during=true;break;}Thread.sleep(20);}
      report.put("microphone_voice_status",get("lastStatus"));report.put("microphone_first_pcm_ms",get("firstAudioMs"));report.put("microphone_first_play_ms",get("firstPlaybackMs"));report.put("microphone_during_output",during);report.put("microphone_samples",capture.capturedSamples.get()-micBefore);
      require(during,"microphone continues during actual local voice");
      main(()->{try{set("speechEnabled",false);}catch(Exception e){throw new RuntimeException(e);}});
      cooking.command("계속");
      main(()->{try{call("stopCapture",new Class<?>[]{});set("listening",true);set("cameraEnabled",true);}catch(Exception e){throw new RuntimeException(e);}});
      SceneGate gate=(SceneGate)get("cookingScenes");byte[] jpeg=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"changed-scene.jpg").toPath());
      int before=(int)get("cookingChecks"),step=cooking.index();deadline=SystemClock.elapsedRealtime()+35000;
      while((int)get("cookingChecks")==before&&SystemClock.elapsedRealtime()<deadline){
        long now=SystemClock.elapsedRealtime();memory.observe(jpeg,now);gate.frame(new byte[192],now);
        main(()->{try{call("cookingTick",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(150);
      }
      report.put("observation",get("lastCookingObservation"));report.put("observation_error",get("lastCookingError"));report.put("observation_ms",get("cookingObservationMs"));
      require((int)get("cookingChecks")>before,"real cooking observation");
      require(((String)get("lastCookingError")).isEmpty(),"cooking observation schema");
      String notice=MainActivity.parseCookingNotice((String)get("lastCookingObservation"));
      require(!notice.isEmpty()&&!notice.contains("없")&&(notice.contains("컵")||notice.contains("잔")),"visible cup must yield useful proactive suggestion");
      report.put("proactive_response",get("response"));require(((String)get("response")).contains(notice),"notice shown in chat");require(cooking.index()==step,"vision must not auto complete");
      report.put("vision_keeps_step",true);report.put("scope","physical GPU model and output; controlled image; human kitchen acceptance outstanding");
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}result.putString("result","FAIL");}
    finally{
      if(activity!=null)main(()->{try{call("endSession",new Class<?>[]{});}catch(Exception ignored){}});
      try(FileWriter out=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"cooking-report.json"))){out.write(report.toString(2));}catch(Exception ignored){}
      result.putString("report",report.toString());finish(Activity.RESULT_OK,result);
    }
  }
}
