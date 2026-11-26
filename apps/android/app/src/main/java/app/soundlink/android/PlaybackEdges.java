package app.soundlink.android;

import java.util.*;

/**
 * Aggregate anonymous playback edges. A media/game count rise or a music-stream resume must be
 * sustained to qualify. Equal-count player replacements can still be invisible during playback.
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
  private int previousMediaCount;
  private boolean initialized, previousMusicActive;
  private long pendingSince = -1;
  private int pendingFloor;
  private boolean pendingResume;
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
    previousMusicActive = false;
    media = false;
    pendingSince = -1;
  }

  void update(Map<Integer, Integer> usages, boolean musicActive, long nowMs) {
    int mediaCount = mediaCount(usages);
    boolean countRose = mediaCount > previousMediaCount;
    boolean resumed = !previousMusicActive && musicActive && mediaCount > 0;
    if (initialized && pendingSince < 0 && (countRose || resumed)) {
      pendingSince = nowMs;
      pendingResume = !countRose;
      pendingFloor = pendingResume ? 0 : previousMediaCount;
    }
    if (mediaCount <= pendingFloor || (pendingResume && !musicActive)) pendingSince = -1;
    if (pendingSince >= 0 && nowMs - pendingSince >= sustainMs) {
      event++;
      media = true;
      pendingSince = -1;
    }
    initialized = true;
    previousMediaCount = mediaCount;
    previousMusicActive = musicActive;
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

  /** A count rise or resume is waiting for its sustain check. */
  boolean pending() {
    return pendingSince >= 0;
  }

  private int mediaCount(Map<Integer, Integer> usages) {
    int count = 0;
    for (int usage : mediaUsages) count += usages.getOrDefault(usage, 0);
    return count;
  }
}
