/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.viewmodel.scanner;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import java.util.List;

import javax.inject.Inject;

import no.nordicsemi.android.mcumgr.sample.fragment.scanner.ScannerFragment;
import no.nordicsemi.android.mcumgr.sample.utils.FilterUtils;
import no.nordicsemi.android.mcumgr.sample.utils.Utils;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;
import timber.log.Timber;

public class ScannerViewModel extends AndroidViewModel {
    private static final String PREFS_FILTER_NAMED_ONLY = "filter_names";
    private static final String PREFS_FILTER_NEARBY_ONLY = "filter_nearby";

    /** MutableLiveData containing the list of devices. */
    private final DevicesLiveData devicesLiveData;
    /** MutableLiveData containing the scanner state. */
    private final ScannerStateLiveData scannerStateLiveData;

    private final SharedPreferences preferences;

    public DevicesLiveData getDevices() {
        return devicesLiveData;
    }

    public ScannerStateLiveData getScannerState() {
        return scannerStateLiveData;
    }

    @Inject
    public ScannerViewModel(@NonNull final Application application,
                            @NonNull final SharedPreferences preferences) {
        super(application);
        this.preferences = preferences;

        final boolean filterNamedOnly = isNameFilterEnabled();
        final boolean filerNearbyOnly = isNearbyFilterEnabled();

        scannerStateLiveData = new ScannerStateLiveData(
                Utils.isBleEnabled(),
                Utils.isLocationEnabled(application)
        );
        devicesLiveData = new DevicesLiveData(filterNamedOnly, filerNearbyOnly);
        registerBroadcastReceivers(application);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        unregisterBroadcastReceivers(getApplication());
    }

    public boolean isNameFilterEnabled() {
        return preferences.getBoolean(PREFS_FILTER_NAMED_ONLY, true);
    }

    public boolean isNearbyFilterEnabled() {
        return preferences.getBoolean(PREFS_FILTER_NEARBY_ONLY, false);
    }

    /**
     * Forces the observers to be notified. This method is used to refresh the screen after the
     * location permission has been granted. In result, the observer in
     * {@link ScannerFragment} will try to start scanning.
     */
    public void refresh() {
        scannerStateLiveData.refresh();
    }

    /**
     * Forgets discovered devices.
     */
    public void clear() {
        devicesLiveData.clear();
        scannerStateLiveData.clearRecords();
    }

    /**
     * Updates the device filter. Devices that once passed the filter will still be shown
     * even if they move away from the phone, or change the advertising packet. This is to
     * avoid removing devices from the list.
     *
     * @param nameRequired if true, the list will display only devices Local Name
     *                     in the advertising packet.
     */
    public void filterByName(final boolean nameRequired) {
        preferences.edit().putBoolean(PREFS_FILTER_NAMED_ONLY, nameRequired).apply();
        if (devicesLiveData.filterByName(nameRequired))
            scannerStateLiveData.recordFound();
        else
            scannerStateLiveData.clearRecords();
    }

    /**
     * Updates the device filter. Devices that once passed the filter will still be shown
     * even if they move away from the phone, or change the advertising packet. This is to
     * avoid removing devices from the list.
     *
     * @param nearbyOnly if true, the list will show only devices with high RSSI.
     */
    public void filterByDistance(final boolean nearbyOnly) {
        preferences.edit().putBoolean(PREFS_FILTER_NEARBY_ONLY, nearbyOnly).apply();
        if (devicesLiveData.filterByDistance(nearbyOnly))
            scannerStateLiveData.recordFound();
        else
            scannerStateLiveData.clearRecords();
    }

    /**
     * Start scanning for Bluetooth LE devices.
     */
    public void startScan() {
        if (scannerStateLiveData.isScanning()) {
            return;
        }

        // Scanning settings
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setReportDelay(500)
                .setUseHardwareBatchingIfSupported(false)
                .build();

        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (final IllegalStateException e) {
            // The scanner is not available when Bluetooth has just been turned off, or is being
            // restarted, while the scanner state still reports it as enabled. The state is
            // updated by a broadcast, which will start the scan again when Bluetooth is back on.
            Timber.w(e, "Failed to start scanning");
            return;
        }
        scannerStateLiveData.scanningStarted();
    }

