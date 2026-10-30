package app.soundlink.android;

import org.json.JSONObject;

/** Pure command gates shared by the live executor and tests. */
final class HandoffCommand {
  static long budget(JSONObject command, String epoch, long roundTripMs, long queuedMs) {
    if (epoch.isEmpty()
        || !epoch.equals(command.optString("epoch"))
        || roundTripMs < 0
        || queuedMs < 0) return 0;
    long ttl = command.optLong("ttlMs", 0);
    if (ttl <= 0 || ttl > 5000) return 0;
    return Math.max(0, ttl - roundTripMs - queuedMs);
  }

  static boolean matches(
      JSONObject command, JSONObject target, JSONObject sample, boolean connecting) {
    if (!sameTarget(command, target, sample)
        || command.optInt("event", -1) != sample.optInt("event")
        || !audioReadable(sample)
        || (!connecting && sample.optBoolean("protectedAudio", true))) return false;
    return !connecting
        || (sample.optBoolean("media") && "active".equals(sample.optString("playing")));
  }

  static boolean audioReadable(JSONObject sample) {
    return sample != null
        && !"unknown".equals(sample.optString("playing", "unknown"))
        && sample.optBoolean(
            "audioReadable",
            !sample.optBoolean("protectedAudio", true)
                && !"unknown".equals(sample.optString("playing", "unknown")));
  }

  static boolean explicitConnectMatches(JSONObject command, JSONObject target, JSONObject sample) {
    return explicitConnectMatches(command, target, sample, false);
  }

  static boolean explicitConnectMatches(
      JSONObject command, JSONObject target, JSONObject sample, boolean ownsPromptMute) {
    return sameTarget(command, target, sample) && promptPlayback(sample, ownsPromptMute);
  }

  static boolean promptPlayback(JSONObject sample, boolean ownsPromptMute) {
    if (sample == null || sample.optDouble("ageMs", 10000) >= 10000
        || !"active".equals(sample.optString("playing"))) return false;
    if (audioReadable(sample) && sample.optBoolean("mediaActive")) return true;
    // Android hides playback configurations while STREAM_MUSIC is muted. Only the owner of
    // this finite, previously eligible prompt may use the live music signal during that mute.
    // Do not mark the observation audioReadable or grant any automatic/new playback edge.
    return ownsPromptMute && sample.optBoolean("musicActive")
        && sample.optBoolean("playbackConfigurationsEmpty") && sample.optBoolean("audioModeNormal");
  }

  static boolean sameTarget(JSONObject command, JSONObject target, JSONObject sample) {
    return sample != null
        && !command.optString("id").isEmpty()
        && !target.optString("phoneAddress").isEmpty()
        && command.optInt("revision", -1) == target.optInt("revision")
        && sample.optInt("targetRevision", -1) == target.optInt("revision")
        && command.optString("address").equalsIgnoreCase(target.optString("phoneAddress"))
        && command.optString("instance").equals(sample.optString("instance"));
  }
}
