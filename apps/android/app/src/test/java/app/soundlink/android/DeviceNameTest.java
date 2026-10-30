package app.soundlink.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceNameTest {
  @Test public void customBluetoothNameHasPriorityOverGenericSystemName() {
    assertEquals("My phone", DeviceName.choose("Xiaomi 17", " My phone "));
  }
  @Test public void unavailableBluetoothNameFallsBackWithoutUsingModelIdentifier() {
    assertEquals("Xiaomi 17", DeviceName.choose(" Xiaomi 17 ", null));
    assertEquals("Xiaomi 17", DeviceName.choose("Xiaomi 17", " \n"));
    assertEquals("My phone", DeviceName.choose(null, "My phone"));
    assertEquals("Android", DeviceName.choose(" \n", null));
  }
  @Test public void sanitizesControlsAndLimitsUnicodeWithoutSplittingSurrogates() {
    assertEquals("My phone", DeviceName.clean("My\n phone\u202E"));
    String name = DeviceName.clean("📱".repeat(70));
    assertEquals(64, name.codePointCount(0, name.length()));
    assertEquals("📱".repeat(64), name);
  }
}