    /**
     * Stop scanning for Bluetooth LE devices.
     */
    public void stopScan() {
        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(scanCallback);
        scannerStateLiveData.scanningStopped();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, @NonNull final ScanResult result) {
            // This callback will be called only if the scan report delay is not set or is set to 0.

            // If the packet has been obtained while Location was disabled, mark Location as not required
            if (Utils.isLocationRequired(getApplication()) && !Utils.isLocationEnabled(getApplication()))
                Utils.markLocationNotRequired(getApplication());

            if (!isNoise(result) && devicesLiveData.deviceDiscovered(result)) {
                devicesLiveData.applyFilter();
                scannerStateLiveData.recordFound();
            }
        }

        @Override
        public void onBatchScanResults(@NonNull final List<ScanResult> results) {
            // This callback will be called only if the report delay set above is greater then 0.
            if (results.isEmpty())
                return;

            // If the packet has been obtained while Location was disabled, mark Location as not required
            if (Utils.isLocationRequired(getApplication()) && !Utils.isLocationEnabled(getApplication()))
                Utils.markLocationNotRequired(getApplication());

            boolean atLeastOneMatchedFilter = false;
            for (final ScanResult result : results)
                atLeastOneMatchedFilter =
                        (!isNoise(result) && devicesLiveData.deviceDiscovered(result))
                                || atLeastOneMatchedFilter;
            if (atLeastOneMatchedFilter) {
                devicesLiveData.applyFilter();
                scannerStateLiveData.recordFound();
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Timber.w("Scanning failed with code %d", errorCode);

            if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                stopScan();
                startScan();
            }
        }
    };

    /**
     * Register for required broadcast receivers.
     */
    private void registerBroadcastReceivers(@NonNull final Application application) {
        application.registerReceiver(bluetoothStateBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        if (Utils.isMarshmallowOrAbove()) {
            application.registerReceiver(locationProviderChangedReceiver, new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
        }
    }

    /**
     * Unregister all broadcast receivers.
     */
    private void unregisterBroadcastReceivers(@NonNull final Context context) {
        context.unregisterReceiver(bluetoothStateBroadcastReceiver);

        if (Utils.isMarshmallowOrAbove()) {
            context.unregisterReceiver(locationProviderChangedReceiver);
        }
    }

    /**
     * Broadcast receiver to monitor the changes in the location provider
     */
    private final BroadcastReceiver locationProviderChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final boolean enabled = Utils.isLocationEnabled(context);
            scannerStateLiveData.setLocationEnabled(enabled);
        }
    };

    /**
     * Broadcast receiver to monitor the changes in the bluetooth adapter
     */
    private final BroadcastReceiver bluetoothStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
            final int previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    scannerStateLiveData.bluetoothEnabled();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                case BluetoothAdapter.STATE_OFF:
                    if (previousState != BluetoothAdapter.STATE_TURNING_OFF && previousState != BluetoothAdapter.STATE_OFF) {
                        stopScan();
                        devicesLiveData.bluetoothDisabled();
                        scannerStateLiveData.bluetoothDisabled();
                    }
                    break;
            }
        }
    };

    /**
     * This method returns true if the scan result may be considered as noise.
     * This is to make the device list on the scanner screen shorter.
     * <p>
     * This implementation considers as noise devices that:
     * <ul>
     * <li>Are not connectable (Android Oreo or newer only),</li>
     * <li>Are far away (RSSI < -80),</li>
     * <li>Advertise as beacons (iBeacons, Nordic Beacons, Microsoft Advertising Beacons,
     * Eddystone),</li>
     * <li>Advertise with AirDrop footprint,</li>
     * </ul>
     * Noise devices will no the shown on the scanner screen even with all filters disabled.
     *
     * @param result the scan result.
     * @return true, if the device may be dismissed, false otherwise.
     */
    @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "RedundantIfStatement"})
    private boolean isNoise(@NonNull final ScanResult result) {
        // Do not show non-connectable devices.
        // Only Android Oreo or newer can say if a device is connectable. On older Android versions
        // the Support Scanner Library assumes all devices are connectable (compatibility mode).
        if (!result.isConnectable())
            return true;

        // Very distant devices are noise.
        if (result.getRssi() < -80)
            return true;

        if (FilterUtils.isBeacon(result))
            return true;

        if (FilterUtils.isAirDrop(result))
            return true;

        return false;
    }
}
