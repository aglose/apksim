package dev.apksim.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothDevice;

/** Remote devices as the stack knows them (name and type once seen, bond state), and the shadows Ble needs. */
public final class Devices {
  private Devices() {}

  static BluetoothDevice device(String address) {
    return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
  }

  /** The device behind a sighting: from now on it has the peripheral's name and is an LE device, as after a scan. */
  static BluetoothDevice seen(Peripheral p) {
    BluetoothDevice d = device(p.address);
    ShadowBluetoothDevice s = Shadow.extract(d);
    s.setName(p.name);
    s.setType(BluetoothDevice.DEVICE_TYPE_LE);
    return d;
  }

  /** The bond state changes, with ACTION_BOND_STATE_CHANGED on the main thread as AdapterService sends it. */
  static void bondState(BluetoothDevice d, int state, int previous) {
    ShadowBluetoothDevice s = Shadow.extract(d);
    s.setBondState(state);
    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
    ShadowBluetoothAdapter sa = Shadow.extract(a);
    Set<BluetoothDevice> bonded = new HashSet<>();
    for (String address : Ble.bondedAddresses()) bonded.add(a.getRemoteDevice(address));
    if (state == BluetoothDevice.BOND_BONDED) bonded.add(d);
    if (state == BluetoothDevice.BOND_NONE) bonded.remove(d);
    sa.setBondedDevices(bonded);
    Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, d)
        .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state)
        .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous);
    new Handler(Looper.getMainLooper()).post(() -> RuntimeEnvironment.getApplication().sendBroadcast(intent));
  }

  /**
   * Robolectric's scanner shadow, replaced by one with no methods: the framework's own BluetoothLeScanner runs,
   * talking to Ble.ScanService through BluetoothAdapter.getBluetoothScan().
   */
  @Implements(value = BluetoothLeScanner.class, isInAndroidSdk = false)
  public static class ShadowSimBluetoothLeScanner {}

  /** Likewise for BluetoothGatt: the framework's own, talking to Ble.GattService. */
  @Implements(value = BluetoothGatt.class, isInAndroidSdk = false)
  public static class ShadowSimBluetoothGatt {}

  /**
   * connectGatt goes to the framework's own (Robolectric's made a BluetoothGatt of its own shadow), and bonding to
   * Ble. Everything else is Robolectric's ShadowBluetoothDevice.
   */
  @Implements(value = BluetoothDevice.class, isInAndroidSdk = false)
  public static class ShadowSimBluetoothDevice extends ShadowBluetoothDevice {
    @RealObject BluetoothDevice self;

    @Implementation
    @Override
    protected BluetoothGatt connectGatt(Context context, boolean autoConnect, BluetoothGattCallback callback) {
      return connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_AUTO);
    }

    @Implementation
    @Override
    protected BluetoothGatt connectGatt(Context context, boolean autoConnect, BluetoothGattCallback callback, int transport) {
      return connectGatt(context, autoConnect, callback, transport, BluetoothDevice.PHY_LE_1M_MASK);
    }

    @Implementation
    @Override
    protected BluetoothGatt connectGatt(Context context, boolean autoConnect, BluetoothGattCallback callback, int transport, int phy) {
      return connectGatt(context, autoConnect, callback, transport, phy, null);
    }

    @Implementation
    @Override
    protected BluetoothGatt connectGatt(Context context, boolean autoConnect, BluetoothGattCallback callback, int transport,
        int phy, Handler handler) {
      return self.connectGatt(context, autoConnect, callback, transport, false, phy, handler);
    }

    @Implementation
    @Override
    protected boolean createBond() {
      return Ble.createBond(self);
    }

    @Implementation
    @Override
    protected boolean createBond(int transport) {
      return Ble.createBond(self);
    }

    @Implementation
    @Override
    protected boolean removeBond() {
      return Ble.removeBond(self);
    }
  }
}
