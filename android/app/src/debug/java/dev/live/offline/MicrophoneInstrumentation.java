package dev.live.offline;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Opt-in short human microphone diagnostic. Raw audio is deleted before finishing. */
public final class MicrophoneInstrumentation extends Instrumentation {
  private MainActivity activity;
  private Bundle args;
  private Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
  private Object call(String name,Class<?>[] types,Object...values)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,values);}
  public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
  public void onStart(){
    JSONObject report=new JSONObject();Bundle result=new Bundle();Capture capture=null;File wav=null;
    ByteArrayOutputStream raw=new ByteArrayOutputStream(640000);
    List<byte[]> segments=new ArrayList<>();List<Double> levels=new ArrayList<>();
    AtomicLong starts=new AtomicLong();List<String> errors=new ArrayList<>();
    File control=new File(getTargetContext().getExternalFilesDir(null),"mic-diagnostic-start");
    File ready=new File(getTargetContext().getExternalFilesDir(null),"mic-diagnostic-ready");
    try{
      if(Build.VERSION.SDK_INT<31)throw new AssertionError("headset diagnostic requires API 31+");
      control.delete();ready.delete();
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long deadline=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      if(!(boolean)get("loaded"))throw new AssertionError("load");
      runOnMainSync(()->{try{if(!(boolean)call("acquireAudioFocus",new Class<?>[]{}))throw new AssertionError("focus");}catch(Exception e){throw new RuntimeException(e);}});
      AudioManager manager=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
      deadline=SystemClock.elapsedRealtime()+5000;
      while(SystemClock.elapsedRealtime()<deadline){AudioDeviceInfo d=manager.getCommunicationDevice();if(d!=null&&d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO)break;Thread.sleep(50);}
      try(FileWriter f=new FileWriter(ready)){f.write("ready");}
      deadline=SystemClock.elapsedRealtime()+180000;
      while(!control.exists()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
      if(!control.exists())throw new AssertionError("start signal timeout");
      capture=new Capture(new Capture.Listener(){
        public void frame(byte[] jpeg,long at){}
        public void speechStart(){starts.incrementAndGet();}
        public void utterance(byte[] pcm,long at){if(segments.size()<20)segments.add(pcm);}
        public void error(String message){errors.add(message);}
        public void samples(short[] pcm,int count){
          double sum=0;
          for(int i=0;i<count;i++){sum+=(double)pcm[i]*pcm[i];if(raw.size()<640000){raw.write(pcm[i]&255);raw.write((pcm[i]>>8)&255);}}
          if(levels.size()<1000)levels.add(Math.sqrt(sum/count)/32768.0);
        }
      });
      capture.start(activity,null,false);
      Thread.sleep(15000);
      Field rec=Capture.class.getDeclaredField("recorder");rec.setAccessible(true);AudioRecord recorder=(AudioRecord)rec.get(capture);
      AudioDeviceInfo input=recorder.getRoutedDevice();
      report.put("input_device_type",input==null?0:input.getType()).put("sample_rate",recorder.getSampleRate());
      capture.close();capture=null;
      report.put("seconds",raw.size()/32000.0).put("speech_starts",starts.get()).put("segment_count",segments.size()).put("errors",new JSONArray(errors));
      JSONArray rms=new JSONArray();for(double d:levels)rms.put(d);report.put("rms_20ms",rms);
      long epoch=((AtomicLong)get("epoch")).get();
      List<byte[]> inputs=new ArrayList<>();inputs.add(raw.toByteArray());inputs.addAll(segments);
      JSONArray transcriptions=new JSONArray();report.put("transcriptions",transcriptions);
      for(int i=0;i<inputs.size();i++){
        wav=new File((File)get("sessionCache"),"mic-diagnostic.wav");
        call("writeWav",new Class<?>[]{File.class,byte[].class},wav,inputs.get(i));
        String text=(String)call("nativeTranscribe",new Class<?>[]{long.class,String.class,long.class},get("handle"),wav.getAbsolutePath(),epoch);
        transcriptions.put(new JSONObject().put("kind",i==0?"full_window":"vad_segment").put("seconds",inputs.get(i).length/32000.0).put("text",text));
        if(i>0){
          Field turn=MainActivity.class.getDeclaredField("microphoneTurnEpoch");turn.setAccessible(true);turn.setLong(activity,epoch);
          runOnMainSync(()->{try{call("showMicrophoneTranscript",new Class<?>[]{long.class,String.class},epoch,text);}catch(Exception e){throw new RuntimeException(e);}});
          String shown=((android.widget.TextView)get("microphoneText")).getText().toString();
          if(!shown.equals(text.trim()))throw new AssertionError("ASR caption mismatch");
          runOnMainSync(()->{try{call("showMicrophoneTranscript",new Class<?>[]{long.class,String.class},epoch-1,"stale");}catch(Exception e){throw new RuntimeException(e);}});
          if(!((android.widget.TextView)get("microphoneText")).getText().toString().equals(shown))throw new AssertionError("stale ASR caption");
          report.put("caption_verified",true);
        }
        wav.delete();wav=null;
      }
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("error",e.toString()).put("result","FAIL");}catch(Exception ignored){}}
    finally{
      if(capture!=null)capture.close();if(wav!=null)wav.delete();control.delete();ready.delete();
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"mic-diagnostic-"+args.getString("label","run")+".json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
