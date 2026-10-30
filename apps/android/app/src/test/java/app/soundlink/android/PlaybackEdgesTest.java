package app.soundlink.android;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class PlaybackEdgesTest {
  @Test
  public void initialAndRestoredPlaybackNeverBecomeNewEvents() {
    var e = new PlaybackEdges(Set.of(1, 14));
    e.update(Map.of(1, 1));
    assertEquals(0, e.event);
    assertFalse(e.media);
    e.update(Map.of());
    e.update(Map.of(1, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    e.resetBaseline();
    e.update(Map.of(1, 1));
    assertEquals(1, e.event);
    assertFalse(e.media);
  }

  @Test
  public void notificationOverExistingMusicIsNotANewMediaEvent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    e.update(Map.of());
    e.update(Map.of(1, 1));
    assertTrue(e.media);
    e.update(Map.of(1, 1, 5, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    e.update(Map.of(1, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
  }

  @Test
  public void mediaStopAndUnchangedCountsDoNotKeepAFalseCandidate() {
    var e = new PlaybackEdges(Set.of(1, 14));
    e.update(Map.of());
    e.update(Map.of(1, 1));
    e.update(Map.of(1, 1));
    assertEquals(1, e.event);
    e.update(Map.of(5, 1));
    assertFalse(e.media);
    e.update(Map.of());
    assertFalse(e.media);
  }

  @Test
  public void nonMediaSoundsNeitherStartNorCancelMediaIntent() {
    var e = new PlaybackEdges(Set.of(1, 14));
    e.update(Map.of());
    e.update(Map.of(5, 1));
    assertEquals(0, e.event);
    assertFalse(e.media);
    e.update(Map.of(1, 1, 5, 1));
    assertEquals(1, e.event);
    assertTrue(e.media);
    e.update(Map.of(1, 1, 5, 2));
    assertEquals(1, e.event);
    assertTrue(e.media);
    e.update(Map.of(5, 2));
    assertFalse(e.media);
    e.update(Map.of(1, 1));
    assertEquals(2, e.event);
    assertTrue(e.media);
  }
}
