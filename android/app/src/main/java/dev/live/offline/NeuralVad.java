package dev.live.offline;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Pinned offline Silero model, one CPU thread; independent from ASR/answer workers. */
final class NeuralVad implements AutoCloseable {
  private final OrtEnvironment env=OrtEnvironment.getEnvironment();
  private final OrtSession session;
  private final float[] block=new float[512];
  private float[][][] h=new float[2][1][64],c=new float[2][1][64];
  private int filled;
  private float probability;
  private long calls,nanos,maxNanos;
  NeuralVad(Context context)throws Exception {
    try(InputStream input=context.getAssets().open("silero-vad.onnx");
        OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(1);options.setInterOpNumThreads(1);
      ByteArrayOutputStream model=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
      while((count=input.read(bytes))!=-1){model.write(bytes,0,count);if(model.size()>4*1024*1024)throw new IOException("VAD model size");}
      session=env.createSession(model.toByteArray(),options);
    }
  }
  void reset(){filled=0;probability=0;Arrays.fill(block,0);h=new float[2][1][64];c=new float[2][1][64];}
  boolean voiced(short[] pcm)throws OrtException {return voiced(pcm,false);}
  boolean voiced(short[] pcm,boolean speaking)throws OrtException {
    for(short sample:pcm){block[filled++]=sample/32768f;if(filled==512){infer();filled=0;}}
    return probability>=(speaking?0.35f:0.65f);
  }
  private void infer()throws OrtException {
    long begin=System.nanoTime();
    try(OnnxTensor audio=OnnxTensor.createTensor(env,FloatBuffer.wrap(block),new long[]{1,512});
        OnnxTensor rate=OnnxTensor.createTensor(env,LongBuffer.wrap(new long[]{16000}),new long[]{});
        OnnxTensor hidden=OnnxTensor.createTensor(env,h);OnnxTensor cell=OnnxTensor.createTensor(env,c)) {
      Map<String,OnnxTensor> inputs=new HashMap<>();inputs.put("input",audio);inputs.put("sr",rate);inputs.put("h",hidden);inputs.put("c",cell);
      try(OrtSession.Result out=session.run(inputs)) {
      probability=((float[][])out.get("output").get().getValue())[0][0];
      h=(float[][][])out.get("hn").get().getValue();c=(float[][][])out.get("cn").get().getValue();
      if(!Float.isFinite(probability))throw new IllegalStateException("speech detector invalid output");
      }
    }
    long elapsed=System.nanoTime()-begin;calls++;nanos+=elapsed;maxNanos=Math.max(maxNanos,elapsed);
  }
  public void close(){try{session.close();}catch(OrtException ignored){}
    android.util.Log.i("LiveVoice","neural_vad calls="+calls+" mean_us="+(calls==0?0:nanos/calls/1000)+" max_us="+maxNanos/1000);
  }
}
