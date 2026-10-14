package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.lang.reflect.*;import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
/** Explicit one-utterance comparison. Acknowledgement or timeout deletes the diagnostic WAV. */
public final class AsrReferenceInstrumentation extends VoiceConversationInstrumentation {
  void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
  public void onStart(){Bundle result=new Bundle();File dir=getTargetContext().getExternalFilesDir(null);
    File ready=new File(dir,"asr-reference-ready"),wav=new File(dir,"asr-reference.wav"),ack=new File(dir,"asr-reference-ack"),decoded=new File(dir,"asr-reference-decoded");
    ready.delete();wav.delete();ack.delete();decoded.delete();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long end=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      require((boolean)get("loaded"),"load");
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      require(get("capture")!=null,"foreground capture unavailable");
      end=SystemClock.elapsedRealtime()+15000;
      while("마이크 연결 중".equals(get("microphoneState"))&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      Thread.sleep(3500);set("diagnosticCapture",true);ready.createNewFile();
      end=SystemClock.elapsedRealtime()+120000;
      while(get("diagnosticRawPcm")==null&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      byte[] pcm=(byte[])get("diagnosticPcm"),raw=(byte[])get("diagnosticRawPcm");set("diagnosticPcm",null);set("diagnosticRawPcm",null);set("diagnosticCapture",false);
      require(pcm!=null,"utterance timeout");
      require(raw!=null,"wide audio timeout");
      runOnMainSync(()->{try{call("stopCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      call("writeWav",new Class<?>[]{File.class,byte[].class},wav,pcm);
      long at=SystemClock.elapsedRealtime();
      String text=(String)call("nativeTranscribe",new Class<?>[]{long.class,String.class,long.class},get("handle"),wav.getAbsolutePath(),epoch());
      report.put("seconds",pcm.length/32000.0).put("android_text",text).put("android_ms",SystemClock.elapsedRealtime()-at);
      call("writeWav",new Class<?>[]{File.class,byte[].class},wav,raw);
      at=SystemClock.elapsedRealtime();
      String wideText=(String)call("nativeTranscribe",new Class<?>[]{long.class,String.class,long.class},get("handle"),wav.getAbsolutePath(),epoch());
      report.put("wide_seconds",raw.length/32000.0).put("wide_android_text",wideText).put("wide_android_ms",SystemClock.elapsedRealtime()-at);
      try(WhisperAsr candidate=new WhisperAsr(new File(activity.getFilesDir(),"whisper"),"cpu",4)){
        report.put("whisper_model","turbo ONNX INT8 2ca6ff69 CPU");
        at=SystemClock.elapsedRealtime();report.put("whisper_text",candidate.decode(pcm)).put("whisper_ms",SystemClock.elapsedRealtime()-at);
        at=SystemClock.elapsedRealtime();report.put("wide_whisper_text",candidate.decode(raw)).put("wide_whisper_ms",SystemClock.elapsedRealtime()-at);
      }
      try(FileWriter f=new FileWriter(new File(dir,"asr-reference.json"))){f.write(report.toString(2));}
      decoded.createNewFile();end=SystemClock.elapsedRealtime()+120000;
      while(!ack.exists()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      report.put("result",ack.exists()?"PASS":"REFERENCE_TIMEOUT");result.putString("result",report.getString("result"));
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}}
    finally{
      wav.delete();ready.delete();decoded.delete();ack.delete();
      try{if(activity!=null){set("diagnosticCapture",false);set("diagnosticPcm",null);set("diagnosticRawPcm",null);runOnMainSync(activity::finish);}}catch(Exception ignored){}
      try(FileWriter f=new FileWriter(new File(dir,"asr-reference.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
