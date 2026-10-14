package dev.live.offline;
import android.app.*;import android.os.*;import android.speech.tts.*;
import java.io.*;import java.util.concurrent.*;import org.json.*;
/** Read installed Korean voice metadata without loading model workers. */
public final class VoiceInventoryInstrumentation extends Instrumentation {
  public void onCreate(Bundle b){super.onCreate(b);start();}
  public void onStart(){TextToSpeech tts=null;JSONObject report=new JSONObject();Bundle result=new Bundle();
    try{CountDownLatch ready=new CountDownLatch(1);int[] status={-1};
      tts=new TextToSpeech(getTargetContext(),value->{status[0]=value;ready.countDown();});
      if(!ready.await(15,TimeUnit.SECONDS)||status[0]!=TextToSpeech.SUCCESS)throw new IllegalStateException("TTS unavailable");
      JSONArray voices=new JSONArray();for(Voice v:tts.getVoices())if(v.getLocale().getLanguage().equals("ko"))
        voices.put(new JSONObject().put("name",v.getName()).put("locale",v.getLocale().toLanguageTag()).put("network",v.isNetworkConnectionRequired()).put("quality",v.getQuality()).put("features",String.valueOf(v.getFeatures())));
      report.put("engine",tts.getDefaultEngine()).put("default_voice",String.valueOf(tts.getVoice())).put("voices",voices);
      result.putString("result","COMPLETE");
    }catch(Throwable error){try{report.put("error",error.toString());}catch(Exception ignored){}}
    finally{if(tts!=null)tts.shutdown();try(FileWriter out=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"voice-inventory.json"))){out.write(report.toString(2));}catch(Exception ignored){}finish(Activity.RESULT_OK,result);}
  }
}
