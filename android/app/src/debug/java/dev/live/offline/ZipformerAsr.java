package dev.live.offline;
import android.content.Context;
import com.k2fsa.sherpa.onnx.*;

/** Debug Korean streaming candidate. One worker owns its stream and recurrent state. */
final class ZipformerAsr implements AutoCloseable {
  private final OnlineRecognizer recognizer;
  private OnlineStream stream;
  private long turn=-1;
  private int accepted;
  private boolean finished;
  ZipformerAsr(Context context) {this(context,false);}
  ZipformerAsr(Context context,boolean modern) {
    OnlineModelConfig model=new OnlineModelConfig();
    String folder=modern?"zipformer-ko-72m/":"zipformer-ko/";
    String suffix=modern?"-epoch-99-avg-1-chunk-16-left-128.int8.onnx":"-epoch-99-avg-1.int8.onnx";
    OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
    transducer.setEncoder(folder+"encoder"+suffix);
    transducer.setDecoder(folder+"decoder"+(modern?suffix:"-epoch-99-avg-1.onnx"));
    transducer.setJoiner(folder+"joiner"+suffix);
    model.setTransducer(transducer);
    model.setTokens(folder+"tokens.txt");model.setNumThreads(2);model.setProvider("cpu");model.setModelType(modern?"zipformer2":"zipformer");
    OnlineRecognizerConfig config=new OnlineRecognizerConfig();config.setModelConfig(model);config.setDecodingMethod("greedy_search");
    if(modern){config.setDecodingMethod("modified_beam_search");config.setMaxActivePaths(8);}
    recognizer=new OnlineRecognizer(context.getAssets(),config);
  }
  String decode(byte[] pcm,long id,boolean last) {
    if(turn!=id){if(stream!=null)stream.release();stream=recognizer.createStream("");turn=id;accepted=0;finished=false;}
    if(finished)return recognizer.getResult(stream).getText().trim();
    if(pcm.length<accepted || (pcm.length&1)!=0)throw new IllegalArgumentException("invalid cumulative audio");
    float[] fresh=new float[(pcm.length-accepted)/2];
    for(int i=0;i<fresh.length;i++){int at=accepted+i*2;fresh[i]=(short)((pcm[at]&255)|(pcm[at+1]<<8))/32768f;}
    if(fresh.length>0)stream.acceptWaveform(fresh,16000);accepted=pcm.length;
    if(last){stream.acceptWaveform(new float[10240],16000);stream.inputFinished();finished=true;}
    while(recognizer.isReady(stream))recognizer.decode(stream);
    return recognizer.getResult(stream).getText().trim();
  }
  public void close(){if(stream!=null){stream.release();stream=null;}recognizer.release();}
}
