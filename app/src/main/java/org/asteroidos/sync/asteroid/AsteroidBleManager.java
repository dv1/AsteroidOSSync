package org.asteroidos.sync.asteroid;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import org.asteroidos.sync.connectivity.IConnectivityService;
import org.asteroidos.sync.connectivity.IServiceCallback;
import org.asteroidos.sync.services.SynchronizationService;
import org.asteroidos.sync.utils.AsteroidUUIDS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;

public class AsteroidBleManager extends BleManager {
    public static final String TAG = AsteroidBleManager.class.toString();
    @Nullable
    public BluetoothGattCharacteristic batteryCharacteristic;
    SynchronizationService mSynchronizationService;
    ArrayList<BluetoothGattService> mGattServices;
    public HashMap<UUID, IServiceCallback> recvCallbacks;
    public HashMap<UUID, BluetoothGattCharacteristic> sendingCharacteristics;

    // The GATT MTU. This size includes 3 extra bytes that are used for GATT metadata
    // (1 op-code byte and 2 attribute handle bytes).
    // Consequently, the maximum GATT payload size is MTU_SIZE - 3.
    private static final int MTU_SIZE = 256;

    public AsteroidBleManager(@NonNull final Context context, SynchronizationService syncService) {
        super(context);
        mSynchronizationService = syncService;
        mGattServices = new ArrayList<>();
        recvCallbacks = new HashMap<>();
    }

    public final void send(UUID characteristic, byte[] data) {
        writeCharacteristic(sendingCharacteristics.get(characteristic), data,
                Objects.requireNonNull(sendingCharacteristics.get(characteristic)).getWriteType()).enqueue();
    }

    @NonNull
    @Override
    protected final BleManagerGattCallback getGattCallback() {
        return new AsteroidBleManagerGattCallback() {
            @Override
            protected void onServicesInvalidated() {
                mSynchronizationService.unsyncServices();
                batteryCharacteristic = null;
                mGattServices.clear();
            }
        };
    }

    public final void abort() {
        cancelQueue();
    }

    @Override
    protected final void finalize() throws Throwable {
        super.finalize();
    }

    public final void setBatteryLevel(Data data) {
        BatteryLevelEvent batteryLevelEvent = new BatteryLevelEvent();
        batteryLevelEvent.battery = Objects.requireNonNull(data.getByte(0)).intValue();
        mSynchronizationService.handleUpdateBatteryPercentage(batteryLevelEvent);
    }

    public static class BatteryLevelEvent {
        public int battery = 0;
    }

    public final void readCharacteristics() {
        readCharacteristic(batteryCharacteristic).with(((device, data) -> setBatteryLevel(data))).enqueue();
    }

    public final int getMaxGattTransmissionSize() {
        return MTU_SIZE - 3; // subtract the 1 op-code byte and 2 attribute handle bytes
    }

    private abstract class AsteroidBleManagerGattCallback extends BleManagerGattCallback {

        /* It is a constraint of the Bluetooth library that it is required to initialize
          the characteristics in the isRequiredServiceSupported() function. */
        @Override
        public final boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
            final BluetoothGattService batteryService = gatt.getService(AsteroidUUIDS.BATTERY_SERVICE_UUID);

            boolean supported = true;

            boolean notify = false;
            if (batteryService != null) {
                batteryCharacteristic = batteryService.getCharacteristic(AsteroidUUIDS.BATTERY_UUID);

                if (batteryCharacteristic != null) {
                    final int properties = batteryCharacteristic.getProperties();
                    notify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                }
            }
            if (sendingCharacteristics == null)
                sendingCharacteristics = new HashMap<>();

            for (IConnectivityService service : mSynchronizationService.getServices().values()) {
                BluetoothGattService bluetoothGattService = gatt.getService(service.getServiceUUID());
                List<UUID> sendUuids = new ArrayList<>();
                service.getCharacteristicUUIDs().forEach((uuid, direction) -> {
                    if (direction == IConnectivityService.Direction.TO_WATCH)
                        sendUuids.add(uuid);
                });
                Log.d(TAG, "UUID " + sendUuids);

                for (UUID uuid : sendUuids) {
                    BluetoothGattCharacteristic characteristic = bluetoothGattService.getCharacteristic(uuid);
                    sendingCharacteristics.put(uuid, characteristic);
                    bluetoothGattService.addCharacteristic(characteristic);
                }
                recvCallbacks.forEach((characteristic, callback) -> {
                    BluetoothGattCharacteristic characteristic1 = bluetoothGattService.getCharacteristic(characteristic);
                    removeNotificationCallback(characteristic1);
                    setNotificationCallback(characteristic1).with((device, data) -> callback.call(data.getValue()));
                    enableNotifications(characteristic1).enqueue();
                });
            }

            supported = (batteryCharacteristic != null && notify);
            return supported;
        }

        @Override
        protected final void initialize() {
            beginAtomicRequestQueue()
                    .add(requestMtu(MTU_SIZE) // See the MTU_SIZE comments above for details about this
                            .with((device, mtu) -> log(Log.INFO, "MTU set to " + mtu))
                            .fail((device, status) -> log(Log.WARN, "Requested MTU not supported: " + status)))
                    .done(device -> log(Log.INFO, "Target initialized"))
                    .fail((device, status) -> Log.e("Init", device.getAddress() + " not initialized with error: " + status))
                    .enqueue();

            setNotificationCallback(batteryCharacteristic).with(((device, data) -> setBatteryLevel(data)));
	    // Do not call readCharacteristic(batteryCharacteristic) here.
	    // Otherwise, on Android 12 and later, the BLE bond to the watch
	    // is lost, and communication no longer works. Arguably, reading
	    // and writing should not be done in initialize(), since it is
	    // part of the BLE manager's connect() request. Instead, do those
	    // IO operations _after_ that request finishes (-> read the
	    // characteristic in the SynchronizationService class, which is
	    // where the BLE manager's connect() function is called).
            enableNotifications(batteryCharacteristic).enqueue();

            // Let all services know that we are connected.
            try {
                mSynchronizationService.syncServices();
            } catch (Exception e){
                e.printStackTrace();
            }
        }
    }
}