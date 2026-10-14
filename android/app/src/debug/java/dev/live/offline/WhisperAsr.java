package dev.live.offline;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;

/** Debug on-device Whisper reference; each decode owns its bounded input stream. */
final class WhisperAsr implements AutoCloseable {
  private final OfflineRecognizer recognizer;
  private final boolean fullContext;
  WhisperAsr(File root,String provider,int threads){this(root,provider,threads,false);}
  WhisperAsr(File root,String provider,int threads,boolean fullContext){
    this.fullContext=fullContext;
    OfflineWhisperModelConfig whisper=new OfflineWhisperModelConfig();
    whisper.setEncoder(new File(root,"turbo-encoder.int8.onnx").getAbsolutePath());
    whisper.setDecoder(new File(root,"turbo-decoder.int8.onnx").getAbsolutePath());
    whisper.setLanguage("ko");whisper.setTask("transcribe");
    OfflineModelConfig model=new OfflineModelConfig();model.setWhisper(whisper);
    model.setTokens(new File(root,"turbo-tokens.txt").getAbsolutePath());
    model.setNumThreads(threads);model.setProvider(provider);model.setDebug(true);
    OfflineRecognizerConfig config=new OfflineRecognizerConfig();config.setModelConfig(model);
    recognizer=new OfflineRecognizer(null,config);
  }
  String decode(byte[] pcm){
    if(pcm.length==0 || pcm.length>512000 || (pcm.length&1)!=0)throw new IllegalArgumentException("PCM length");
    float[] samples=new float[fullContext?480000:pcm.length/2];
    for(int i=0;i<pcm.length/2;i++)samples[i]=(short)((pcm[i*2]&255)|(pcm[i*2+1]<<8))/32768f;
    OfflineStream stream=recognizer.createStream();
    try{stream.acceptWaveform(samples,16000);recognizer.decode(stream);return recognizer.getResult(stream).getText().trim();}
    finally{stream.release();}
  }
  public void close(){recognizer.release();}
}
