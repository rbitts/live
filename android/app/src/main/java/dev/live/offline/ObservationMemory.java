package dev.live.offline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Session-only evidence from the latest frame and two previous questions. */
final class ObservationMemory {
  static final class Frame {
    final byte[] jpeg;
    final long at;
    final long sourceMs;
    final String source;

    Frame(byte[] jpeg, long at) {
      this(jpeg, at, "camera-request", -1);
    }
    Frame(byte[] jpeg, long at, String source, long sourceMs) {
      this.jpeg = jpeg.clone();
      this.at = at;
      this.source = source;
      this.sourceMs = sourceMs;
    }
  }

  static final class Evidence {
    final Frame frame;
    final boolean current;

    Evidence(Frame frame, boolean current) {
      this.frame = frame;
      this.current = current;
    }
  }

  private Frame latest;
  // Short alignment ring, separate from remembered observations. 8 frames / 4 MiB.
  private final ArrayDeque<Frame> recent = new ArrayDeque<>();
  private int recentBytes;
  private final ArrayDeque<Frame> history = new ArrayDeque<>();
  private int capacity = 2;
  private long retentionMs = 300000;

  synchronized void configure(int count, long durationMs) {
    if (count < 0 || count > 2 || durationMs < 1000 || durationMs > 900000)
      throw new IllegalArgumentException("memory settings");
    capacity = count;
    retentionMs = durationMs;
    while (history.size() > capacity) history.removeFirst();
  }

  synchronized List<Frame> snapshot(long now) {
    expire(now);
    return new ArrayList<>(history);
  }

  synchronized void forget(long at) {
    history.removeIf(frame -> frame.at == at);
    if (latest != null && latest.at == at) latest = null;
    recent.removeIf(frame -> frame.at == at);
    recentBytes = 0;
    for (Frame frame : recent) recentBytes += frame.jpeg.length;
  }

  private void expire(long now) {
    history.removeIf(frame -> now < frame.at || now - frame.at > retentionMs);
  }

  synchronized void observe(byte[] jpeg, long at) {
    observe(jpeg, at, "camera-request", -1);
  }
  synchronized void observe(byte[] jpeg, long at, String source, long sourceMs) {
    if (jpeg.length > 2 * 1024 * 1024 || at < 0) throw new IllegalArgumentException("frame limit");
    if (latest != null && at <= latest.at) return;
    latest = new Frame(jpeg, at, source, sourceMs);
    recent.addLast(latest); recentBytes += jpeg.length;
    while (recent.size() > 8 || recentBytes > 4 * 1024 * 1024
        || (!recent.isEmpty() && at - recent.peekFirst().at > 4000))
      recentBytes -= recent.removeFirst().jpeg.length;
  }

  synchronized Frame aligned(long anchor) {
    Frame selected = null;
    for (Frame frame : recent)
      if (frame.at <= anchor && anchor - frame.at <= 3000) selected = frame;
    return selected;
  }

  synchronized List<Evidence> alignedQuestion(long anchor) {
    expire(anchor);
    Frame selected = aligned(anchor);
    List<Evidence> result = new ArrayList<>();
    if (selected != null) {
      result.add(new Evidence(selected, true));
      history.removeIf(f -> f.at == selected.at);
      if (capacity > 0) history.addLast(selected);
      while (history.size() > capacity) history.removeFirst();
    }
    return result;
  }

  synchronized List<Evidence> question(long now) {
    expire(now);
    List<Evidence> result = new ArrayList<>();
    for (Frame frame : history) if (frame.at <= now) result.add(new Evidence(frame, false));
    if (latest != null && now >= latest.at && now - latest.at <= 3000) {
      result.removeIf(e -> e.frame.at == latest.at);
      result.add(new Evidence(latest, true));
      history.removeIf(f -> f.at == latest.at);
      if (capacity > 0) history.addLast(latest);
      while (history.size() > capacity) history.removeFirst();
    }
    return result;
  }

  synchronized void cameraOff() {
    latest = null;
    recent.clear(); recentBytes = 0;
  }

  // The mobile GPU path consumes one image per turn. Keep history stored for
  // inspection/recall, but never batch several image encodings in one request.
  synchronized List<Evidence> liveQuestion(long now) {
    List<Evidence> all = question(now);
    List<Evidence> result = new ArrayList<>();
    if (!all.isEmpty()) result.add(all.get(all.size() - 1));
    return result;
  }

  synchronized Frame current(long now) {
    return latest != null && now >= latest.at && now - latest.at <= 3000 ? latest : null;
  }

  synchronized void reset() {
    cameraOff();
    history.clear();
  }
}
