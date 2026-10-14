package dev.live.offline;
/** 20 ms frames. Confirm speech before cancelling; keep recording during output. */
final class SpeechSegmenter {
  static final int NONE=0, START=1, END=2;
  private int active, quiet, frames;
  private boolean speaking, awaitSilence;
  boolean speaking() { return speaking; }
  int quietFrames() { return quiet; }
  int accept(boolean voice) {
    quiet = voice ? 0 : quiet + 1;
    if (awaitSilence) {
      if (quiet >= 40) awaitSilence = false;
      return NONE;
    }
    if (!speaking) {
      active = voice ? active + 1 : 0;
      if (active < 8) return NONE;
      speaking = true; frames = 8; quiet = 0;
      return START;
    }
    frames++;
    if (quiet < 40 && frames < 750) return NONE;
    speaking = false; active = 0;
    // A 15s bound must not turn one long sound into repeated interruptions.
    awaitSilence = frames >= 750 && quiet < 40;
    return END;
  }
}
