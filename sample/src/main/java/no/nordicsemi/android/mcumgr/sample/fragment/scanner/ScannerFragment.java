/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.fragment.scanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import javax.inject.Inject;

import no.nordicsemi.android.mcumgr.sample.MainActivity;
import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.android.mcumgr.sample.adapter.DevicesAdapter;
import no.nordicsemi.android.mcumgr.sample.databinding.FragmentScannerBinding;
import no.nordicsemi.android.mcumgr.sample.di.Injectable;
import no.nordicsemi.android.mcumgr.sample.utils.Utils;
import no.nordicsemi.android.mcumgr.sample.viewmodel.ViewModelFactory;
import no.nordicsemi.android.mcumgr.sample.viewmodel.scanner.ScannerStateLiveData;
import no.nordicsemi.android.mcumgr.sample.viewmodel.scanner.ScannerViewModel;

public class ScannerFragment extends Fragment implements Injectable, DevicesAdapter.OnItemClickListener {

    @Inject
    ViewModelFactory viewModelFactory;

    private ScannerViewModel scannerViewModel;
    private FragmentScannerBinding binding;
    /** Set between onStart and onStop. The fragment's lifecycle state is not updated yet in onStart. */
    private boolean started;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentScannerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final Context context = view.getContext();

        // Create view model containing utility methods for scanning
        scannerViewModel = new ViewModelProvider(getViewModelStore(), viewModelFactory)
                .get(ScannerViewModel.class);
        scannerViewModel.getScannerState().observe(getViewLifecycleOwner(), this::startScan);

