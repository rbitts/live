package dev.live.offline;

/** Energy gate for 16 kHz capture; keep quiet syllables after speech starts. */
final class SpeechGate {
  private double noise = 0.0005;

  boolean voiced(double rms, boolean speaking) {
    double onset = Math.max(0.004, Math.min(0.025, noise * 3.5));
    double threshold = speaking ? onset * 0.55 : onset;
    boolean voice = rms > threshold;
    // Do not learn the speaker as background or raise the gate mid-sentence.
    if (!speaking && !voice) noise = noise * 0.98 + rms * 0.02;
    return voice;
  }
}
