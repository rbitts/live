package dev.live.offline;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.util.concurrent.*;import org.json.*;
/** Transient synthetic PCM, same pinned recognizer, explicit context comparison. */
public final class RecognitionFixInstrumentation extends VoiceConversationInstrumentation {
  native String transcribeContext(long handle,String wav,long epoch,String context);
  void set(String n,Object v)throws Exception{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,v);}
  public void onStart(){Bundle result=new Bundle();MockVoiceInput voice=null;
    File wav=new File(getTargetContext().getCacheDir(),"recognition-fix.wav");
    try{
      activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      long until=SystemClock.elapsedRealtime()+90000;
      while(!(boolean)get("loaded")&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      require((boolean)get("loaded"),"models");
      BlockingQueue<short[]> samples=new ArrayBlockingQueue<>(1);
      voice=new MockVoiceInput(activity,activity.getCacheDir(),new MockVoiceInput.Output(){
        public void ready(long id,short[] pcm){samples.offer(pcm);}
        public void error(long id,String error){samples.offer(new short[0]);}
      });
      until=SystemClock.elapsedRealtime()+10000;while(!voice.ready()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
      require(voice.ready(),"mock voice");JSONArray rows=new JSONArray();report.put("rows",rows);
      String[] inputs={};
      String[] contexts={"","타이머, 시간, 초, 분, 일 초, 이 초, 삼 초, 사 초, 오 초, 육 초, 칠 초, 팔 초, 구 초, 십 초, 이십 초, 삼십 초"};
      long n=0;
      for(String input:inputs){voice.prepare(++n,input);short[] pcm=samples.poll(30,TimeUnit.SECONDS);require(pcm!=null&&pcm.length>0,"synthesis");
        byte[] bytes=new byte[pcm.length*2];for(int i=0;i<pcm.length;i++){bytes[i*2]=(byte)pcm[i];bytes[i*2+1]=(byte)(pcm[i]>>8);}
        call("writeWav",new Class<?>[]{File.class,byte[].class},wav,bytes);
        for(String context:contexts){long at=SystemClock.elapsedRealtime();String text=transcribeContext((long)get("handle"),wav.getAbsolutePath(),epoch(),context);
          rows.put(new JSONObject().put("input",input).put("context",context).put("recognized",text).put("ms",SystemClock.elapsedRealtime()-at));}
        wav.delete();
      }
      voice.close();voice=null;
      runOnMainSync(()->{try{call("startCapture",new Class<?>[]{});((Capture)get("capture")).mockInput.enable();set("diagnosticCapture",true);call("sendMockVoice",new Class<?>[]{String.class},"십 초 타이머로 바꿔 줘.");}catch(Exception e){throw new RuntimeException(e);}});
      until=SystemClock.elapsedRealtime()+20000;while(get("diagnosticRawPcm")==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
      byte[] cut=(byte[])get("diagnosticPcm"),wide=(byte[])get("diagnosticRawPcm");require(cut!=null&&wide!=null,"capture windows");
      runOnMainSync(()->{try{call("stopCapture",new Class<?>[]{});set("diagnosticCapture",false);set("diagnosticPcm",null);set("diagnosticRawPcm",null);}catch(Exception e){throw new RuntimeException(e);}});
      JSONArray windows=new JSONArray();report.put("capture_windows",windows);
      int offset=-1;
      for(int o=0;o+cut.length<=wide.length;o+=2){boolean match=true;for(int j=0;j<cut.length;j++)if(cut[j]!=wide[o+j]){match=false;break;}if(match){offset=o;break;}}
      report.put("segmented_offset_samples",offset/2);
      double sum=0;int peak=0;if(offset>0)for(int j=0;j<offset;j+=2){int v=(short)((wide[j]&255)|(wide[j+1]<<8));sum+=(double)v*v;peak=Math.max(peak,Math.abs(v));}
      report.put("excluded_leading_peak",peak).put("excluded_leading_rms",offset>0?Math.sqrt(sum/(offset/2)):0);
      byte[] lead=new byte[cut.length+12800],tail=new byte[cut.length+12800],both=new byte[cut.length+25600];
      System.arraycopy(cut,0,lead,12800,cut.length);System.arraycopy(cut,0,tail,0,cut.length);System.arraycopy(cut,0,both,12800,cut.length);
      byte[] tail800=java.util.Arrays.copyOf(cut,cut.length+25600),tail1200=java.util.Arrays.copyOf(cut,cut.length+38400),tail2000=java.util.Arrays.copyOf(cut,cut.length+64000);
      double tailEnergy=0;int tailPeak=0;if(offset>=0)for(int j=offset+cut.length;j+1<wide.length;j+=2){int v=(short)((wide[j]&255)|(wide[j+1]<<8));tailEnergy+=(double)v*v;tailPeak=Math.max(tailPeak,Math.abs(v));}
      report.put("excluded_trailing_peak",tailPeak).put("excluded_trailing_energy",tailEnergy);
      byte[][] variants={cut,wide,lead,tail,both,tail800,tail1200,tail2000};String[] labels={"segmented","wide","lead400","tail400","both400","tail800","tail1200","tail2000"};
      for(int i=0;i<variants.length;i++){byte[] pcm=variants[i];call("writeWav",new Class<?>[]{File.class,byte[].class},wav,pcm);
        windows.put(new JSONObject().put("window",labels[i]).put("samples",pcm.length/2).put("recognized",transcribeContext((long)get("handle"),wav.getAbsolutePath(),epoch(),"")));}
      call("writeWav",new Class<?>[]{File.class,byte[].class},wav,cut);
      for(String context:new String[]{"타이머",contexts[1]})windows.put(new JSONObject().put("window","segmented_with_context").put("context",context).put("recognized",transcribeContext((long)get("handle"),wav.getAbsolutePath(),epoch(),context)));
      report.put("status","completed");result.putString("result","COMPLETE");
    }catch(Throwable e){try{report.put("error",e.toString());}catch(Exception ignored){}}
    finally{wav.delete();if(voice!=null)voice.close();
      try(FileWriter f=new FileWriter(new File(getTargetContext().getExternalFilesDir(null),"recognition-fix.json"))){f.write(report.toString(2));}catch(Exception ignored){}
      if(activity!=null)runOnMainSync(activity::finish);finish(Activity.RESULT_OK,result);}
  }
}
