package app.soundlink.android;

import android.companion.*;
import android.net.*;

public final class MacCompanionService extends CompanionDeviceService {
  private ConnectivityManager.NetworkCallback network;

  @Override
  public void onCreate() {
    super.onCreate();
    Diagnostics.record(this, "伴生服务创建", "系统绑定，不等于进程重启");
    network =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network n) {
            Recovery.wake(MacCompanionService.this, "伴生服务网络可用回调", null);
          }
        };
    getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(network);
  }

  @Override
  public void onDevicePresenceEvent(DevicePresenceEvent event) {
    int type = event.getEvent();
    Diagnostics.event(
        this,
        "companion_callback",
        "伴生设备事件",
        Diagnostics.fields("event", type, "association", event.getAssociationId()),
        false);
    if (type == DevicePresenceEvent.EVENT_BLE_APPEARED
        || type == DevicePresenceEvent.EVENT_BT_CONNECTED)
      Recovery.presence(this, event.getAssociationId(), true);
    else if (type == DevicePresenceEvent.EVENT_BLE_DISAPPEARED
        || type == DevicePresenceEvent.EVENT_BT_DISCONNECTED)
      Recovery.presence(this, event.getAssociationId(), false);
    else if (type == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED
        && event.getAssociationId() == Link.prefs(this).getInt("association_id", -1)) {
      Recovery.disable(this);
      Recovery.status(this, "Mac 系统关联已被移除");
    }
  }

  @Override
  public void onDeviceAppeared(AssociationInfo info) {
    Recovery.presence(this, info.getId(), true);
  }

  @Override
  public void onDeviceDisappeared(AssociationInfo info) {
    Recovery.presence(this, info.getId(), false);
  }

  @Override
  public void onDestroy() {
    Diagnostics.record(this, "伴生服务销毁", "系统解除服务绑定");
    if (network != null)
      getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
    super.onDestroy();
  }
}
