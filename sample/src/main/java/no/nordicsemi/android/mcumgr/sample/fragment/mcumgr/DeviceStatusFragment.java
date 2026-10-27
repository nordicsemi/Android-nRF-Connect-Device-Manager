/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.fragment.mcumgr;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.android.mcumgr.sample.databinding.FragmentCardDeviceStatusBinding;
import no.nordicsemi.android.mcumgr.sample.di.Injectable;
import no.nordicsemi.android.mcumgr.sample.dialog.HelpDialogFragment;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.DeviceStatusViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.FeatureState;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.McuMgrViewModelFactory;
import no.nordicsemi.android.observability.data.ChunksEmitter;
import no.nordicsemi.android.ota.DeviceInfo;
import no.nordicsemi.kotlin.ble.client.android.BondState;
import no.nordicsemi.kotlin.ble.client.android.EncryptionState;

public class DeviceStatusFragment extends Fragment implements Injectable {

    @Inject
    McuMgrViewModelFactory viewModelFactory;

    private FragmentCardDeviceStatusBinding binding;

    private DeviceStatusViewModel viewModel;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this, viewModelFactory)
                .get(DeviceStatusViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentCardDeviceStatusBinding.inflate(inflater, container, false);
        binding.toolbar.inflateMenu(R.menu.help);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_help) {
                final DialogFragment dialog = HelpDialogFragment.getInstance(
                        R.string.status_dialog_help_title,
                        R.string.status_dialog_help_message,
                        "https://mflt.io/nrf-app-discover-cloud-services");
                dialog.show(getChildFragmentManager(), null);
                return true;
            }
            return false;
        });
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case CONNECTING:
                    binding.mcumgrSupported.setText(R.string.status_connecting);
                    break;
                case INITIALIZING:
                    binding.mcumgrSupported.setText(R.string.status_initializing);
                    break;
                case READY:
                    binding.mcumgrSupported.setText(R.string.status_connected);
                    break;
                case DISCONNECTING:
                    binding.mcumgrSupported.setText(R.string.status_disconnecting);
                    break;
                case DISCONNECTED:
                    binding.mcumgrSupported.setText(R.string.status_disconnected);
                    break;
                case TIMEOUT:
                    binding.mcumgrSupported.setText(R.string.status_error_connection_timeout);
                    break;
                case NOT_SUPPORTED:
                    binding.mcumgrSupported.setText(R.string.status_not_supported);
                    binding.mcumgrBufferSize.setText(R.string.not_applicable);
                    binding.bootloaderMode.setText(R.string.not_applicable);
                    binding.bootloaderName.setText(R.string.not_applicable);
                    binding.activeB0Slot.setText(R.string.not_applicable);
                    binding.kernel.setText(R.string.not_applicable);
                    binding.otaSupported.setText(R.string.status_not_supported);
                    break;
            }
        });
        viewModel.getBondState().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case BondState.None none: {
                    final BondState.None.Reason reason = none.getReason();
                    if (reason != null) {
                        binding.bondingStatus.setText(bondFailureToString(reason));
                    } else {
                        binding.bondingStatus.setText(R.string.status_not_bonded);
                    }
                    break;
                }
                case BondState.Bonded ignored: {
                    binding.bondingStatus.setText(R.string.status_bonded);
                    break;
                }
                default: {
                    binding.bondingStatus.setText(R.string.status_bonding);
                    break;
                }
            }
        });
        // The encryption state is reported from Android 16 (API 36). Earlier versions do not tell
        // whether the link is encrypted, so there is nothing to show.
        if (Build.VERSION.SDK_INT < 36) {
            binding.encryptionStatusLabel.setVisibility(View.GONE);
            binding.encryptionStatus.setVisibility(View.GONE);
        } else {
            viewModel.getEncryptionState().observe(getViewLifecycleOwner(), state ->
                    binding.encryptionStatus.setText(encryptionToString(state)));
        }
        viewModel.getBufferParams().observe(getViewLifecycleOwner(), params -> {
            if (params != null) {
                final String text = getString(R.string.status_mcumgr_buffer_size, params.getCount(), params.getSize());
                binding.mcumgrBufferSize.setText(text);
            } else {
                binding.mcumgrBufferSize.setText(R.string.status_unknown);
            }
        });
        viewModel.getBootloaderName().observe(getViewLifecycleOwner(), name -> {
            if (name != null) {
                binding.bootloaderName.setText(name);
            } else {
                binding.bootloaderName.setText(R.string.status_unknown);
            }
        });
        viewModel.getBootloaderMode().observe(getViewLifecycleOwner(), mode -> {
            if (mode != null) {
                final CharSequence[] modes = getResources().getTextArray(R.array.bootloader_modes);
                if (mode >= 0 && mode < modes.length) {
                    binding.bootloaderMode.setText(modes[mode]);
                } else {
                    binding.bootloaderMode.setText(getString(R.string.status_unknown_value, mode));
                }
            } else {
                binding.bootloaderMode.setText(R.string.status_unknown);
            }
        });
        viewModel.getActiveB0Slot().observe(getViewLifecycleOwner(), slot -> {
            if (slot != null) {
                final CharSequence[] slots = getResources().getTextArray(R.array.b0_slots);
                if (slot >= 0 && slot < slots.length) {
                    binding.activeB0Slot.setText(slots[slot]);
                } else {
                    binding.activeB0Slot.setText(getString(R.string.status_unknown_value, slot));
                }
            } else {
                binding.activeB0Slot.setText(R.string.status_unknown);
            }
        });
        viewModel.getAppInfo().observe(getViewLifecycleOwner(), kernel -> {
            if (kernel != null) {
                binding.kernel.setText(kernel);
            } else {
                binding.kernel.setText(R.string.status_unknown);
            }
        });
        viewModel.getOtaInfo().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case FeatureState.NotSupported ignored ->
                        binding.otaSupported.setText(R.string.status_not_supported);
                case FeatureState.Supported<DeviceInfo> info -> {
                        binding.otaSupported.setText(R.string.status_supported);
                        final DeviceInfo deviceInfo = info.getResult();
                        binding.otaVersion.setText(getString(R.string.status_ota_version_value, deviceInfo.getCurrentVersion(), deviceInfo.getSoftwareType()));
                        binding.otaHwVersion.setText(deviceInfo.getHardwareVersion());
                }
                case null, default -> binding.otaSupported.setText(R.string.status_unknown);
            }
        });
        viewModel.getObservabilityState().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case ChunksEmitter.State.Initializing ignored ->
                        binding.observabilitySupported.setText(R.string.status_connecting);
                case ChunksEmitter.State.Ready ignored ->
                        binding.observabilitySupported.setText(R.string.status_mds_supported);
                case ChunksEmitter.State.Disconnected ignored ->
                        binding.observabilitySupported.setText(R.string.status_disconnected);
                case null,
                default -> binding.observabilitySupported.setText(R.string.status_unknown);
            }
        });
        viewModel.getBusyState().observe(getViewLifecycleOwner(), busy ->
                binding.workIndicator.setVisibility(busy ? View.VISIBLE : View.GONE));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Returns the text describing the encryption state.
     *
     * @param state the state, or null if the device is not connected.
     */
    @NonNull
    private String bondFailureToString(@NonNull final BondState.Reason reason) {
        return switch (reason) {
            case BondState.Reason.AuthenticationFailed ignored ->
                    getString(R.string.status_bond_failure_authentication_failed);
            case BondState.Reason.AuthenticationRejected ignored ->
                    getString(R.string.status_bond_failure_authentication_rejected);
            case BondState.Reason.AuthenticationCanceled ignored ->
                    getString(R.string.status_bond_failure_authentication_canceled);
            case BondState.Reason.AuthenticationTimeout ignored ->
                    getString(R.string.status_bond_failure_authentication_timeout);
            case BondState.Reason.RemoteAuthenticationCanceled ignored ->
                    getString(R.string.status_bond_failure_remote_authentication_canceled);
            case BondState.Reason.RemoteDeviceDown ignored ->
                    getString(R.string.status_bond_failure_remote_device_down);
            case BondState.Reason.DiscoveryInProgress ignored ->
                    getString(R.string.status_bond_failure_discovery_in_progress);
            case BondState.Reason.RepeatedAttempts ignored ->
                    getString(R.string.status_bond_failure_repeated_attempts);
            case BondState.Reason.Removed ignored ->
                    getString(R.string.status_bond_failure_removed);
            case BondState.Reason.Unknown unknown ->
                    getString(R.string.status_bond_failure_unknown, unknown.getCode());
            default -> getString(R.string.status_unknown);
        };
    }

    private String encryptionToString(@Nullable final EncryptionState state) {
        switch (state) {
            case null -> {
                return getString(R.string.not_applicable);
            }
            case EncryptionState.Encrypted encrypted -> {
                final List<String> details = new ArrayList<>();
                if (encrypted.getAlgorithm() != EncryptionState.Algorithm.Unknown) {
                    details.add(encrypted.getAlgorithm().name());
                }
                final Integer keySize = encrypted.getKeySize();
                if (keySize != null) {
                    details.add(getString(R.string.status_encryption_key_size, keySize * 8));
                }
                return details.isEmpty() ?
                        getString(R.string.status_encrypted) :
                        getString(R.string.status_encrypted_details, String.join(", ", details));
            }
            case EncryptionState.NotEncrypted notEncrypted -> {
                final EncryptionState.Reason reason = notEncrypted.getReason();
                if (reason == null) {
                    return getString(R.string.status_not_encrypted);
                }
                final String text;
                if (reason instanceof EncryptionState.Reason.ControllerError error) {
                    text = getString(R.string.status_encryption_failure_controller, error.getStatus());
                } else if (reason instanceof EncryptionState.Reason.LeEncryptionFailure
                        || reason instanceof EncryptionState.Reason.BrEdrAuthenticationFailure) {
                    text = getString(R.string.status_encryption_failure_le);
                } else if (reason instanceof EncryptionState.Reason.LeIncomingPairing
                        || reason instanceof EncryptionState.Reason.BrEdrIncomingPairing) {
                    text = getString(R.string.status_encryption_failure_pairing);
                } else if (reason instanceof EncryptionState.Reason.Unknown unknown) {
                    text = getString(R.string.status_encryption_failure_unknown, unknown.getCode());
                } else {
                    text = reason.toString();
                }
                return getString(R.string.status_not_encrypted_reason, text);
            }
            default -> {
            }
        }
        // Unknown.
        return getString(R.string.status_unknown);
    }
}
