package app.soundlink.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** HyperOS 3 template data only. The existing reconnect flow remains the sole action/timer owner.
 * Official templates (2026-01-29): iconTextInfo + textButton, imageTextInfoLeft + sameWidthDigitInfo.
 * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 */
final class XiaomiIslandPayload {
  static final String PARAM = "miui.focus.param";
  static final String PICS = "miui.focus.pics";
  static final String ACTIONS = "miui.focus.actions";
  static final String ICON = "miui.focus.pic_headphones";
  static final String IGNORE = "miui.focus.action_ignore";
  static final String CONNECT = "miui.focus.action_connect";

  static JSONObject create(String title, String detail, String summary, boolean waiting,
      long remainingMs, long wallNow, boolean first, String ignoreTitle, String connectTitle)
      throws org.json.JSONException {
    if (remainingMs <= 0 || remainingMs > OfflineReconnectFlow.CONNECT_MS)
      throw new IllegalArgumentException("Expired or unbounded island notification");
    int seconds = (int) ((remainingMs + 999) / 1000);
    String[] lines = detail.split("\n", 2);
    JSONObject iconText = new JSONObject()
        .put("animIconInfo", new JSONObject().put("type", 0).put("src", ICON).put("srcDark", ICON))
        .put("title", title).put("content", lines[0]);
    if (lines.length == 2) iconText.put("subContent", lines[1]);
    JSONObject pic = new JSONObject().put("type", 1).put("pic", ICON);
    JSONObject timer = new JSONObject().put("timerType", -1)
        .put("timerWhen", wallNow + remainingMs).put("timerSystemCurrent", wallNow)
        .put("timerTotal", OfflineReconnectFlow.CONNECT_MS);
    JSONObject island = new JSONObject()
        .put("islandProperty", waiting ? 2 : 1)
        .put("islandOrder", false)
        .put("islandTimeout", seconds)
        .put("bigIslandArea", new JSONObject()
            .put("imageTextInfoLeft", new JSONObject().put("type", 1).put("picInfo", pic)
                .put("textInfo", new JSONObject().put("title", summary)))
            .put("sameWidthDigitInfo", new JSONObject().put("timerInfo", timer)))
        .put("smallIslandArea", new JSONObject().put("picInfo", pic));
    JSONObject data = new JSONObject().put("protocol", 1).put("business", "headphone_reconnect")
        .put("islandFirstFloat", first).put("enableFloat", false).put("updatable", true)
        .put("reopen", first ? "reopen" : "close")
        .put("filterWhenNoPermission", false)
        .put("ticker", summary).put("tickerPic", ICON).put("tickerPicDark", ICON)
        .put("aodTitle", detail.replace('\n', ' ')).put("aodPic", ICON)
        .put("iconTextInfo", iconText)
        .put("picInfo", new JSONObject().put("type", 1))
        .put("param_island", island);
    if (waiting) {
      if (ignoreTitle == null || ignoreTitle.isBlank() || connectTitle == null || connectTitle.isBlank())
        throw new IllegalArgumentException("Both island button labels must be present");
      // PendingIntent references, never serialized intent URIs or exported receivers.
      // HyperOS 4 reads actionTitle before resolving Action.title; omission crashes SystemUI.
      data.put("textButton", new JSONArray()
          .put(new JSONObject().put("action", IGNORE).put("actionTitle", ignoreTitle)
              .put("clickWithCollapse", true))
          .put(new JSONObject().put("action", CONNECT).put("actionTitle", connectTitle)
              .put("clickWithCollapse", false)));
    }
    return new JSONObject().put("param_v2", data);
  }
}
