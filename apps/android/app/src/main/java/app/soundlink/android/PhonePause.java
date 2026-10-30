package app.soundlink.android;

import android.content.*;
import android.media.session.*;
import android.service.notification.NotificationListenerService;
import java.util.*;
import org.json.JSONObject;

/** Optional precise media-session control. Does not read or store notifications. */
public final class PhonePause extends NotificationListenerService {
  private static MediaController captured;
  private static String edge = "";
  private static long stateUpdated;

  private static List<MediaController> sessions(Context c) {
    try {
      return c.getSystemService(MediaSessionManager.class)
          .getActiveSessions(new ComponentName(c, PhonePause.class));
    } catch (SecurityException e) {
      return List.of();
    }
  }

  static synchronized void capture(Context c) {
    JSONObject s = PhoneObservation.wire(c);
    if (s == null) return;
    String key = s.optString("instance") + ":" + s.optInt("event");
    if (edge.equals(key)) return;
    edge = key;
    captured = null;
    List<MediaController> playing =
        sessions(c).stream()
            .filter(
                m ->
                    m.getPlaybackState() != null
                        && m.getPlaybackState().getState() == PlaybackState.STATE_PLAYING)
            .toList();
    if (playing.size() == 1) {
      captured = playing.get(0);
      stateUpdated = captured.getPlaybackState().getLastPositionUpdateTime();
    }
  }

  static synchronized String pause(Context c, JSONObject command) {
    JSONObject s = PhoneObservation.wire(c);
    if (s == null || !"disconnected".equals(s.optString("connection"))) return "手机仍连接耳机或状态未知，跳过暂停";
    if (!"active".equals(s.optString("playing"))) return "手机已停止播放，无需暂停";
    if (!edge.equals(command.optString("instance") + ":" + command.optInt("event"))
        || captured == null) return "无已授权且明确的旧媒体会话，跳过暂停";
    try {
      MediaController current =
          sessions(c).stream()
              .filter(m -> m.getSessionToken().equals(captured.getSessionToken()))
              .findFirst()
              .orElse(null);
      if (current == null
          || current.getPlaybackState() == null
          || current.getPlaybackState().getState() != PlaybackState.STATE_PLAYING)
        return "旧会话已停止，跳过暂停";
      if (current.getPlaybackState().getLastPositionUpdateTime() != stateUpdated)
        return "播放器状态已变化，跳过迟到暂停";
      current.getTransportControls().pause();
      return "已向旧手机媒体会话发送 pause（尽力执行）";
    } catch (Exception e) {
      return "手机暂停失败，已跳过";
    }
  }
}