        // Configure the recycler view
        final RecyclerView recyclerView = binding.recyclerViewBleDevices;
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        final DevicesAdapter adapter =
                new DevicesAdapter(getViewLifecycleOwner(), scannerViewModel.getDevices());
        adapter.setOnItemClickListener(this);
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            adapter.setHorizontalPadding(Math.max(bars.left, bars.right));
            return WindowInsetsCompat.CONSUMED;
        });
        recyclerView.setAdapter(adapter);

        // Set up permission request launcher
        final ActivityResultLauncher<String> requestPermission =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        result -> scannerViewModel.refresh()
                );
        final ActivityResultLauncher<String[]> requestPermissions =
                registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        result -> scannerViewModel.refresh()
                );

        // Configure views
        binding.refreshLayout.setOnRefreshListener(() -> {
            // Restart the scan, as Android may have downgraded a long-running scan to an
            // opportunistic one, which gives no results on its own.
            stopScan();
            scannerViewModel.clear();
            startScan();
            binding.refreshLayout.setRefreshing(false);
        });
        binding.noDevices.actionEnableLocation.setOnClickListener(v -> openLocationSettings());
        binding.bluetoothOff.actionEnableBluetooth.setOnClickListener(v -> requestBluetoothEnabled());
        binding.noLocationPermission.actionGrantLocationPermission.setOnClickListener(v -> {
            if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION))
                Utils.markLocationPermissionRequested(context);
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        });
        binding.noLocationPermission.actionPermissionSettings.setOnClickListener(v -> {
            Utils.clearLocationPermissionRequested(context);
            openPermissionSettings();
        });
        if (Utils.isSorAbove()) {
            binding.noBluetoothPermission.actionGrantBluetoothPermission.setOnClickListener(v -> {
                if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(),
                        Manifest.permission.BLUETOOTH_SCAN)) {
                    Utils.markBluetoothScanPermissionRequested(context);
                }
                requestPermissions.launch(new String[] {
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                });
            });
            binding.noBluetoothPermission.actionPermissionSettings.setOnClickListener(v -> {
                Utils.clearBluetoothPermissionRequested(context);
                openPermissionSettings();
            });
        }

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull final Menu menu, @NonNull final MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.filter, menu);
                menu.findItem(R.id.filter_names).setChecked(scannerViewModel.isNameFilterEnabled());
                menu.findItem(R.id.filter_nearby).setChecked(scannerViewModel.isNearbyFilterEnabled());
            }

            @Override
            public void onPrepareMenu(@NonNull final Menu menu) {
                menu.findItem(R.id.menu_filter).setVisible(isVisible());
            }

            @Override
            public boolean onMenuItemSelected(@NonNull final MenuItem item) {
                final int itemId = item.getItemId();
                if (itemId == R.id.filter_names) {
                    item.setChecked(!item.isChecked());
                    scannerViewModel.filterByName(item.isChecked());
                    return true;
                }
                if (itemId == R.id.filter_nearby) {
                    item.setChecked(!item.isChecked());
                    scannerViewModel.filterByDistance(item.isChecked());
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    // The scan is started in onStart and stopped in onStop, not in onResume and onPause.
    // Stopping the scan posts a new scanner state, which, when delivered while the view is still
    // started (that is between onPause and onStop), would be handled by the observer, restarting
    // the scan right after it was stopped. Also, when another activity is started, onStop is
    // called only after it is shown, so the scan would be left on for the whole time.
    @Override
    public void onStart() {
        super.onStart();
        started = true;
        startScan();
    }

    @Override
    public void onStop() {
        super.onStop();
        started = false;
        stopScan();
    }

    /**
     * The scanner fragment is hidden when the Saved tab is selected, which does not change its
     * lifecycle. The scan is not needed then.
     */
    @Override
    public void onHiddenChanged(final boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopScan();
        } else {
            startScan();
        }
    }

    @Override
    public void onItemClick(@NonNull final BluetoothDevice device) {
        final Intent intent = new Intent(requireContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_DEVICE, device);
        startActivity(intent);
    }

    /**
     * Start scanning for Bluetooth devices or displays a message based on the scanner state.
     */
    private void startScan(final ScannerStateLiveData state) {
        // Do not scan when the screen is not visible. This may be called by the observer with
        // a state posted before the scan was stopped.
        if (!started || isHidden()) {
            return;
        }
        final Context context = requireContext();
        // First, check the Location permission.
        // This is required since Marshmallow up until Android 11 in order to scan for Bluetooth LE
        // devices.
        if (!Utils.isLocationPermissionRequired() ||
                Utils.isLocationPermissionGranted(context)) {
            binding.noLocationPermission.getRoot().setVisibility(View.GONE);

            // On Android 12+ a new BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions need to be
            // requested.
            //
            // Note: This has to be done before asking user to enable Bluetooth, as
            //       sending BluetoothAdapter.ACTION_REQUEST_ENABLE intent requires
            //       BLUETOOTH_CONNECT permission.
            if (!Utils.isSorAbove() || Utils.isBluetoothScanPermissionGranted(context)) {
                binding.noBluetoothPermission.getRoot().setVisibility(View.GONE);

                // Bluetooth must be enabled
                if (state.isBluetoothEnabled()) {
                    binding.bluetoothOff.getRoot().setVisibility(View.GONE);

                    // We are now OK to start scanning
                    scannerViewModel.startScan();

                    if (!state.hasRecords()) {
                        binding.noDevices.getRoot().setVisibility(View.VISIBLE);

                        if (!Utils.isLocationRequired(context) ||
                                Utils.isLocationEnabled(context)) {
                            binding.noDevices.noLocation.setVisibility(View.INVISIBLE);
                        } else {
                            binding.noDevices.noLocation.setVisibility(View.VISIBLE);
                        }
                    } else {
                        binding.noDevices.getRoot().setVisibility(View.GONE);
                    }
                } else {
                    binding.bluetoothOff.getRoot().setVisibility(View.VISIBLE);
                    binding.noDevices.getRoot().setVisibility(View.GONE);
                    binding.noBluetoothPermission.getRoot().setVisibility(View.GONE);

                    scannerViewModel.clear();
                }
            } else {
                binding.noBluetoothPermission.getRoot().setVisibility(View.VISIBLE);
                binding.bluetoothOff.getRoot().setVisibility(View.GONE);
                binding.noDevices.getRoot().setVisibility(View.GONE);

                final boolean deniedForever = Utils.isBluetoothScanPermissionDeniedForever(requireActivity());
                binding.noBluetoothPermission.actionGrantBluetoothPermission.setVisibility(deniedForever ? View.GONE : View.VISIBLE);
                binding.noBluetoothPermission.actionPermissionSettings.setVisibility(deniedForever ? View.VISIBLE : View.GONE);
            }
        } else {
            binding.noLocationPermission.getRoot().setVisibility(View.VISIBLE);
            binding.noBluetoothPermission.getRoot().setVisibility(View.GONE);
            binding.bluetoothOff.getRoot().setVisibility(View.GONE);
            binding.noDevices.getRoot().setVisibility(View.GONE);

            final boolean deniedForever = Utils.isLocationPermissionDeniedForever(requireActivity());
            binding.noLocationPermission.actionGrantLocationPermission.setVisibility(deniedForever ? View.GONE : View.VISIBLE);
            binding.noLocationPermission.actionPermissionSettings.setVisibility(deniedForever ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Starts scanning for Bluetooth LE devices.
     */
    private void startScan() {
        if (scannerViewModel != null && binding != null) {
            startScan(scannerViewModel.getScannerState());
        }
    }

    /**
     * Stops scanning for Bluetooth LE devices.
     */
    private void stopScan() {
        if (scannerViewModel != null) {
            scannerViewModel.stopScan();
        }
    }

    /**
     * Opens application settings in Android Settings app.
     */
    private void openPermissionSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * Opens Location settings.
     */
    private void openLocationSettings() {
        final Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * Shows a prompt to the user to enable Bluetooth on the device.
     *
     * @implSpec On Android 12+ BLUETOOTH_CONNECT permission needs to be granted before calling
     *           this method. Otherwise, the app would crash with {@link SecurityException}.
     */
    @SuppressLint("MissingPermission")
    private void requestBluetoothEnabled() {
        if (Utils.isBluetoothConnectPermissionGranted(requireContext())) {
            final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableIntent);
        }
    }
}
