package dev.live.offline;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Audible copy of synthetic capture, never an ASR input source.
 * Capture must not wait for playback: a bounded AudioTrack uses nonblocking writes.
 * Owned and closed by the capture thread, independently of assistant cancellation. */
final class MockInputMonitor implements AutoCloseable {
  private AudioTrack track;
  long written, dropped, played;
  private long finishedFrames;
  int routeType;
  private boolean failed;
  void write(short[] pcm,boolean enabled) {
    if(!enabled){stop();return;}
    if(failed)return;
    try {
      if(track==null){
        int minimum=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0||minimum>64000)throw new IllegalStateException("monitor buffer");
        track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum,16000)).build();
        if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("monitor initialization");
        // Play the selected input voice naturally; no pitch or speed conversion.
        if(android.os.Build.VERSION.SDK_INT>=31)track.setStartThresholdInFrames(320);
        track.play();
      }
      int accepted=track.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);
      if(accepted<0)throw new IllegalStateException("monitor write "+accepted);
      written+=accepted;dropped+=pcm.length-accepted;
      played=finishedFrames+Integer.toUnsignedLong(track.getPlaybackHeadPosition());
      android.media.AudioDeviceInfo route=track.getRoutedDevice();routeType=route==null?0:route.getType();
    }catch(RuntimeException error){
      failed=true;stop();android.util.Log.w("LiveVoice","mock monitor unavailable",error);
    }
  }
  private void stop(){if(track!=null){try{finishedFrames+=Integer.toUnsignedLong(track.getPlaybackHeadPosition());played=finishedFrames;track.pause();track.flush();}finally{track.release();track=null;}}}
  public void close(){stop();}
}
