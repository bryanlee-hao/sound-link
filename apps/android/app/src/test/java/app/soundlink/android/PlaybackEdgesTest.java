package app.soundlink.android;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class PlaybackEdgesTest {
  private long now = 10_000;

  /** Repeats the same sample long enough to pass the sustain threshold. */
  private void sustain(PlaybackEdges e, Map<Integer, Integer> usages) {
    e.update(usages, now);
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS;
    e.update(usages, now);
  }

  private void step(PlaybackEdges e, Map<Integer, Integer> usages) {
    now += 200;
    e.update(usages, now);
  }

  @Test
  public void initialAndRestoredPlaybackNeverBecomeNewEvents() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of(1, 1));
    assertEquals(0, e.event);
    assertFalse(e.media);
    step(e, Map.of());
    sustain(e, Map.of(1, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    e.resetBaseline();
    sustain(e, Map.of(1, 1));
    assertEquals(1, e.event);
    assertFalse(e.media);
  }

  @Test
  public void shortMediaSoundBelowThresholdIsNotAnEvent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    step(e, Map.of(1, 1));
    step(e, Map.of(1, 1));
    assertEquals(0, e.event);
    assertFalse(e.media);
    step(e, Map.of());
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS;
    step(e, Map.of());
    assertEquals(0, e.event);
  }

  @Test
  public void mediaQualifiesOnceSustainedAndDoesNotRepeat() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    step(e, Map.of(1, 1));
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS - 300;
    e.update(Map.of(1, 1), now);
    assertEquals(0, e.event);
    step(e, Map.of(1, 1));
    step(e, Map.of(1, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    now += 10_000;
    step(e, Map.of(1, 1));
    assertEquals(1, e.event);
  }

  @Test
  public void shortSecondPlayerOverExistingMusicIsNotAnEvent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of(1, 1));
    step(e, Map.of(1, 2));
    step(e, Map.of(1, 1));
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS;
    step(e, Map.of(1, 1));
    assertEquals(0, e.event);
    sustain(e, Map.of(1, 2));
    assertEquals(1, e.event);
    assertTrue(e.media);
  }

  @Test
  public void notificationOverExistingMusicIsNotANewMediaEvent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    sustain(e, Map.of(1, 1));
    assertTrue(e.media);
    sustain(e, Map.of(1, 1, 5, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    step(e, Map.of(1, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
  }

  @Test
  public void mediaStopAndUnchangedCountsDoNotKeepAFalseCandidate() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    sustain(e, Map.of(1, 1));
    step(e, Map.of(1, 1));
    assertEquals(1, e.event);
    step(e, Map.of(5, 1));
    assertFalse(e.media);
    step(e, Map.of());
    assertFalse(e.media);
  }

  @Test
  public void nonMediaSoundsNeitherStartNorCancelMediaIntent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    sustain(e, Map.of(5, 1));
    assertEquals(0, e.event);
    assertFalse(e.media);
    sustain(e, Map.of(1, 1, 5, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    step(e, Map.of(1, 1, 5, 2));
    assertEquals(1, e.event);
    assertTrue(e.media);
    step(e, Map.of(5, 2));
    assertFalse(e.media);
    sustain(e, Map.of(1, 1));
    assertEquals(2, e.event);
    assertTrue(e.media);
  }

  @Test
  public void pendingTracksAMediaRiseUntilItIsSustainedOrStops() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    assertFalse(e.pending());
    step(e, Map.of(1, 1));
    assertTrue(e.pending());
    step(e, Map.of());
    assertFalse(e.pending());
    assertEquals(0, e.event);
    step(e, Map.of(1, 1));
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS;
    e.update(Map.of(1, 1), now);
    assertFalse(e.pending());
    assertEquals(1, e.event);
    e.resetBaseline();
    assertFalse(e.pending());
  }

  @Test
  public void ownProvisionalMuteGapKeepsLastVisibleUsagesOnly() {
    Map<Integer, Integer> last = Map.of(1, 1);
    assertEquals(last, PlaybackEdges.visibleUsages(Map.of(), true, true, last));
    assertEquals(Map.of(), PlaybackEdges.visibleUsages(Map.of(), true, false, last));
    assertEquals(Map.of(), PlaybackEdges.visibleUsages(Map.of(), false, true, last));
    assertEquals(Map.of(), PlaybackEdges.visibleUsages(Map.of(), true, true, null));
    assertEquals(Map.of(14, 1), PlaybackEdges.visibleUsages(Map.of(14, 1), true, true, last));
  }

  @Test
  public void sustainSurvivesProvisionalMuteGap() {
    var e = new PlaybackEdges(Set.of(1, 14));
    step(e, Map.of());
    step(e, Map.of(1, 1));
    Map<Integer, Integer> seen = PlaybackEdges.visibleUsages(Map.of(), true, true, Map.of(1, 1));
    now += PlaybackEdges.DEFAULT_SUSTAIN_MS;
    e.update(seen, now);
    assertEquals(1, e.event);
    assertTrue(e.media);
  }
}
