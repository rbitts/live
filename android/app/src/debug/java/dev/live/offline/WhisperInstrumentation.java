package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.nio.file.Files;import org.json.*;

/** Physical-device Whisper completion and latency smoke, independent of app answer models. */
public final class WhisperInstrumentation extends Instrumentation {
  private String provider="cpu";
  private boolean fullContext;
  public void onCreate(Bundle b){super.onCreate(b);if(b!=null){provider=b.getString("provider","cpu");fullContext="true".equals(b.getString("full_context","false"));}start();}
  public void onStart(){Activity activity=null;JSONObject report=new JSONObject();Bundle out=new Bundle();
    File dir=getTargetContext().getExternalFilesDir(null);
    try{
      activity=startActivitySync(new Intent(getTargetContext(),ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity shown=activity;runOnMainSync(()->shown.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long at=SystemClock.elapsedRealtime();
      try(WhisperAsr asr=new WhisperAsr(new File(getTargetContext().getFilesDir(),"whisper"),provider,4,fullContext)){
        report.put("provider_requested",provider).put("full_context",fullContext).put("load_ms",SystemClock.elapsedRealtime()-at);
        byte[] wav=Files.readAllBytes(new File(dir,"question.wav").toPath());
        int rate=(wav[24]&255)|((wav[25]&255)<<8)|((wav[26]&255)<<16)|((wav[27]&255)<<24),frames=(wav.length-44)/2;
        byte[] pcm=new byte[frames*16000/rate*2];
        for(int i=0;i<pcm.length/2;i++){double p=i*rate/16000.0;int a=(int)p,b=Math.min(a+1,frames-1);short x=(short)((wav[44+2*a]&255)|(wav[45+2*a]<<8)),y=(short)((wav[44+2*b]&255)|(wav[45+2*b]<<8));short s=(short)Math.round(x+(y-x)*(p-a));pcm[2*i]=(byte)s;pcm[2*i+1]=(byte)(s>>8);}
        at=SystemClock.elapsedRealtime();String text=asr.decode(pcm);report.put("decode_ms",SystemClock.elapsedRealtime()-at).put("text",text).put("audio_ms",pcm.length/32);
        if(text.isEmpty())throw new AssertionError("empty transcript");
        report.put("result","PASS");out.putString("result","PASS");
      }
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}}
    finally{try(FileWriter f=new FileWriter(new File(dir,"whisper-profile-"+provider+".json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null){Activity end=activity;runOnMainSync(end::finish);}finish("PASS".equals(out.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
  }
}
