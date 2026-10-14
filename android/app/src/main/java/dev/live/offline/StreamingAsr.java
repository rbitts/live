package dev.live.offline;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.Arrays;
/** Bounded cumulative-audio recognition. Only the newest waiting snapshot is retained. */
final class StreamingAsr implements AutoCloseable {
  interface Decoder { String decode(byte[] pcm,long turn)throws Exception; default String decode(byte[] pcm,long turn,String context)throws Exception{return decode(pcm,turn);} void cancel(long turn); }
  interface Output { void text(long turn,String text,boolean last,long at); void error(long turn,String error); }
  private static final class Job {
    final byte[] pcm; final long turn,at; final boolean last; final String context;
    Job(byte[] p,long t,boolean l,long a,String c){pcm=p;turn=t;last=l;at=a;context=c;}
  }
  private final Decoder decoder; private final Output output;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final AtomicReference<Job> pending=new AtomicReference<>();
  private final AtomicBoolean draining=new AtomicBoolean();
  private final AtomicLong generation=new AtomicLong();
  private volatile boolean closed,finalQueued;
  private String context="";
  private Job decoded;
  private String decodedText;
  StreamingAsr(Decoder d,Output o){decoder=d;output=o;}
  synchronized long begin(){return begin("");}
  synchronized long begin(String value){
    if(value==null||value.length()>128)throw new IllegalArgumentException("ASR context limit");
    cancel();context=value;return generation.get();
  }
  synchronized void cancel(){long t=generation.incrementAndGet();finalQueued=false;context="";pending.set(null);decoded=null;decodedText=null;decoder.cancel(t);}
  long current(){return generation.get();}
  synchronized void accept(long turn,byte[] pcm,boolean last,long at){
    if(closed||turn!=generation.get()||finalQueued)return;
    if(pcm.length==0||pcm.length>512000){output.error(turn,"음성 입력 길이 제한");return;}
    if(last)finalQueued=true;
    pending.set(new Job(pcm,turn,last,at,context));schedule();
  }
  private void schedule(){if(!closed&&draining.compareAndSet(false,true)){
    try{worker.execute(this::drain);}catch(RejectedExecutionException e){draining.set(false);}
  }}
  private void drain(){try{
    Job job;
    while(!closed&&(job=pending.getAndSet(null))!=null){
      if(job.turn!=generation.get())continue;
      try{String text;
        synchronized(this){text=decoded!=null&&decoded.turn==job.turn&&Arrays.equals(decoded.pcm,job.pcm)?decodedText:null;}
        if(text==null)text=decoder.decode(job.pcm,job.turn,job.context);
        synchronized(this){if(!closed&&job.turn==generation.get()){decoded=job;decodedText=text;}}
        if(!closed&&job.turn==generation.get())output.text(job.turn,text,job.last,job.at);
      }catch(Exception e){if(job.last&&!closed&&job.turn==generation.get())output.error(job.turn,e.getMessage());}
    }
  }finally{draining.set(false);if(pending.get()!=null)schedule();}}
  public void close(){closed=true;cancel();worker.shutdown();}
  void awaitClosed()throws InterruptedException{while(!worker.awaitTermination(1,TimeUnit.SECONDS)){} }
}
