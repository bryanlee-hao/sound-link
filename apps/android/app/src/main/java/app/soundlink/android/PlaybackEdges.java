package app.soundlink.android;

import java.util.*;

/**
 * Aggregate anonymous playback edges; this cannot identify two players with equal usage counts. A
 * rise in media/game playback becomes an event only after it is sustained, so short in-app sounds
 * and autoplay previews do not count as a user starting playback.
 */
final class PlaybackEdges {
  /**
   * Only media/game usages count, so system notification, ringtone and alarm sounds never reach
   * this filter. It only drops short in-app media sounds (UI/game effects, previews), which are
   * typically well under a second, while the provisional mute hides the wait from the speaker.
   */
  static final long DEFAULT_SUSTAIN_MS = 800;
  /** Re-sample just after the threshold instead of waiting for the next 1-second poll. */
  static final long SUSTAIN_CHECK_MARGIN_MS = 30;
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

  /**
   * Muting STREAM_MUSIC briefly hides playback configurations while music keeps playing. During
   * our own provisional mute that gap is not a stop or an unreadable state, so keep the last
   * visible usages; outside that bounded mute the gap stays visible and fails closed.
   */
  static Map<Integer, Integer> visibleUsages(
      Map<Integer, Integer> current, boolean musicActive, boolean provisionalMute,
      Map<Integer, Integer> lastVisible) {
    if (musicActive && current.isEmpty() && provisionalMute && lastVisible != null
        && !lastVisible.isEmpty()) return new TreeMap<>(lastVisible);
    return current;
  }

  /** A media rise is running but has not yet been sustained long enough to be an event. */
  boolean pending() {
    return pendingSince >= 0;
  }

  private int mediaCount(Map<Integer, Integer> usages) {
    int count = 0;
    for (int usage : mediaUsages) count += usages.getOrDefault(usage, 0);
    return count;
  }
}
