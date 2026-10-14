package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.lang.reflect.*;import java.nio.file.Files;import java.util.*;import java.util.concurrent.*;
import org.json.*;
/** Paced fixture into the production streaming recognizer; physical headset output, no camera. */
public final class StreamingConversationInstrumentation extends VoiceConversationInstrumentation {
  void set(String n,Object value)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,value);}
  public void onStart(){Bundle result=new Bundle();
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long end=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      require((boolean)get("loaded"),"load");require(!(boolean)get("cameraEnabled"),"camera off");
      runOnMainSync(()->{try{call("acquireAudioFocus",new Class<?>[]{});set("listening",true);}catch(Exception e){throw new RuntimeException(e);}});
      byte[] wav=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"question.wav").toPath());
      int rate=(wav[24]&255)|((wav[25]&255)<<8)|((wav[26]&255)<<16)|((wav[27]&255)<<24);
      int frames=(wav.length-44)/2;byte[] pcm=new byte[frames*16000/rate*2];
      for(int i=0;i<pcm.length/2;i++){double p=i*rate/16000.0;int a=(int)p,b=Math.min(a+1,frames-1);
        short x=(short)((wav[44+a*2]&255)|(wav[45+a*2]<<8)),y=(short)((wav[44+b*2]&255)|(wav[45+b*2]<<8));
        short v=(short)Math.round(x+(y-x)*(p-a));pcm[i*2]=(byte)v;pcm[i*2+1]=(byte)(v>>8);}
      // Production prepares the endpoint snapshot with 400ms of trailing silence.
      pcm=Arrays.copyOf(pcm,pcm.length+12800);
      StreamingAsr asr=(StreamingAsr)get("streamingAsr");
      runOnMainSync(()->{try{long id=(long)call("invalidate",new Class<?>[]{});set("microphoneTurnEpoch",id);set("userSpeaking",true);}catch(Exception e){throw new RuntimeException(e);}});
      long turn=asr.begin(),start=SystemClock.elapsedRealtime();
      for(int n=32000;n<pcm.length;n+=19200){
        long due=start+n/32;while(SystemClock.elapsedRealtime()<due)Thread.sleep(10);
        asr.accept(turn,Arrays.copyOf(pcm,n),false,SystemClock.elapsedRealtime());
      }
      long due=start+pcm.length/32;while(SystemClock.elapsedRealtime()<due)Thread.sleep(10);
      asr.accept(turn,pcm,false,SystemClock.elapsedRealtime());
      Thread.sleep(400);
      report.put("partials_before_endpoint",get("asrPartialUpdates")).put("drafts_before_endpoint",get("speculativeStarts"));
      require((int)get("asrPartialUpdates")>0,"no partial ASR before endpoint");
      require((int)get("speculativeStarts")>0,"no reasoning while speaking");
      require((long)get("firstPlaybackMs")<0,"speculative audio leaked while speaking");
      set("userSpeaking",false);long endpoint=SystemClock.elapsedRealtime();asr.accept(turn,pcm,true,endpoint);
      end=endpoint+45000;
      while((long)get("firstPlaybackMs")<0&&SystemClock.elapsedRealtime()<end)Thread.sleep(25);
      require((long)get("firstPlaybackMs")>=0,"no streaming answer audio");
      long id=epoch();awaitDrain(id);
      report.put("stream_first_text_ms",get("firstTextMs")).put("stream_first_audio_ms",get("firstPlaybackMs"))
        .put("speculative_reuses",get("speculativeReuses")).put("stream_response",get("response"));
      int sentences=(int)get("sentenceStarts");
      long multi=submit(null,"오이 샐러드 만드는 방법을 짧은 세 문장으로 설명해 줘. 각 문장은 마침표로 끝내 줘.");awaitAudio(multi);
      boolean overlap=((ThreadPoolExecutor)get("inference")).getActiveCount()>0;
      awaitDrain(multi);
      report.put("sentence_response",get("response")).put("sentences_started",(int)get("sentenceStarts")-sentences).put("audio_before_generation_end",overlap);
      require((int)get("sentenceStarts")-sentences>=2,"not sentence delivery");
      // Production cancel discards partial recognition too; stale final must not revive speech.
      runOnMainSync(()->{try{call("invalidate",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      long stopped=epoch();asr.accept(turn,pcm,true,SystemClock.elapsedRealtime());Thread.sleep(800);
      require(epoch()==stopped,"stale ASR revived answer");
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}}
    finally{
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"streaming-conversation.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
