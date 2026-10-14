package dev.live.offline;

/** Debug-only caller: bounded two-second pre-roll and five seconds after VAD onset. */
final class DiagnosticSpeechWindow {
  private final byte[] before=new byte[64000], after=new byte[160000];
  private int head,size,used;
  private boolean started,done;
  void start(){started=true;}
  byte[] samples(short[] pcm,int count){
    if(done)return null;
    for(int i=0;i<count;i++){
      for(int b=0;b<2;b++){
        byte value=(byte)(pcm[i]>>(8*b));
        if(started){
          after[used++]=value;
          if(used==after.length){
            done=true;byte[] out=new byte[size+used];
            int first=(head-size+before.length)%before.length;
            for(int j=0;j<size;j++)out[j]=before[(first+j)%before.length];
            System.arraycopy(after,0,out,size,used);return out;
          }
        }else{before[head]=value;head=(head+1)%before.length;size=Math.min(size+1,before.length);}
      }
    }
    return null;
  }
}
