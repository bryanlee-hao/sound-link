package app.soundlink.android;

import java.util.*;

/** Aggregate anonymous playback edges; this cannot identify two players with equal usage counts. */
final class PlaybackEdges {
  private final Set<Integer> mediaUsages;
  private Map<Integer, Integer> previous = new HashMap<>();
  private boolean initialized;
  int event;
  boolean media;

  PlaybackEdges(Set<Integer> mediaUsages) {
    this.mediaUsages = Set.copyOf(mediaUsages);
  }

  void resetBaseline() {
    initialized = false;
    media = false;
  }

  void update(Map<Integer, Integer> usages) {
    boolean newMedia = false;
    for (Map.Entry<Integer, Integer> usage : usages.entrySet()) {
      if (usage.getValue() > previous.getOrDefault(usage.getKey(), 0)) {
        newMedia |= mediaUsages.contains(usage.getKey());
      }
    }
    if (initialized && newMedia) {
      event++;
      media = true;
    }
    initialized = true;
    previous = new HashMap<>(usages);
    if (mediaUsages.stream().noneMatch(key -> usages.getOrDefault(key, 0) > 0)) media = false;
  }
}
