package dev.live.offline;
/** Exact stop-only phrases keep the floor with the user, without a spoken acknowledgement. */
final class SpeechIntent {
  static boolean stopOnly(String text) {
    String key=text.trim().replaceAll("[.!?。！？]+$", "").replaceAll("\\s+", "");
    key=key.replaceFirst("^(?:아[,，]?|어[,，]?|음[,，]?)", "");
    return java.util.Arrays.asList("잠깐","잠깐만","잠깐만요","잠시만","잠시만요","멈춰","그만","그만해","스톱","stop").contains(key);
  }
}
