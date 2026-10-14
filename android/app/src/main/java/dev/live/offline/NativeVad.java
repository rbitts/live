package dev.live.offline;
/** One classifier per microphone capture; never shares the model worker. */
final class NativeVad implements AutoCloseable {
  static { System.loadLibrary("live_jni"); }
  private long handle = create();
  private static native long create();
  private static native boolean process(long handle, short[] pcm);
  private static native void release(long handle);
  void reset() { close(); handle = create(); }
  boolean voiced(short[] pcm) { return process(handle, pcm); }
  public void close() { if (handle != 0) { release(handle); handle = 0; } }
}
