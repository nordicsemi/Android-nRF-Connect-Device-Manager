/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.fragment.mcumgr;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.TargetImage;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.image.McuMgrImage;
import no.nordicsemi.android.mcumgr.image.SUITImage;
import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.android.mcumgr.sample.databinding.FragmentCardImageUpgradeBinding;
import no.nordicsemi.android.mcumgr.sample.di.Injectable;
import no.nordicsemi.android.mcumgr.sample.dialog.FirmwareUpgradeModeDialogFragment;
import no.nordicsemi.android.mcumgr.sample.dialog.HelpDialogFragment;
import no.nordicsemi.android.mcumgr.sample.dialog.ReleaseInformationDialogFragment;
import no.nordicsemi.android.mcumgr.sample.dialog.SelectBinaryDialogFragment;
import no.nordicsemi.android.mcumgr.sample.dialog.WarningDialogFragment;
import no.nordicsemi.android.mcumgr.sample.graph.ThroughputParameters;
import no.nordicsemi.android.mcumgr.sample.utils.StringUtils;
import no.nordicsemi.android.mcumgr.sample.utils.ZipPackage;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ImageUpgradeViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.McuMgrViewModelFactory;
import no.nordicsemi.android.ota.DownloadCallback;
import no.nordicsemi.android.ota.OtaManager;
import no.nordicsemi.android.ota.ReleaseBinary;

