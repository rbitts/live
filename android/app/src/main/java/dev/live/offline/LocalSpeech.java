package dev.live.offline;

import android.content.Context;
import android.media.AudioFormat;
import android.os.*;
import android.speech.tts.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Installed offline Korean voice only. Audio is played by the app's existing metered AudioTrack. */
final class LocalSpeech implements AutoCloseable {
  interface Output { boolean pcm(long id, float[] pcm); void done(long id, String error); }
  private TextToSpeech engine;
  private final File directory;
  private final Output output;
  private final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
  private volatile boolean ready, closed;
  private volatile long epoch = -1;
  private volatile String utteranceKey="";
  private long serial;
  private volatile String voiceName = "";
  volatile long synthesisMs=-1,firstEngineAudioMs=-1,renderedSamples;
  private volatile long synthesisStarted;
  LocalSpeech(Context context, File directory, Output output) {this(context,directory,output,new String[0]);}
  LocalSpeech(Context context, File directory, Output output,String... preferredNames) {
    this.directory=directory;this.output=output;directory.mkdirs();
    engine=new TextToSpeech(context, status -> new Handler(Looper.getMainLooper()).post(() -> {
      if(closed || status!=TextToSpeech.SUCCESS || engine==null)return;
      Set<Voice> voices=engine.getVoices();
      if(voices==null)return;
      // Respect the engine's current voice when it is installed/offline Korean.
      // Alphabetical names are not a voice-quality ranking.
      Voice preferred=engine.getVoice();
      for(String name:preferredNames){
        Voice selected=voices.stream().filter(v->name.equals(v.getName())&&offlineKorean(v)).findFirst().orElse(null);
        if(selected!=null){preferred=selected;break;}
      }
      Voice best=offlineKorean(preferred)?preferred:voices.stream().filter(LocalSpeech::offlineKorean)
          .sorted(Comparator.comparingInt(Voice::getQuality).reversed()
              .thenComparingInt(Voice::getLatency).thenComparing(Voice::getName))
          .findFirst().orElse(null);
      if(best!=null && engine.setVoice(best)==TextToSpeech.SUCCESS){voiceName=best.getName();ready=true;}
    }));
    engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
      public void onStart(String id){}
      public void onAudioAvailable(String id,byte[] audio){
        if(id.equals(utteranceKey) && audio.length>0 && firstEngineAudioMs<0)
          firstEngineAudioMs=SystemClock.elapsedRealtime()-synthesisStarted;
      }
      public void onStop(String id, boolean interrupted){new File(directory,"voice-"+id+".wav").delete();}
      public void onError(String id){complete(id,"기기 음성을 만들지 못했어요.");}
      public void onDone(String id){
        if(id.equals(utteranceKey))synthesisMs=SystemClock.elapsedRealtime()-synthesisStarted;
        complete(id,"");
      }
    });
  }
  private static boolean offlineKorean(Voice voice){
    return voice!=null && voice.getLocale().getLanguage().equals("ko")
        && !voice.isNetworkConnectionRequired() && (voice.getFeatures()==null
        || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
  }
  boolean ready(){return ready&&!closed;}
  String voice(){return voiceName;}
  void speak(long id,String text){
    cancel();epoch=id;utteranceKey=id+"-"+(++serial);
    synthesisStarted=SystemClock.elapsedRealtime();synthesisMs=firstEngineAudioMs=-1;renderedSamples=0;
    if(!ready()){output.done(id,"설치된 오프라인 한국어 음성이 없어 글로 안내합니다.");return;}
    if(text.isBlank()||text.length()>400){output.done(id,"짧은 답변만 음성으로 안내합니다.");return;}
    Bundle options=new Bundle();
    File file=new File(directory,"voice-"+utteranceKey+".wav");
    if(engine.synthesizeToFile(text,options,file,utteranceKey)!=TextToSpeech.SUCCESS)
      output.done(id,"기기 음성을 사용할 수 없어 글로 안내합니다.");
  }
  private void complete(String value,String error){
    final long id;try{id=Long.parseLong(value.split("-",2)[0]);}catch(NumberFormatException e){return;}
    if(closed || epoch!=id || !value.equals(utteranceKey)){new File(directory,"voice-"+value+".wav").delete();return;}
    try { reader.execute(()->{
      File file=new File(directory,"voice-"+value+".wav");
      try{
        if(epoch!=id||!value.equals(utteranceKey))return;
        if(!error.isEmpty())throw new IOException(error);
        float[] samples=readPcm(file);
        renderedSamples=samples.length;
        for(int at=0;at<samples.length&&epoch==id&&value.equals(utteranceKey);at+=4800)
          if(!output.pcm(id,Arrays.copyOfRange(samples,at,Math.min(samples.length,at+4800))))return;
        if(epoch==id&&value.equals(utteranceKey))output.done(id,"");
      }catch(Exception e){if(epoch==id&&value.equals(utteranceKey))output.done(id,e.getMessage());}
      finally{file.delete();}
    }); } catch(RejectedExecutionException e) {
      new File(directory,"voice-"+value+".wav").delete();
      if(epoch==id&&!closed)output.done(id,"음성 처리가 바빠 글로 안내합니다.");
    }
  }
  static float[] readPcm(File file)throws IOException{
    if(file.length()<44||file.length()>4*1024*1024)throw new IOException("음성 크기 확인 필요");
    ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
    if(b.getInt()!=0x46464952 || b.getInt()<0 || b.getInt()!=0x45564157)throw new IOException("WAV 형식 확인 필요");
    int format=0,channels=0,rate=0,bits=0,start=0,size=0;
    while(b.remaining()>=8){
      int tag=b.getInt(),length=b.getInt(),pos=b.position();
      if(length<0||length>b.remaining())throw new IOException("WAV 길이 확인 필요");
      if(tag==0x20746d66 && length>=16){format=b.getShort()&65535;channels=b.getShort()&65535;rate=b.getInt();b.getInt();b.getShort();bits=b.getShort()&65535;}
      if(tag==0x61746164){start=pos;size=length;}
      long next=(long)pos+length+(length&1);if(next>b.limit())break;b.position((int)next);
    }
    if(format!=1||bits!=16||channels<1||channels>2||rate<8000||rate>48000||size==0||size%(channels*2)!=0)
      throw new IOException("지원하지 않는 기기 음성 형식");
    int frames=size/(channels*2);if(frames>rate*20)throw new IOException("음성 길이 확인 필요");
    float[] mono=new float[frames];b.position(start);
    for(int i=0;i<frames;i++){for(int c=0;c<channels;c++)mono[i]+=b.getShort()/32768f/channels;}
    float[] out=new float[(int)((long)frames*24000/rate)];
    for(int i=0;i<out.length;i++){double source=(double)i*rate/24000.0;int lo=(int)source;float f=(float)(source-lo);out[i]=mono[lo]*(1-f)+mono[Math.min(lo+1,frames-1)]*f;}
    return out;
  }
  void cancel(){epoch=-1;utteranceKey="";if(engine!=null)engine.stop();}
  public void close(){closed=true;ready=false;cancel();if(engine!=null)engine.shutdown();reader.shutdown();}
}
