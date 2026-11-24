package app.soundlink.android;

import java.util.*;

/**
 * Aggregate anonymous playback edges; this cannot identify two players with equal usage counts. A
 * rise in media/game playback becomes an event only after it is sustained, so short in-app sounds
 * and autoplay previews do not count as a user starting playback.
 */
final class PlaybackEdges {
  static final long DEFAULT_SUSTAIN_MS = 1500;
  private final Set<Integer> mediaUsages;
  private final long sustainMs;
  private Map<Integer, Integer> previous = new HashMap<>();
  private boolean initialized;
  private long pendingSince = -1;
  private int pendingFloor;
  int event;
  boolean media;

  PlaybackEdges(Set<Integer> mediaUsages) {
    this(mediaUsages, DEFAULT_SUSTAIN_MS);
  }

  PlaybackEdges(Set<Integer> mediaUsages, long sustainMs) {
    this.mediaUsages = Set.copyOf(mediaUsages);
    this.sustainMs = sustainMs;
  }

  void resetBaseline() {
    initialized = false;
    media = false;
    pendingSince = -1;
  }

  void update(Map<Integer, Integer> usages, long nowMs) {
    boolean newMedia = false;
    for (Map.Entry<Integer, Integer> usage : usages.entrySet()) {
      if (usage.getValue() > previous.getOrDefault(usage.getKey(), 0)) {
        newMedia |= mediaUsages.contains(usage.getKey());
      }
    }
    int mediaCount = mediaCount(usages);
    if (initialized && newMedia && pendingSince < 0) {
      pendingSince = nowMs;
      pendingFloor = mediaCount(previous);
    }
    // The new player must still be running above the pre-rise count when the threshold passes.
    if (pendingSince >= 0 && mediaCount <= pendingFloor) pendingSince = -1;
    if (pendingSince >= 0 && nowMs - pendingSince >= sustainMs) {
      event++;
      media = true;
      pendingSince = -1;
    }
    initialized = true;
    previous = new HashMap<>(usages);
    if (mediaCount == 0) media = false;
  }

  private int mediaCount(Map<Integer, Integer> usages) {
    int count = 0;
    for (int usage : mediaUsages) count += usages.getOrDefault(usage, 0);
    return count;
  }
}