public class ImageUpgradeFragment extends FileBrowserFragment implements Injectable,
        SelectBinaryDialogFragment.OnBinarySelectedListener,
        ReleaseInformationDialogFragment.OnDownloadClickedListener {
    private static final String PREF_ERASE_APP_SETTINGS = "pref_erase_app_settings";
    private static final String PREF_ESTIMATED_SWAP_TIME = "pref_estimated_swap_time";
    private static final String PREF_WINDOW_CAPACITY = "pref_window_capacity";
    private static final String PREF_MEMORY_ALIGNMENT = "pref_memory_alignment";
    private static final String SIS_MEMORY_ALIGNMENT = "sis_memory_alignment";

    @Inject
    McuMgrViewModelFactory viewModelFactory;

    private FragmentCardImageUpgradeBinding binding;

    private ImageUpgradeViewModel viewModel;
    private int memoryAlignment;
    private boolean requiresModeSelection;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this, viewModelFactory)
                .get(ImageUpgradeViewModel.class);

        if (savedInstanceState != null) {
            memoryAlignment = savedInstanceState.getInt(SIS_MEMORY_ALIGNMENT);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull @NotNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SIS_MEMORY_ALIGNMENT, memoryAlignment);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentCardImageUpgradeBinding.inflate(inflater, container, false);

        // Set up (i) buttons.
        binding.advancedEraseSettingsInfo.setOnClickListener(v -> {
            final DialogFragment dialog = HelpDialogFragment.getInstance(
                    R.string.image_upgrade_erase_storage,
                    R.string.image_upgrade_erase_storage_info);
            dialog.show(getChildFragmentManager(), null);
        });
        binding.advancedSwapTimeInfo.setOnClickListener(v -> {
            final DialogFragment dialog = HelpDialogFragment.getInstance(
                    R.string.image_upgrade_swap_time,
                    R.string.image_upgrade_swap_time_info);
            dialog.show(getChildFragmentManager(), null);
        });
        binding.advancedPipelineInfo.setOnClickListener(v -> {
            final DialogFragment dialog = HelpDialogFragment.getInstance(
                    R.string.image_upgrade_pipeline,
                    R.string.image_upgrade_pipeline_info);
            dialog.show(getChildFragmentManager(), null);
        });
        binding.advancedMemoryAlignmentInfo.setOnClickListener(v -> {
            final DialogFragment dialog = HelpDialogFragment.getInstance(
                    R.string.image_upgrade_memory_alignment,
                    R.string.image_upgrade_memory_alignment_info);
            dialog.show(getChildFragmentManager(), null);
        });

        final CharSequence[] items = getResources().getTextArray(R.array.image_upgrade_memory_alignment_options);
        binding.advancedMemoryAlignment.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.drop_down_item, items));
        binding.advancedMemoryAlignment.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 1 -> memoryAlignment = 2;
                case 2 -> memoryAlignment = 4;
                case 3 -> memoryAlignment = 8;
                case 4 -> memoryAlignment = 16;
                default -> memoryAlignment = 1;
            }
        });

        // Fill default values.
        if (savedInstanceState == null) {
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
            binding.advancedEraseSettings.setChecked(preferences.getBoolean(PREF_ERASE_APP_SETTINGS, false));
            binding.advancedSwapTime.setText(getString(R.string.value_int, preferences.getInt(PREF_ESTIMATED_SWAP_TIME, 10)));
            binding.advancedWindowCapacity.setText(getString(R.string.value_int, preferences.getInt(PREF_WINDOW_CAPACITY, 4)));
            memoryAlignment = preferences.getInt(PREF_MEMORY_ALIGNMENT, 4);
            int position = switch (memoryAlignment) {
                case 2 -> 1;
                case 4 -> 2;
                case 8 -> 3;
                case 16 -> 4;
                default -> 0;
            };
            binding.advancedMemoryAlignment.setText(items[position], false);
        }

        // Set up Expand / Collapse buttons in the section's menu.
        binding.toolbar.inflateMenu(R.menu.expandable);
        binding.toolbar.getMenu().findItem(R.id.action_expand).setOnMenuItemClickListener(item -> {
            viewModel.setAdvancedSettingsExpanded(true);
            return true;
        });
        binding.toolbar.getMenu().findItem(R.id.action_collapse).setOnMenuItemClickListener(item -> {
            viewModel.setAdvancedSettingsExpanded(false);
            return true;
        });
        viewModel.getAdvancedSettingsState().observe(getViewLifecycleOwner(), expanded -> {
            final Menu menu = binding.toolbar.getMenu();
            menu.findItem(R.id.action_collapse).setVisible(expanded);
            menu.findItem(R.id.action_expand).setVisible(!expanded);
            binding.advancedGroup.setVisibility(expanded ? View.VISIBLE : View.GONE);
        });
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel.getState().observe(getViewLifecycleOwner(), state -> {
            binding.actionStart.setEnabled(isFileLoaded());
            binding.actionCancel.setEnabled(state.canCancel());
            binding.actionPauseResume.setEnabled(state.canPauseOrResume());
            binding.actionPauseResume.setText(state == ImageUpgradeViewModel.State.PAUSED ?
                    R.string.image_action_resume : R.string.image_action_pause);

            binding.actionCheckForUpdate.setVisibility(state.inProgress() ? View.GONE : View.VISIBLE);
            binding.actionSelectFile.setVisibility(state.inProgress() ? View.GONE : View.VISIBLE);
            binding.actionStart.setVisibility(state.inProgress() ? View.GONE : View.VISIBLE);
            binding.actionCancel.setVisibility(state.inProgress() ? View.VISIBLE : View.GONE);
            binding.actionPauseResume.setVisibility(state.inProgress() ? View.VISIBLE : View.GONE);
            // Update status
            switch (state) {
                case VALIDATING -> {
                    binding.status.setText(R.string.image_upgrade_status_validating);
                    binding.advancedEraseSettings.setEnabled(false);
                    binding.advancedSwapTimeLayout.setEnabled(false);
                    binding.advancedPipelineLayout.setEnabled(false);
                    binding.advancedMemoryAlignmentLayout.setEnabled(false);
                }
                case UPLOADING -> binding.status.setText(R.string.image_upgrade_status_uploading);
                case PROCESSING -> binding.status.setText(R.string.image_upgrade_status_processing);
                case PAUSED -> binding.status.setText(R.string.image_upgrade_status_paused);
                case TESTING -> binding.status.setText(R.string.image_upgrade_status_testing);
                case CONFIRMING -> binding.status.setText(R.string.image_upgrade_status_confirming);
                case RESETTING -> binding.status.setText(R.string.image_upgrade_status_resetting);
                case COMPLETE -> {
                    binding.status.setText(R.string.image_upgrade_status_completed);
                    binding.advancedEraseSettings.setEnabled(true);
                    binding.advancedSwapTimeLayout.setEnabled(true);
                    binding.advancedPipelineLayout.setEnabled(true);
                    binding.advancedMemoryAlignmentLayout.setEnabled(true);
                }
            }
        });
        viewModel.getProgress().observe(getViewLifecycleOwner(), throughputData -> {
            if (throughputData == null) {
                binding.graph.setVisibility(View.GONE);
                binding.graph.clear();
            } else {
                binding.graph.setVisibility(View.VISIBLE);
                binding.graph.addProgress(
                        throughputData.progress,
                        throughputData.averageThroughput,
                        throughputData.newSeries
                );
            }
        });
        final LiveData<ThroughputParameters> parametersLiveData = viewModel.getConnectionParameters();
        if (parametersLiveData != null) {
            parametersLiveData.observe(getViewLifecycleOwner(), parameters -> {
                if (parameters != null) {
                    binding.graph.setConnectionParameters(
                            parameters.getIntervalInMs(),
                            parameters.getMtu(),
                            parameters.getBufferSize(),
                            parameters.getTxPhy(),
                            parameters.getRxPhy()
                    );
                }
            });
        }
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null) {
                binding.actionCheckForUpdate.setVisibility(View.VISIBLE);
                binding.actionSelectFile.setVisibility(View.VISIBLE);
                binding.actionStart.setVisibility(View.VISIBLE);
                binding.actionCancel.setVisibility(View.GONE);
                binding.actionPauseResume.setVisibility(View.GONE);
                binding.advancedEraseSettings.setEnabled(true);
                binding.advancedSwapTimeLayout.setEnabled(true);
                binding.advancedPipelineLayout.setEnabled(true);
                binding.advancedMemoryAlignmentLayout.setEnabled(true);
                printError(error);
            }
        });
        viewModel.getCancelledEvent().observe(getViewLifecycleOwner(), nothing -> {
            binding.status.setText(R.string.image_upgrade_status_cancelled);
            binding.actionCheckForUpdate.setVisibility(View.VISIBLE);
            binding.actionSelectFile.setVisibility(View.VISIBLE);
            binding.actionStart.setVisibility(View.VISIBLE);
            binding.actionStart.setEnabled(false);
            binding.actionCancel.setVisibility(View.GONE);
            binding.actionPauseResume.setVisibility(View.GONE);
            binding.advancedEraseSettings.setEnabled(true);
            binding.advancedSwapTimeLayout.setEnabled(true);
            binding.advancedPipelineLayout.setEnabled(true);
            binding.advancedMemoryAlignmentLayout.setEnabled(true);
        });
        viewModel.getBusyState().observe(getViewLifecycleOwner(), busy -> {
            binding.actionCheckForUpdate.setEnabled(!busy);
            binding.actionSelectFile.setEnabled(!busy);
            binding.actionStart.setEnabled(isFileLoaded() && !busy);
        });
        viewModel.getOtaReadyEvent().observe(getViewLifecycleOwner(), releaseInformation -> {
            final DialogFragment dialog = ReleaseInformationDialogFragment.getInstance(releaseInformation);
            dialog.show(getChildFragmentManager(), null);
        });
        viewModel.getNetworkErrorEvent().observe(getViewLifecycleOwner(), throwable -> {
            final String errorMessage = createDetailedErrorMessage(throwable);
            final int titleRes = isHttpError(throwable) ? R.string.ota_http_error_title : R.string.ota_network_unavailable_title;
            final DialogFragment dialog = WarningDialogFragment.getInstance(titleRes, errorMessage);
            dialog.show(getChildFragmentManager(), null);
        });
        viewModel.getOtaNotSupportedEvent().observe(getViewLifecycleOwner(), nothing -> {
            final DialogFragment dialog = HelpDialogFragment.getInstance(
                    R.string.ota_dialog_not_supported_title,
                    R.string.ota_dialog_not_supported_message,
                    "https://mflt.io/nrf-app-discover-cloud-services");
            dialog.show(getChildFragmentManager(), null);
        });

        // Configure Check for Updates button
        binding.actionCheckForUpdate.setOnClickListener(v -> viewModel.checkForUpdate());

        // Configure SELECT FILE action
        binding.actionSelectFile.setOnClickListener(v -> {
            requiresModeSelection = true;
            selectFile("*/*");
        });

        // Restore START action state after rotation
        binding.actionStart.setEnabled(isFileLoaded());
        binding.actionStart.setOnClickListener(v -> {
            if (requiresModeSelection) {
                // Show a mode picker. When mode is selected, the upgrade(Mode) method will be called.
                final DialogFragment dialog = FirmwareUpgradeModeDialogFragment.getInstance();
                dialog.show(getChildFragmentManager(), null);
            } else {
                // The mode doesn't matter for SUIT files as it's ignored.
                // For MCUboot update this has to be Confirm Only.
                start(FirmwareUpgradeManager.Mode.CONFIRM_ONLY);
            }
        });

        // Cancel and Pause/Resume buttons
        binding.actionCancel.setOnClickListener(v -> viewModel.cancel());
        binding.actionPauseResume.setOnClickListener(v -> {
            if (viewModel.getState().getValue() == ImageUpgradeViewModel.State.UPLOADING) {
                viewModel.pause();
            } else {
                viewModel.resume();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Starts the Firmware Upgrade using a selected mode.
     */
    @SuppressWarnings("ConstantConditions")
    public void start(@NonNull final FirmwareUpgradeManager.Mode mode) {
        if (binding.advancedSwapTime.getText().toString().isEmpty()) {
            binding.advancedSwapTime.setText("0");
        }
        if (binding.advancedWindowCapacity.getText().toString().isEmpty()) {
            binding.advancedWindowCapacity.setText("1");
        }
        final boolean eraseAppSettings = binding.advancedEraseSettings.isChecked();
        int swapTimeSeconds;
        try {
            swapTimeSeconds = Integer.parseInt(binding.advancedSwapTime.getText().toString());
            binding.advancedSwapTimeLayout.setError(null);
        } catch (final NumberFormatException e) {
            binding.advancedSwapTimeLayout.setError(getText(R.string.image_upgrade_error));
            return;
        }
        int windowCapacity;
        try {
            windowCapacity = Integer.parseInt(binding.advancedWindowCapacity.getText().toString());
            if (windowCapacity < 1 || windowCapacity > 25)
                throw new NumberFormatException();
            binding.advancedPipelineLayout.setError(null);
        } catch (final NumberFormatException e) {
            binding.advancedPipelineLayout.setError(getText(R.string.image_upgrade_error));
            return;
        }
        final int memoryAlignment = this.memoryAlignment;

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        preferences.edit()
                .putBoolean(PREF_ERASE_APP_SETTINGS, eraseAppSettings)
                .putInt(PREF_ESTIMATED_SWAP_TIME, swapTimeSeconds)
                .putInt(PREF_WINDOW_CAPACITY, windowCapacity)
                .putInt(PREF_MEMORY_ALIGNMENT, memoryAlignment)
                .apply();

        viewModel.upgrade(getFileContent(), mode,
                eraseAppSettings,
                swapTimeSeconds * 1000,
                Math.max(1, windowCapacity - 1), // 1 buffer is used for sending responses.
                memoryAlignment
        );
    }

    @Override
    protected void onFileCleared() {
        binding.actionStart.setEnabled(false);
    }

    @Override
    protected void onFileSelected(@NonNull final String fileName, final int fileSize) {
        binding.fileName.setText(fileName);
    }

    @Override
    protected void onFileLoaded(@NonNull final byte[] data) {
        try {
            // Try parsing BIN file for single/main core (core0) update.
            final byte[] hash = McuMgrImage.getHash(data);
            binding.fileHash.setText(StringUtils.toHex(hash));
            binding.fileSize.setText(getString(R.string.image_upgrade_size_value, data.length));
            binding.actionStart.setEnabled(true);
            binding.status.setText(R.string.image_upgrade_status_ready);
        } catch (final McuMgrException e) {
            // For multi-core devices images are bundled in a ZIP file.
            try {
                final ZipPackage zip = new ZipPackage(data);
                final StringBuilder sizeBuilder = new StringBuilder();
                final StringBuilder hashBuilder = new StringBuilder();
                // Check for SUIT envelope
                final byte[] envelope = zip.getSuitEnvelope();
                if (envelope != null) {
                    // Support for SUIT (Software Update for Internet of Things) format.
                    trySuitEnvelope(envelope);
                    return;
                }

                final ImageSet mcubootBinaries = zip.getMcuBootBinaries();
                if (mcubootBinaries != null && mcubootBinaries.getImages().size() > 1) {
                    final DialogFragment dialog = SelectBinaryDialogFragment.getInstance(123);
                    dialog.show(getChildFragmentManager(), null);
                    return;
                }

                for (final TargetImage binary: zip.getBinaries().getImages()) {
                    final byte[] hash = binary.image.getHash();
                    hashBuilder
                            .append(StringUtils.toHex(hash))
                            .append("\n");
                    sizeBuilder
                            .append(getString(R.string.image_upgrade_size_value, binary.image.getData().length));
                    switch (binary.imageIndex) {
                        case 0 -> sizeBuilder.append(" (app core");
                        case 1 -> sizeBuilder.append(" (net core");
                        default -> sizeBuilder.append(" (unknown core (").append(binary.imageIndex);
                    }
                    sizeBuilder.append(", slot: ").append(binary.slot).append(")\n");
                }
                hashBuilder.setLength(hashBuilder.length() - 1);
                sizeBuilder.setLength(sizeBuilder.length() - 1);
                binding.fileHash.setText(hashBuilder.toString());
                binding.fileSize.setText(sizeBuilder.toString());
                binding.actionStart.setEnabled(true);
                binding.status.setText(R.string.image_upgrade_status_ready);
                requiresModeSelection = true;
            } catch (final Exception e1) {
                // Support for SUIT (Software Update for Internet of Things) format.
                trySuitEnvelope(data);
            }
        }
    }

    @Override
    protected void onFileLoadingFailed(final int error) {
        binding.status.setText(error);
    }

    @Override
    public void onDownload(final @NonNull String location) {
        final OtaManager otaManager = new OtaManager();
        otaManager.download(location, new DownloadCallback() {
            @Override
            public void onSuccess(@NonNull ReleaseBinary binary) {
                final String fileName = binary.getName();
                final byte[] content = binary.getBytes();
                if (fileName != null) {
                    onFileSelected(fileName, content.length);
                }
                setFileContent(content);
            }

            @Override
            public void onNoContent() {
                printError(new McuMgrException("No content"));
            }

            @Override
            public void onError(@NotNull Throwable t) {
                printError(new McuMgrException(t));
            }
        });
    }

    @Override
    public void onBinarySelected(int requestId, int index) {
        final byte[] data = getFileContent();
        try {
            final ZipPackage zip = new ZipPackage(data);
            final TargetImage binary = zip.getBinaries().getImages().get(index);
            requiresModeSelection = false;
            setFileContent(binary.image.getData());
        } catch (final Exception e) {
            onFileLoadingFailed(R.string.image_error_file_not_valid);
        }
    }

    @Override
    public void onSelectingBinaryCancelled() {
        binding.fileName.setText(null);
        binding.fileHash.setText(null);
        binding.fileSize.setText(null);
        binding.status.setText(null);
        clearFileContent();
    }

    private void trySuitEnvelope(@NonNull final byte[] data) {
        try {
            final byte[] hash = SUITImage.getHash(data);
            binding.fileHash.setText(StringUtils.toHex(hash));
            binding.fileSize.setText(getString(R.string.image_upgrade_size_value_suit, data.length));
            binding.actionStart.setEnabled(true);
            binding.status.setText(R.string.image_upgrade_status_ready);
            requiresModeSelection = false;
        } catch (final McuMgrException e) {
            binding.fileHash.setText(null);
            clearFileContent();
            onFileLoadingFailed(R.string.image_error_file_not_valid);
        }
    }

    private void printError(@Nullable final McuMgrException error) {
        final String message = StringUtils.toString(requireContext(), error);
        if (message == null) {
            binding.status.setText(null);
            return;
        }
        final SpannableString spannable = new SpannableString(message);
        spannable.setSpan(new ForegroundColorSpan(
                        ContextCompat.getColor(requireContext(), R.color.colorError)),
                0, message.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new StyleSpan(Typeface.BOLD),
                0, message.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        binding.status.setText(spannable);
    }

    private String createDetailedErrorMessage(@Nullable final Throwable throwable) {
        if (throwable == null) {
            return getString(R.string.ota_network_unavailable_message);
        }

        final StringBuilder message = new StringBuilder();
        final String errorDetails = throwable.getMessage();

        if (isHttpError(throwable)) {
            // For HTTP errors, show a clean message with status code
            message.append("Failed to communicate with the server.");

            if (errorDetails != null && errorDetails.contains("Unexpected response:")) {
                // Extract HTTP status code from "Unexpected response: 400 BAD REQUEST" format
                String[] parts = errorDetails.split("Unexpected response: ");
                if (parts.length > 1) {
                    String responsePart = parts[1].trim();
                    String[] statusParts = responsePart.split(" ");
                    if (statusParts.length > 0) {
                        String statusCode = statusParts[0];
                        String statusText = responsePart.substring(statusCode.length()).trim();

                        message.append("\n\nHTTP Status: ").append(statusCode);

                        // Add the status text if available
                        if (!statusText.isEmpty()) {
                            message.append(" (").append(statusText).append(")");
                        }
                    }
                }
            }
        } else {
            // For non-HTTP errors (network connectivity issues)
            message.append(getString(R.string.ota_network_unavailable_message));

            if (errorDetails != null) {
                message.append("\n\nError: ").append(errorDetails);
            }
        }

        return message.toString();
    }

    private boolean isHttpError(@Nullable final Throwable throwable) {
        if (throwable == null) return false;

        final String message = throwable.getMessage();
        return message != null && message.contains("Unexpected response:");
    }
}
