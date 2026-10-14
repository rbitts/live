package dev.live.offline;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Synthetic silent PCM checks queue backpressure and EOS; not model acceptance. */
public final class AudioBufferInstrumentation extends Instrumentation {
  private MainActivity activity;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private Object get(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
    return field.get(activity);
  }
  private void set(String name, Object value) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
    field.set(activity, value);
  }
  private long reset() throws Exception {
    Method method = MainActivity.class.getDeclaredMethod("invalidate"); method.setAccessible(true);
    long id = (Long) method.invoke(activity);
    set("firstAudioMs", -1L); set("firstPlaybackMs", -1L); set("audioDoneEpoch", -1L);
    set("requestStarted", SystemClock.elapsedRealtime()); set("audioWrittenSamples", 0L);
    set("audioProducerWaits", 0);
    return id;
  }
  private void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  private float level() throws Exception {
    Method method=MainActivity.class.getDeclaredMethod("currentVoiceLevel");method.setAccessible(true);
    return (Float)method.invoke(activity);
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (get("track") == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
      ((android.media.AudioTrack)get("track")).setVolume(0f);
      runOnMainSync(() -> {
        try {
          Method focus=MainActivity.class.getDeclaredMethod("acquireAudioFocus");focus.setAccessible(true);
          require((Boolean)focus.invoke(activity),"audio focus unavailable");
        } catch(Exception e){throw new RuntimeException(e);}
      });
      long shortId = reset();
      require(activity.onPcm(shortId, new float[2400]), "short PCM rejected");
      Thread.sleep(150);
      require((Long) get("audioWrittenSamples") == 0, "preroll starts too early");
      set("audioDoneEpoch", shortId);
      deadline = SystemClock.elapsedRealtime() + 3000;
      while ((Long) get("audioWrittenSamples") < 2400 && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(20);
      require((Long) get("audioWrittenSamples") == 2400, "short EOS was never played");
      // The real playback head must select the meter, not PCM arrival time.
      long meterId=reset();
      float[] tone=new float[57600];
      for(int i=9600;i<48000;i++)
        tone[i]=(float)Math.sin(i*2*Math.PI*220/24000)*(i<28800?.04f:.16f);
      require(activity.onPcm(meterId,tone),"meter PCM rejected");
      require(level()==0,"buffered audio must not animate");
      set("audioDoneEpoch",meterId);
      float quiet=0,loud=0;boolean silentTail=false;
      deadline=SystemClock.elapsedRealtime()+5000;
      while(SystemClock.elapsedRealtime()<deadline){
        android.media.AudioTrack track=(android.media.AudioTrack)get("track");
        long head=Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        float value=level();
        if(head>12000 && head<26000)quiet=Math.max(quiet,value);
        if(head>32000 && head<45000)loud=Math.max(loud,value);
        if(head>51000 && head<57000){require(value==0,"silent PCM must not animate");silentTail=true;}
        if(head>=tone.length)break;
        Thread.sleep(10);
      }
      result.putFloat("quiet_playback_level",quiet);result.putFloat("loud_playback_level",loud);
      result.putBoolean("silent_tail_observed",silentTail);
      result.putLong("meter_written_samples",(Long)get("audioWrittenSamples"));
      result.putLong("meter_head",Integer.toUnsignedLong(((android.media.AudioTrack)get("track")).getPlaybackHeadPosition()));
      require(quiet>0 && loud>quiet*2 && silentTail,"playback amplitude sequence not observed");
      require(level()==0,"completed audio must not animate");
      long id = reset();
      Thread producer = new Thread(() -> {
        for (int i = 0; i < 30; i++) if (!activity.onPcm(id, new float[72000])) return;
      });
      producer.start();
      deadline = SystemClock.elapsedRealtime() + 3000;
      while ((Integer) get("audioProducerWaits") == 0 && SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(20);
      require((Integer) get("audioProducerWaits") > 0, "backpressure not exercised");
      require(producer.isAlive(), "producer silently truncated at a full queue");
      require(((ArrayBlockingQueue<?>) get("playback")).size() <= 4, "unbounded queue");
      long start = SystemClock.elapsedRealtime();
      reset();
      producer.join(1000);
      require(!producer.isAlive(), "cancel deadlocked against blocked producer");
      long cancelMs = SystemClock.elapsedRealtime() - start;
      require(cancelMs < 500, "cancel took too long");
      require(!activity.onPcm(id, new float[480]), "late PCM accepted");
      require(level()==0,"cancelled audio must not animate");
      require(!(Boolean) get("listening"), "synthetic test unexpectedly opened microphone");
      result.putString("result", "PASS");
      result.putLong("blocked_producer_cancel_ms", cancelMs);
      result.putString("scope", "Synthetic PCM on real AudioTrack; short EOS, amplitude/silence, bounded backpressure and cancel; no model acceptance");
    } catch (Throwable e) { result.putString("error", e.toString()); }
    finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
  }
}
