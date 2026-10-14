package dev.live.offline;

import java.util.Arrays;

/** One bounded synthetic utterance, read by the same paced microphone loop. */
final class MockAudioInput {
  static final int MAX_SECONDS = 12;
  private boolean enabled;
  private short[] pending;
  private int offset;
  private long delivered;

  synchronized boolean enabled() { return enabled; }
  synchronized boolean feeding() { return pending != null; }
  synchronized long deliveredSamples() { return delivered; }
  synchronized void enable() { enabled = true; }
  synchronized void clear() { pending = null; offset = 0; }
  synchronized void disable() { clear(); enabled = false; }

  synchronized void offer(short[] pcm) {
    if (!enabled) throw new IllegalStateException("모의 입력을 먼저 켜세요.");
    if (pending != null) throw new IllegalStateException("이전 모의 음성 입력이 끝난 뒤 보내세요.");
    if (pcm.length == 0 || pcm.length > 16000 * MAX_SECONDS)
      throw new IllegalArgumentException("모의 음성은 12초 이내로 입력하세요.");
    // Warm the detector, preserve the first syllable and use the real endpoint guard.
    pending = new short[6400 + pcm.length + 19200];
    System.arraycopy(pcm, 0, pending, 6400, pcm.length);
    offset = 0;
  }

  synchronized boolean read(short[] frame) {
    if (!enabled) return false;
    Arrays.fill(frame, (short) 0);
    if (pending != null) {
      int count = Math.min(frame.length, pending.length - offset);
      System.arraycopy(pending, offset, frame, 0, count);
      offset += count; delivered += count;
      if (offset == pending.length) clear();
    }
    return true;
  }

  static short[] from24k(float[] source, int length) {
    if (length <= 0 || length > source.length || length > 24000 * MAX_SECONDS)
      throw new IllegalArgumentException("모의 음성은 12초 이내로 입력하세요.");
    short[] out = new short[length * 2 / 3];
    for (int i = 0; i < out.length; i++) {
      double at = i * 1.5;
      int lo = (int) at;
      float f = (float) (at - lo);
      float sample = source[lo] * (1 - f) + source[Math.min(lo + 1, length - 1)] * f;
      if (!Float.isFinite(sample)) throw new IllegalArgumentException("잘못된 음성 값");
      out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(sample * 32768)));
    }
    return out;
  }
}
