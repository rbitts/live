package dev.live.offline;
import android.app.*;
import android.content.*;
import android.media.AudioTrack;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
/** No images: live headset quiet baseline, real fixture ASR, answer, cancel, resume. */
public class VoiceConversationInstrumentation extends Instrumentation {
  MainActivity activity;
  boolean textOnly;
  JSONObject report = new JSONObject();
  JSONArray turns = new JSONArray();
  Object get(String n)throws Exception { Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity); }
  Object call(String n,Class<?>[] t,Object...v)throws Exception { Method m=MainActivity.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(activity,v); }
  long epoch()throws Exception {return ((AtomicLong)get("epoch")).get();}
  void require(boolean value,String msg){if(!value)throw new AssertionError(msg);}
  public void onCreate(Bundle b){super.onCreate(b);textOnly=b.getBoolean("textOnly",false)||"true".equals(b.getString("textOnly"));start();}
  long submit(byte[] pcm,String text)throws Exception {
    final long[] id={0};
    runOnMainSync(()->{try {
      id[0]=(long)call("invalidate",new Class<?>[]{});
      call("submit",new Class<?>[]{byte[].class,String.class,long.class,long.class},pcm,text,id[0],SystemClock.elapsedRealtime());
    }catch(Exception e){throw new RuntimeException(e);}});
    return id[0];
  }
  void awaitAudio(long id)throws Exception {
    long end=SystemClock.elapsedRealtime()+35000;
    while(epoch()==id && (long)get(textOnly ? "firstTextMs" : "firstPlaybackMs")<0 && SystemClock.elapsedRealtime()<end)Thread.sleep(25);
    require(epoch()==id,"unexpected speech/cancel while waiting for answer");
    require((long)get(textOnly ? "firstTextMs" : "firstPlaybackMs")>=0,"no answer output");
    turns.put(new JSONObject().put("epoch",id).put("first_text_ms",get("firstTextMs")).put("first_audio_write_ms",get("firstPlaybackMs")).put("response",get("response")));
  }
  void awaitDrain(long id)throws Exception {
    if(textOnly) {
      long end=SystemClock.elapsedRealtime()+30000;
      java.util.concurrent.ThreadPoolExecutor worker=(java.util.concurrent.ThreadPoolExecutor)get("inference");
      while((worker.getActiveCount()>0||!worker.getQueue().isEmpty())&&SystemClock.elapsedRealtime()<end)Thread.sleep(25);
      require(epoch()==id && worker.getActiveCount()==0,"text completion failed");
      waitForIdleSync();
      String response=(String)get("response");
      require(response.contains("\n\n") && !response.substring(response.indexOf("\n\n")+2).trim().isEmpty(),"empty completed answer");
      turns.getJSONObject(turns.length()-1).put("completed_response",response);return;
    }
    long end=SystemClock.elapsedRealtime()+20000;
    while(SystemClock.elapsedRealtime()<end) {
      require(epoch()==id,"unexpected interruption during playback");
      AudioTrack track=(AudioTrack)get("track");
      if((long)get("audioDoneEpoch")==id && (long)get("audioWrittenSamples")>0 && Integer.toUnsignedLong(track.getPlaybackHeadPosition())>=(long)get("audioWrittenSamples"))return;
      Thread.sleep(25);
    }
    throw new AssertionError("audio drain timeout");
  }
  public void onStart(){Bundle result=new Bundle();
    try {
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long end=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
      require((boolean)get("loaded"),"model load");
      require(!(boolean)get("cameraEnabled"),"camera must be off");
      if(textOnly){Field field=MainActivity.class.getDeclaredField("speechEnabled");field.setAccessible(true);field.setBoolean(activity,false);}
      report.put("text_only",textOnly);
      byte[] wav=Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"question.wav").toPath());
      require(wav.length>44 && wav[0]=='R' && wav[36]=='d',"expected PCM WAV fixture");
      int rate=(wav[24]&255)|((wav[25]&255)<<8)|((wav[26]&255)<<16)|((wav[27]&255)<<24);
      require((rate==16000||rate==24000) && wav[22]==1 && wav[34]==16,"PCM mono fixture format");
      int sourceSamples=(wav.length-44)/2, outputSamples=sourceSamples*16000/rate;
      byte[] pcm=new byte[outputSamples*2];
      for(int i=0;i<outputSamples;i++) {
        double pos=i*rate/16000.0;int a=(int)pos,b=Math.min(a+1,sourceSamples-1);
        short x=(short)((wav[44+a*2]&255)|(wav[45+a*2]<<8));
        short y=(short)((wav[44+b*2]&255)|(wav[45+b*2]<<8));
        short sample=(short)Math.round(x+(y-x)*(pos-a));pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);
      }
      int positive=0,total=0; SpeechGate gate=new SpeechGate();SpeechSegmenter segmenter=new SpeechSegmenter();int starts=0;
      try(NeuralVad detector=new NeuralVad(activity)) {
        for(int offset=0;offset+640<=pcm.length;offset+=640) {
          short[] frame=new short[320]; double sum=0;
          for(int i=0;i<320;i++){frame[i]=(short)((pcm[offset+2*i]&255)|(pcm[offset+2*i+1]<<8));sum+=(double)frame[i]*frame[i];}
          boolean speech=detector.voiced(frame); if(speech)positive++;total++;
          if(segmenter.accept(gate.voiced(Math.sqrt(sum/320)/32768,segmenter.speaking())&&speech)==SpeechSegmenter.START)starts++;
        }
      }
      report.put("fixture_vad_frames",positive).put("fixture_frames",total).put("fixture_starts",starts);
      require(starts>0,"speech fixture rejected by gate");
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      Capture capture=(Capture)get("capture");
      require(capture!=null,"capture did not start: foreground="+get("foreground")+", loaded="+get("loaded")+", modelBusy="+get("modelBusy"));
      long before=capture.capturedSamples.get(),quietEpoch=epoch();
      Thread.sleep(15000);
      report.put("quiet_epoch_changes",epoch()-quietEpoch).put("quiet_capture_samples",capture.capturedSamples.get()-before);
      require(epoch()==quietEpoch,"false interruption during quiet baseline");
      report.put("input_ready", !"마이크 연결 중".equals(get("microphoneState")));
      if(!textOnly)require(!"마이크 연결 중".equals(get("microphoneState")),"headset input not connected");
      require(capture.capturedSamples.get()>before,"capture stopped");
      report.put("turns",turns);
      long first=submit(pcm,"");awaitAudio(first);awaitDrain(first);
      long interrupted=submit(null,"숫자 하나부터 열까지 차례대로 말해 줘.");awaitAudio(interrupted);
      before=capture.capturedSamples.get();long at=SystemClock.elapsedRealtime();
      runOnMainSync(()->{try{call("invalidate",new Class<?>[]{});}catch(Exception e){throw new RuntimeException(e);}});
      report.put("cancel_to_pause_ms",SystemClock.elapsedRealtime()-at);
      if(!textOnly)require(((AudioTrack)get("track")).getPlayState()!=AudioTrack.PLAYSTATE_PLAYING,"playback not stopped");
      require(!activity.onPcm(interrupted,new float[480]),"late PCM accepted");
      activity.onModelTextDelta(interrupted,"stale","stale");
      java.util.concurrent.ThreadPoolExecutor worker=(java.util.concurrent.ThreadPoolExecutor)get("inference");
      long cancelDeadline=SystemClock.elapsedRealtime()+10000;
      while(worker.getActiveCount()>0&&SystemClock.elapsedRealtime()<cancelDeadline)Thread.sleep(5);
      require(worker.getActiveCount()==0,"generation cancellation timeout");
      report.put("cancel_to_worker_idle_ms",SystemClock.elapsedRealtime()-at);
      Thread.sleep(100);waitForIdleSync();
      require(!((String)get("response")).contains("stale"),"late text accepted");
      require(capture.capturedSamples.get()>before && (boolean)get("listening"),"mic stopped on cancel");
      long resumed=submit(null,"하나 더하기 하나는 얼마야?");awaitAudio(resumed);awaitDrain(resumed);
      report.put("result","PASS");result.putString("result","PASS");
    }catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString()).put("last_caption",get("microphoneTranscript")).put("last_response",get("response"));}catch(Exception ignored){}}
    finally{
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),textOnly ? "voice-text-conversation.json" : "voice-conversation.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);
      finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
  }
}
