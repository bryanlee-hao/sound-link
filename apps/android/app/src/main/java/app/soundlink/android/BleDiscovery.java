package app.soundlink.android;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.security.SecureRandom;
import java.util.*;

/** One bounded discovery attempt. All mutable GATT state is confined to the main looper. */
@android.annotation.SuppressLint(
    "MissingPermission") // start() checks both nearby-device grants; callbacks handle revocation.
final class BleDiscovery {
  interface Listener {
    void state(String text);

    void resolved(DiscoveryProtocol.Endpoint endpoint);

    void failed(String reason);
  }

  final Context context;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Listener listener;
  final String pin;
  final UUID service;
  BluetoothLeScanner scanner;
  BluetoothGatt gatt;
  ScanCallback callback;
  byte[] nonce;
  long challengeAt;
  boolean closed, scanning, discoveryStarted;
  final Runnable deadline = () -> fail("蓝牙查找超时，等待下一次发现事件");

  BleDiscovery(Context c, String pin, Listener listener) throws Exception {
    context = c.getApplicationContext();
    this.pin = pin;
    this.listener = listener;
    service = DiscoveryProtocol.serviceUUID(pin);
  }

  boolean permitted() {
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  void start(BluetoothDevice hint) {
    if (!permitted()) {
      fail("需要附近设备权限");
      return;
    }
    BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
    if (adapter == null || !adapter.isEnabled()) {
      fail("手机蓝牙已关闭");
      return;
    }
    handler.postDelayed(deadline, 25000);
    if (hint != null) {
      connect(hint);
      return;
    }
    listener.state("正在查找已配对的 Mac");
    scanner = adapter.getBluetoothLeScanner();
    if (scanner == null) {
      fail("蓝牙扫描不可用");
      return;
    }
    callback =
        new ScanCallback() {
          @Override
          public void onScanResult(int type, ScanResult result) {
            handler.post(
                () -> {
                  if (!closed && scanning) {
                    stopScan();
                    connect(result.getDevice());
                  }
                });
          }

          @Override
          public void onScanFailed(int error) {
            handler.post(() -> fail("蓝牙扫描受限（" + error + "）"));
          }
        };
    try {
      scanning = true;
      scanner.startScan(
          List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(service)).build()),
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          callback);
    } catch (SecurityException e) {
      fail("蓝牙权限被撤销");
    }
  }

  private void stopScan() {
    if (scanning && scanner != null && callback != null) {
      try {
        scanner.stopScan(callback);
      } catch (SecurityException ignored) {
      }
      scanning = false;
    }
  }

  private void connect(BluetoothDevice device) {
    if (closed) return;
    listener.state("发现候选 Mac · 正在读取地址");
    try {
      gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
      if (gatt == null) fail("无法建立蓝牙地址连接");
    } catch (SecurityException e) {
      fail("蓝牙权限被撤销");
    }
  }

  private final BluetoothGattCallback gattCallback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
          handler.post(
              () -> {
                if (closed || g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  fail("蓝牙连接失败（" + status + "）");
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                  try {
                    if (!g.requestMtu(247)) discover(g);
                    else handler.postDelayed(() -> discover(g), 1800);
                  } catch (SecurityException e) {
                    fail("蓝牙权限被撤销");
                  }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) fail("蓝牙地址连接已断开");
              });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
          handler.post(() -> discover(g));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          handler.post(
              () -> {
                if (closed || g != gatt) return;
                BluetoothGattService found = g.getService(service);
                if (status != BluetoothGatt.GATT_SUCCESS || found == null) {
                  fail("候选设备没有匹配的地址服务");
                  return;
                }
                BluetoothGattCharacteristic challenge =
                    found.getCharacteristic(DiscoveryProtocol.CHALLENGE);
                if (challenge == null
                    || found.getCharacteristic(DiscoveryProtocol.ENDPOINT) == null) {
                  fail("Mac 地址服务不完整");
                  return;
                }
                nonce = new byte[16];
                new SecureRandom().nextBytes(nonce);
                challengeAt = SystemClock.elapsedRealtime();
                try {
                  if (g.writeCharacteristic(
                          challenge, nonce, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                      != BluetoothStatusCodes.SUCCESS) fail("无法发送蓝牙身份挑战");
                } catch (SecurityException e) {
                  fail("蓝牙权限被撤销");
                }
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
          handler.post(
              () -> {
                if (closed || g != gatt || !c.getUuid().equals(DiscoveryProtocol.CHALLENGE)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  fail("Mac 未接受蓝牙身份挑战（" + status + "）");
                  return;
                }
                try {
                  if (!g.readCharacteristic(
                      g.getService(service).getCharacteristic(DiscoveryProtocol.ENDPOINT)))
                    fail("无法读取 Mac 最新地址");
                } catch (SecurityException e) {
                  fail("蓝牙权限被撤销");
                }
              });
        }

        @Override
        public void onCharacteristicRead(
            BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
          handler.post(
              () -> {
                if (closed || g != gatt || !c.getUuid().equals(DiscoveryProtocol.ENDPOINT)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  fail("读取 Mac 地址失败（" + status + "）");
                  return;
                }
                try {
                  if (nonce == null || SystemClock.elapsedRealtime() - challengeAt > 10000)
                    throw new java.io.IOException("蓝牙地址回应过期");
                  DiscoveryProtocol.Endpoint endpoint = DiscoveryProtocol.verify(value, nonce, pin);
                  close();
                  listener.resolved(endpoint);
                } catch (Exception e) {
                  fail(ConnectionService.friendly(e));
                }
              });
        }
      };

  private void discover(BluetoothGatt g) {
    if (closed || g != gatt || discoveryStarted) return;
    discoveryStarted = true;
    try {
      if (!g.discoverServices()) fail("无法发现 Mac 地址服务");
    } catch (SecurityException e) {
      fail("蓝牙权限被撤销");
    }
  }

  private void fail(String text) {
    if (closed) return;
    close();
    listener.failed(text);
  }

  void close() {
    closed = true;
    handler.removeCallbacks(deadline);
    stopScan();
    if (gatt != null) {
      try {
        gatt.disconnect();
        gatt.close();
      } catch (SecurityException ignored) {
      }
      gatt = null;
    }
  }
}
