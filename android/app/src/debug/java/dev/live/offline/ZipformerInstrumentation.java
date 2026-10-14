package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.nio.file.Files;import java.util.*;import org.json.*;
/** Real tablet incremental candidate inference, with a fixed PCM fixture. */
public final class ZipformerInstrumentation extends Instrumentation {
  public void onCreate(Bundle b){super.onCreate(b);start();}
  public void onStart(){Activity activity=null;JSONObject report=new JSONObject();Bundle out=new Bundle();
    try{
      activity=startActivitySync(new Intent(getTargetContext(),ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity shown=activity;runOnMainSync(()->shown.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long at=SystemClock.elapsedRealtime();
      try(ZipformerAsr asr=new ZipformerAsr(activity,true)){
        report.put("load_ms",SystemClock.elapsedRealtime()-at);
        byte[] wav=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"question.wav").toPath());
        int rate=(wav[24]&255)|((wav[25]&255)<<8)|((wav[26]&255)<<16)|((wav[27]&255)<<24),frames=(wav.length-44)/2;
        byte[] pcm=new byte[frames*16000/rate*2];
        for(int i=0;i<pcm.length/2;i++){double p=i*rate/16000.0;int a=(int)p,b=Math.min(a+1,frames-1);short x=(short)((wav[44+2*a]&255)|(wav[45+2*a]<<8)),y=(short)((wav[44+2*b]&255)|(wav[45+2*b]<<8));short s=(short)Math.round(x+(y-x)*(p-a));pcm[2*i]=(byte)s;pcm[2*i+1]=(byte)(s>>8);}
        JSONArray updates=new JSONArray();long total=0,max=0;
        for(int n=6400;n<pcm.length;n+=6400){at=SystemClock.elapsedRealtime();String text=asr.decode(Arrays.copyOf(pcm,n),1,false);long ms=SystemClock.elapsedRealtime()-at;total+=ms;max=Math.max(max,ms);if(!text.isEmpty())updates.put(new JSONObject().put("audio_ms",n/32).put("text",text).put("ms",ms));}
        at=SystemClock.elapsedRealtime();String result=asr.decode(pcm,1,true);long finalMs=SystemClock.elapsedRealtime()-at;total+=finalMs;
        report.put("text",result).put("updates",updates).put("decode_ms",total).put("max_update_ms",max).put("final_ms",finalMs).put("audio_ms",pcm.length/32);
      report.put("batch_text",asr.decode(pcm,2,true));
        if(result.isEmpty())throw new AssertionError("empty candidate result");
        report.put("result","PASS");out.putString("result","PASS");
      }
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}}
    finally{try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"zipformer-profile.json"))){f.write(report.toString(2));}catch(Exception ignored){}if(activity!=null){Activity end=activity;runOnMainSync(end::finish);}finish("PASS".equals(out.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
  }
}
