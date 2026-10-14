package dev.live.offline;

import android.app.*;
import android.content.*;
import android.os.*;
import android.media.AudioTrack;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Real synthesis through the production player; optional audible route check, no sensors. */
public final class SpeechLatencyInstrumentation extends Instrumentation {
  private MainActivity activity;
  private Bundle args;
  private Object get(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
  private void set(String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,v);}
  private Object call(String n,Class<?>[] types,Object...v)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,v);}
  private void require(boolean b,String s){if(!b)throw new AssertionError(s);}
  public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
  public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();JSONArray runs=new JSONArray();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long deadline=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      require((boolean)get("loaded"),"load");
      LocalSpeech local=(LocalSpeech)get("localSpeech");deadline=SystemClock.elapsedRealtime()+10000;
      while(!local.ready()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
      require(local.ready(),"offline voice");
      boolean muted=Boolean.parseBoolean(args.getString("mute","true"));
      runOnMainSync(()->{try{((AudioTrack)get("track")).setVolume(muted?0f:1f);set("speechEnabled",true);require((boolean)call("acquireAudioFocus",new Class<?>[]{}),"audio focus");}catch(Exception e){throw new RuntimeException(e);}});
      report.put("muted",muted).put("voice",local.voice()).put("runs",runs);
      for(String backend:new String[]{"qwen","local","general"})for(String text:new String[]{"컵을 준비해 주세요.","오이와 토마토를 씻어 주세요. 먹기 좋은 크기로 자른 뒤 그릇에 담아 주세요."}){
        if(!args.getString("backend","all").equals("all")&&!args.getString("backend").equals(backend))continue;
        AtomicLong idValue=new AtomicLong();runOnMainSync(()->{try{long id=(long)call("invalidate",new Class<?>[]{});idValue.set(id);call("resetOutput",new Class<?>[]{long.class},id);}catch(Exception e){throw new RuntimeException(e);}});
        long id=idValue.get(),start=SystemClock.elapsedRealtime();AtomicLong generation=new AtomicLong(-1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=null;
        if(backend.equals("qwen")){
          worker=new Thread(()->{try{call("nativeSpeak",new Class<?>[]{long.class,String.class,long.class},get("handle"),text,id);generation.set(SystemClock.elapsedRealtime()-start);set("audioDoneEpoch",id);}catch(Throwable e){error.set(e);}});worker.start();
        }else if(backend.equals("general"))runOnMainSync(()->{try{call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},null,"한 문장으로 인사해 줘.",id,start);}catch(Exception e){throw new RuntimeException(e);}});
        else runOnMainSync(()->{try{call("speakCooking",new Class<?>[]{long.class,String.class},id,text);}catch(Exception e){throw new RuntimeException(e);}});
        long firstHead=-1,firstNonzero=-1;int routeType=0,routeId=0;deadline=start+45000;
        while(SystemClock.elapsedRealtime()<deadline){
          if(error.get()!=null)throw new RuntimeException(error.get());
          AudioTrack track=(AudioTrack)get("track");long head=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
          AudioDeviceInfo routed=track.getRoutedDevice();
          if(routed!=null&&track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING&&head>0){routeType=routed.getType();routeId=routed.getId();}
          if(head>0&&firstHead<0)firstHead=SystemClock.elapsedRealtime()-start;
          if((float)call("currentVoiceLevel",new Class<?>[]{})>0.002f&&firstNonzero<0)firstNonzero=SystemClock.elapsedRealtime()-start;
          if((long)get("audioDoneEpoch")==id && (long)get("audioWrittenSamples")>0 && head>=(long)get("audioWrittenSamples") && ((ArrayBlockingQueue<?>)get("playback")).isEmpty())break;
          Thread.sleep(10);
        }
        if(worker!=null)worker.join(1000);
        JSONArray chunks=new JSONArray();long samples=0;Object list=get("audioChunks");
        synchronized(list){for(long[] chunk:(ArrayList<long[]>)list){chunks.put(new JSONArray().put(chunk[0]).put(chunk[1]));samples+=chunk[1];}}
        long synth=backend.equals("qwen")?generation.get():local.synthesisMs;
        runs.put(new JSONObject().put("backend",backend).put("text",text).put("synthesis_ms",synth)
          .put("first_engine_audio_ms",backend.equals("qwen")?JSONObject.NULL:local.firstEngineAudioMs)
          .put("audio_seconds",samples/24000.0).put("generation_rtf",synth/(samples/24.0))
          .put("first_pcm_ms",get("firstAudioMs")).put("first_write_ms",get("firstPlaybackMs"))
          .put("first_text_ms",get("firstTextMs")).put("response",get("response")).put("local_speech_epoch",get("localSpeechEpoch"))
          .put("first_head_ms",firstHead).put("first_nonzero_meter_ms",firstNonzero)
          .put("routed_device_type",routeType).put("routed_device_id",routeId)
          .put("voice_call_volume",((AudioManager)activity.getSystemService(Context.AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_VOICE_CALL))
          .put("buffer_ms",(long)get("firstPlaybackMs")-(long)get("firstAudioMs"))
          .put("collect_all",get("audioFullyBuffered")).put("starvations",get("audioStarvations"))
          .put("status",get("lastStatus")).put("foreground",get("foreground")).put("done_epoch",get("audioDoneEpoch")).put("epoch",((AtomicLong)get("epoch")).get())
          .put("producer_waits",get("audioProducerWaits")).put("chunks_ms_samples",chunks));
        require((long)get("audioDoneEpoch")==id&&firstNonzero>=0,"speech completion/PCM");
        if(Boolean.parseBoolean(args.getString("require_headset","false")))
          require(routeType==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||routeType==AudioDeviceInfo.TYPE_BLE_HEADSET,"actual Bluetooth playback route");
        if(backend.equals("general")){require((long)get("localSpeechEpoch")==id,"general route must use local voice");break;}
      }
      require(!(boolean)get("listening"),"sensors must stay off");report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("error",e.toString()).put("result","FAIL");}catch(Exception ignored){}}
    finally{
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"speech-latency-"+args.getString("label","run")+".json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
