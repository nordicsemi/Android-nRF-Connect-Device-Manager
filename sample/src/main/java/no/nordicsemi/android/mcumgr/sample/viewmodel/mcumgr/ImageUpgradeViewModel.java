/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

import javax.inject.Inject;
import javax.inject.Named;

import no.nordicsemi.android.mcumgr.McuMgrErrorCode;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeCallback;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeController;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeSettings;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet;
import no.nordicsemi.android.mcumgr.dfu.suit.SUITUpgradeManager;
import no.nordicsemi.android.mcumgr.dfu.suit.model.CacheImageSet;
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.image.SUITImage;
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport;
import no.nordicsemi.android.mcumgr.sample.log.LogController;
import no.nordicsemi.android.mcumgr.sample.graph.ThroughputParameters;
import no.nordicsemi.android.mcumgr.sample.profile.LegacyDeviceInfoProfile;
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority;
import kotlinx.coroutines.CoroutineScope;
import no.nordicsemi.android.mcumgr.sample.utils.ZipPackage;
import no.nordicsemi.android.mcumgr.sample.viewmodel.SingleLiveEvent;
import no.nordicsemi.android.ota.DeviceInfo;
import no.nordicsemi.android.ota.OtaManager;
import no.nordicsemi.android.ota.ReleaseCallback;
import no.nordicsemi.android.ota.ReleaseInformation;
import timber.log.Timber;

public class ImageUpgradeViewModel extends McuMgrViewModel {
    public enum State {
        IDLE,
        VALIDATING,
        UPLOADING,
        PAUSED,
        TESTING,
        CONFIRMING,
        PROCESSING,
        RESETTING,
        COMPLETE;

        public boolean inProgress() {
            return this != IDLE && this != COMPLETE;
        }

        public boolean canPauseOrResume() {
            return this == UPLOADING || this == PAUSED;
        }

        public boolean canCancel() {
            return this == VALIDATING || this == UPLOADING || this == PAUSED;
        }
    }

    public static class ThroughputData {
        public int progress;
        public float averageThroughput;

        public ThroughputData(final int progress, final float averageThroughput) {
            this.progress = progress;
            this.averageThroughput = averageThroughput;
        }
    }

    private final McuMgrTransport transport;
    private final LogController logging;
    private final LegacyDeviceInfoProfile legacyDeviceInfo;
    private final CoroutineScope scope;
    @NonNull
    private final FirmwareUpgradeManager manager;
    @NonNull
    private final SUITUpgradeManager suitManager;
	private final Handler handler;
    private Runnable onSuitNotSupported;

    private final MutableLiveData<State> stateLiveData = new MutableLiveData<>();
    private final MutableLiveData<ThroughputData> progressLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> advancedSettingsExpanded = new MutableLiveData<>();
    private final MutableLiveData<McuMgrException> errorLiveData = new MutableLiveData<>();
    private final SingleLiveEvent<Void> cancelledEvent = new SingleLiveEvent<>();

    private final SingleLiveEvent<ReleaseInformation> otaReadyEvent = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> otaNotSupportedEvent = new SingleLiveEvent<>();
    private final SingleLiveEvent<Throwable> networkErrorEvent = new SingleLiveEvent<>();

    private long uploadStartTimestamp;
	private int imageSize, bytesSent, bytesSentSinceUploadStated, lastProgress;
    /** A value indicating that the upload has not been started before. */
    private final static int NOT_STARTED = -1;
	/** How often the throughput data should be sent to the graph. */
	private final static long REFRESH_RATE = 100L; /* ms */

    @Inject
    ImageUpgradeViewModel(@NonNull final McuMgrTransport transporter,
                          @NonNull final FirmwareUpgradeManager manager,
                          @NonNull final SUITUpgradeManager suitManager,
                          @NonNull final LogController logging,
                          @NonNull final LegacyDeviceInfoProfile legacyDeviceInfo,
                          @NonNull final CoroutineScope scope,
                          @NonNull @Named("busy") final MutableLiveData<Boolean> state) {
        super(state);
        this.transport = transporter;
        this.logging = logging;
        this.legacyDeviceInfo = legacyDeviceInfo;
        this.scope = scope;
        this.manager = manager;
        this.manager.setFirmwareUpgradeCallback(new FirmwareUpgradeCallback<>() {

            @Override
            public void onUpgradeStarted(final FirmwareUpgradeController controller) {
                ImageUpgradeViewModel.this.onUpgradeStarted(State.VALIDATING);
            }

            @Override
            public void onStateChanged(
                    final FirmwareUpgradeManager.State prevState,
                    final FirmwareUpgradeManager.State newState
            ) {
                setLoggingEnabled(newState != FirmwareUpgradeManager.State.UPLOAD);
                switch (newState) {
                    case UPLOAD -> {
                        Timber.i("Uploading firmware...");
                        bytesSentSinceUploadStated = NOT_STARTED;
                        stateLiveData.postValue(State.UPLOADING);
                    }
                    case TEST -> {
                        handler.removeCallbacks(graphUpdater);
                        stateLiveData.postValue(State.TESTING);
                    }
                    case CONFIRM -> {
                        handler.removeCallbacks(graphUpdater);
                        stateLiveData.postValue(State.CONFIRMING);
                    }
                    case RESET -> stateLiveData.postValue(State.RESETTING);
                }
            }

            @Override
            public void onUploadProgressChanged(final int bytesSent, final int imageSize, final long timestamp) {
                setLoggingEnabled(bytesSent == imageSize);
                ImageUpgradeViewModel.this.onUploadProgressChanged(bytesSent, imageSize, timestamp);
            }

            @Override
            public void onUpgradeCompleted() {
                ImageUpgradeViewModel.this.onUpgradeCompleted();
            }

            @Override
            public void onUpgradeCanceled(final FirmwareUpgradeManager.State state) {
                ImageUpgradeViewModel.this.onUpgradeCancelled();
            }

            @Override
            public void onUpgradeFailed(final FirmwareUpgradeManager.State state, final McuMgrException error) {
                ImageUpgradeViewModel.this.onUpgradeFailed(error);
            }
        });

        this.suitManager = suitManager;
        this.suitManager.setFirmwareUpgradeCallback(new FirmwareUpgradeCallback<>() {
            @Override
            public void onUpgradeStarted(FirmwareUpgradeController controller) {
                Timber.i("Upgrade started");
                ImageUpgradeViewModel.this.onUpgradeStarted(State.PROCESSING);
            }

            @Override
            public void onStateChanged(SUITUpgradeManager.State prevState, SUITUpgradeManager.State newState) {
                setLoggingEnabled(newState == SUITUpgradeManager.State.PROCESSING);
                switch (newState) {
                    case PROCESSING -> {
                        handler.removeCallbacks(graphUpdater);
                        stateLiveData.postValue(State.PROCESSING);
                    }
                    case UPLOADING_ENVELOPE -> {
                        Timber.i("Uploading envelope...");
                        bytesSentSinceUploadStated = NOT_STARTED;
                        stateLiveData.postValue(State.UPLOADING);
                    }
                    case UPLOADING_RESOURCE -> {
                        Timber.i("Uploading resource...");
                        bytesSentSinceUploadStated = NOT_STARTED;
                        stateLiveData.postValue(State.UPLOADING);
                    }
                }
            }

            @Override
            public void onUploadProgressChanged(final int bytesSent, final int imageSize, final long timestamp) {
                // Log the last part of the logs.
                if (bytesSent > imageSize - 510) {
                    setLoggingEnabled(true);
                }
                ImageUpgradeViewModel.this.onUploadProgressChanged(bytesSent, imageSize, timestamp);
            }

            @Override
            public void onUpgradeCompleted() {
                setLoggingEnabled(true);
                ImageUpgradeViewModel.this.onUpgradeCompleted();
            }

            @Override
            public void onUpgradeCanceled(final SUITUpgradeManager.State state) {
                ImageUpgradeViewModel.this.onUpgradeCancelled();
            }

            @Override
            public void onUpgradeFailed(final SUITUpgradeManager.State state, final McuMgrException error) {
                ImageUpgradeViewModel.this.onUpgradeFailed(error);
            }
        });
        // The handler only drives the progress refresh, so the main looper is enough.
        this.handler = new Handler(Looper.getMainLooper());

        stateLiveData.setValue(State.IDLE);
        progressLiveData.setValue(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        manager.setFirmwareUpgradeCallback(null);
        suitManager.setFirmwareUpgradeCallback(null);
        onSuitNotSupported = null;
    }

    @NonNull
    public LiveData<Boolean> getAdvancedSettingsState() {
        return advancedSettingsExpanded;
    }

    public void setAdvancedSettingsExpanded(final boolean expanded) {
        advancedSettingsExpanded.setValue(expanded);
    }

    @NonNull
    public LiveData<State> getState() {
        return stateLiveData;
    }

    /**
     * Returns current transfer speed in KB/s.
     */
    @NonNull
    public LiveData<ThroughputData> getProgress() {
        return progressLiveData;
    }

    /**
     * The parameters of the link shown on the throughput graph, or null when the transport is
     * not a Bluetooth LE one and has no link to report on.
     */
    @Nullable
    public LiveData<ThroughputParameters> getConnectionParameters() {
        return ThroughputParameters.observe(transport, scope);
    }

    @NonNull
    public LiveData<McuMgrException> getError() {
        return errorLiveData;
    }

    @NonNull
    public LiveData<Void> getCancelledEvent() {
        return cancelledEvent;
    }

    @NonNull
    public LiveData<ReleaseInformation> getOtaReadyEvent() {
        return otaReadyEvent;
    }

    @NonNull
    public LiveData<Void> getOtaNotSupportedEvent() {
        return otaNotSupportedEvent;
    }

    @NonNull
    public LiveData<Throwable> getNetworkErrorEvent() {
        return networkErrorEvent;
    }

    public void checkForUpdate() {
        setBusy();
        final OtaManager otaManager = new OtaManager();

        // First, try getting the device info by reading from the Memfault group.
        otaManager.getLatestRelease(transport, new ReleaseCallback() {
            @Override
            public void onSuccess(final @NotNull ReleaseInformation releaseInformation) {
                postReady();
                otaReadyEvent.postValue(releaseInformation);
            }

            @Override
            public void onError(final @NotNull Throwable t) {
                logError(t);

                // If there's no Memfault group, read the same data from Device Information
                // Service (DIS). This is legacy mode. It will be removed in the future.
                if (t instanceof McuMgrException) {
                    final DeviceInfo deviceInfo = legacyDeviceInfo.getDeviceInfo().getValue();
                    final String projectKey = legacyDeviceInfo.getProjectKey().getValue();
                    if (deviceInfo == null || projectKey == null) {
                        postReady();
                        otaNotSupportedEvent.post();
                        return;
                    }

                    otaManager.getLatestRelease(deviceInfo, projectKey, new ReleaseCallback() {
                        @Override
                        public void onSuccess(final @NotNull ReleaseInformation releaseInformation) {
                            postReady();
                            otaReadyEvent.postValue(releaseInformation);
                        }

                        @Override
                        public void onError(final @NotNull Throwable t) {
                            logError(t);
                            postReady();
                            networkErrorEvent.postValue(t);
                        }
                    });
                } else {
                    postReady();
                    networkErrorEvent.postValue(t);
                }
            }
        });
    }

    public void upgrade(@NonNull final byte[] data,
                        @NonNull final FirmwareUpgradeManager.Mode mode,
                        final boolean eraseSettings,
                        final int estimatedSwapTime,
                        final int windowCapacity,
                        final int memoryAlignment) {
        // If SUIT is not supported by the device, the image will be uploaded using Image Manager.
        onSuitNotSupported = () -> {
            onSuitNotSupported = null;
            ImageSet images;
            try {
                // The following supports Mcu Manager Image and a standalone SUIT envelope.
                images = new ImageSet().add(data);
            } catch (final Exception e) {
                try {
                    final ZipPackage zip = new ZipPackage(data);
                    final byte[] envelope = zip.getSuitEnvelope();
                    if (envelope != null) {
                        // SUIT envelope and cache images can also be sent using Image Manager.
                        // For example for device recovery.
                        images = new ImageSet().add(envelope);
                        // Check if the ZIP contains cache images.
                        final CacheImageSet cacheImages = zip.getCacheBinaries();
                        if (cacheImages != null) {
                            images.set(cacheImages.getImages());
                        }
                    } else {
                        images = zip.getBinaries();
                    }
                } catch (final Exception e1) {
                    Timber.e(e, "Invalid image file");
                    errorLiveData.setValue(new McuMgrException("Invalid image file."));
                    return;
                }
            }
            upgradeWithImageManager(images, mode, eraseSettings, estimatedSwapTime, windowCapacity, memoryAlignment);
        };
        // First, try using SUIT Manager.
        try {
            // Try parsing as SUIT Envelope.
            final SUITImage envelope = SUITImage.fromBytes(data);
            // Having a single ZIP file we can't provide additional resources.
            // HTTP(S) transport is currently not supported.
            suitManager.setResourceCallback(new SUITUpgradeManager.OnResourceRequiredCallback() {
                @Override
                public void onResourceRequired(@NotNull URI uri, SUITUpgradeManager.@NotNull ResourceCallback callback) {
                    callback.error(new UnsupportedOperationException("Resource required callback not supported."));
                }

                @Override
                public void onUploadCancelled() {
                    // Ignore
                }
            });
            upgradeWithSUITManager(envelope, null, windowCapacity, memoryAlignment);
        } catch (final Exception e) {
            try {
                // Try reading SUIT envelope from ZIP file.
                final ZipPackage zip = new ZipPackage(data);
                // A ZIP file may contain multiple files, but only one SUIT envelope.
                final byte[] envelope = zip.getSuitEnvelope();
                if (envelope != null) {
                    final SUITImage suitImage = SUITImage.fromBytes(envelope);
                    final CacheImageSet cacheImages = zip.getCacheBinaries();
                    // During the upload, SUIT manager may request additional resources.
                    // This callback will return the requested resource from the ZIP file.
                    suitManager.setResourceCallback(new SUITUpgradeManager.OnResourceRequiredCallback() {
                        @Override
                        public void onResourceRequired(@NotNull final URI uri, @NotNull final SUITUpgradeManager.ResourceCallback callback) {
                            if (!uri.getScheme().equals("file")) {
                                callback.error(new McuMgrException("Cannot obtain " + uri + ". Only file:// scheme is supported."));
                                return;
                            }
                            final byte[] data = zip.getResource(uri.getSchemeSpecificPart().substring(2));
                            if (data != null) {
                                callback.provide(data);
                            } else {
                                callback.error(new McuMgrException(uri + " not found in ZIP file."));
                            }
                        }

                        @Override
                        public void onUploadCancelled() {
                            // Ignore
                        }
                    });
                    upgradeWithSUITManager(suitImage, cacheImages, windowCapacity, memoryAlignment);
                    return;
                }
                throw new NullPointerException();
            } catch (final Exception e2) {
                // Fallback to Image Manager.
                onSuitNotSupported.run();
            }
        }
    }

    private void upgradeWithImageManager(
            @NonNull final ImageSet images,
            @NonNull final FirmwareUpgradeManager.Mode mode,
            final boolean eraseSettings,
            final int estimatedSwapTime,
            final int windowCapacity,
            final int memoryAlignment
    ) {
        requestHighConnectionPriority();

        // Set the upgrade mode.
        manager.setMode(mode);

        final FirmwareUpgradeManager.Settings settings = new FirmwareUpgradeManager.Settings.Builder()
                .setEraseAppSettings(eraseSettings)
                // rF52840, due to how the flash memory works, requires ~20 sec to erase images.
                .setEstimatedSwapTime(estimatedSwapTime)
                // Set the window capacity. Values > 1 enable a new implementation for uploading
                // the images, which makes use of SMP pipelining feature.
                // The app will send this many packets immediately, without waiting for notification
                // confirming each packet. This value should be lower or equal to MCUMGR_TRANSPORT_NETBUF_COUNT - 1
                // (https://github.com/zephyrproject-rtos/zephyr/blob/19f645edd40b38e54f505135beced1919fdc7715/subsys/mgmt/mcumgr/transport/Kconfig#L32)
                // parameter in KConfig in NCS / Zephyr configuration and should also be supported
                // on Mynewt devices.
                // Mind, that in Zephyr,  before https://github.com/zephyrproject-rtos/zephyr/pull/41959
                // was merged, the device required data to be sent with memory alignment. Otherwise,
                // the device would ignore uneven bytes and reply with lower than expected offset
                // causing multiple packets to be sent again dropping the speed instead of increasing it.
                .setWindowCapacity(windowCapacity)
                // Set the selected memory alignment. In the app this defaults to 4 to match Nordic
                // devices, but can be modified in the UI.
                .setMemoryAlignment(memoryAlignment)
                .build();

        manager.start(images, settings);
    }

    private void upgradeWithSUITManager(
            @NonNull final SUITImage envelope,
            @Nullable final CacheImageSet cacheImages,
            final int windowCapacity,
            final int memoryAlignment
    ) {
        requestHighConnectionPriority();

        final FirmwareUpgradeSettings settings = new FirmwareUpgradeSettings.Builder()
                .setWindowCapacity(windowCapacity)
                .setMemoryAlignment(memoryAlignment)
                .build();
        suitManager.start(settings, envelope.getData(), cacheImages);
    }

    public void pause() {
        if (manager.isInProgress() || suitManager.isInProgress()) {
            handler.removeCallbacks(graphUpdater);
            stateLiveData.postValue(State.PAUSED);
            manager.pause();
            suitManager.pause();
            Timber.i("Upload paused");
            setLoggingEnabled(true);
            setReady();
        }
    }

    public void resume() {
        if (manager.isPaused() || suitManager.isPaused()) {
            setBusy();
            stateLiveData.postValue(State.UPLOADING);
            Timber.i("Upload resumed");
            bytesSentSinceUploadStated = NOT_STARTED;
            setLoggingEnabled(false);
            manager.resume();
            suitManager.resume();
        }
    }

    public void cancel() {
        manager.cancel();
        suitManager.cancel();
    }

    private final Runnable graphUpdater = new Runnable() {
        @Override
        public void run() {
            if (stateLiveData.getValue() != State.UPLOADING) {
                return;
            }

            final long timestamp = SystemClock.uptimeMillis();
            // Calculate the current upload progress.
            final int progress = (int) (bytesSent * 100.f /* % */ / imageSize);
            if (lastProgress != progress) {
                lastProgress = progress;

                // Calculate the average throughout.
                // This is done by diving number of bytes sent since upload has been started (or resumed)
                // by the time since that moment. The minimum time of MIN_INTERVAL ms prevents from
                // graph peaks that may happen when .
                final float bytesSentSinceUploadStarted = bytesSent - bytesSentSinceUploadStated;
                final float timeSinceUploadStarted = timestamp - uploadStartTimestamp;
                final float averageThroughput = bytesSentSinceUploadStarted / timeSinceUploadStarted; // bytes / ms = KB/s

                progressLiveData.postValue(new ThroughputData(progress, averageThroughput));
            }

            if (stateLiveData.getValue() == State.UPLOADING) {
                handler.postAtTime(this, timestamp + REFRESH_RATE);
            }
        }
    };

    private void onUpgradeStarted(State state) {
        postBusy();
        progressLiveData.setValue(null);
        stateLiveData.setValue(state);
    }

    private void onUploadProgressChanged(final int bytesSent, final int imageSize, final long timestamp) {
        ImageUpgradeViewModel.this.imageSize = imageSize;
        ImageUpgradeViewModel.this.bytesSent = bytesSent;

        final long uptimeMillis = SystemClock.uptimeMillis();

        // Check if this is the first time this method is called since:
        // - the start of an upload
        // - after resume
        if (bytesSentSinceUploadStated == NOT_STARTED) {
            lastProgress = NOT_STARTED;

            // If a new image started being sending, clear the progress graph.
            progressLiveData.postValue(null);

            // To calculate the throughput it is necessary to store the initial timestamp and
            // the number of bytes sent so far. Mind, that the upload may be resumed from any point,
            // not necessarily from the beginning.
            uploadStartTimestamp = uptimeMillis;
            bytesSentSinceUploadStated = bytesSent;

            // Begin updating the graph.
            handler.removeCallbacks(graphUpdater);
            handler.postAtTime(graphUpdater, uptimeMillis + REFRESH_RATE);
        }
        // When done, reset the counter.
        if (bytesSent == imageSize) {
            Timber.i("Image (%d bytes) sent in %d ms (avg speed: %f kB/s)",
                    imageSize - bytesSentSinceUploadStated,
                    uptimeMillis - uploadStartTimestamp,
                    (float) (imageSize - bytesSentSinceUploadStated) / (float) (uptimeMillis - uploadStartTimestamp)
            );
            // Finish the graph.
            graphUpdater.run();
            // Reset the initial bytes counter, so if there is a next image uploaded afterwards,
            // it will start the throughput calculations again.
            bytesSentSinceUploadStated = NOT_STARTED;
        }
    }

    private void onUpgradeCompleted() {
        stateLiveData.postValue(State.COMPLETE);
        onSuitNotSupported = null;
        Timber.i("Upgrade complete");
        setLoggingEnabled(true);
        postReady();
    }

    private void onUpgradeCancelled() {
        handler.removeCallbacks(graphUpdater);
        progressLiveData.postValue(null);
        stateLiveData.postValue(State.IDLE);
        cancelledEvent.post();
        onSuitNotSupported = null;
        Timber.w("Upgrade cancelled");
        setLoggingEnabled(true);
        postReady();
    }

    private void onUpgradeFailed(final McuMgrException error) {
        if (onSuitNotSupported != null &&
                error instanceof McuMgrErrorException ee &&
                ee.getCode() == McuMgrErrorCode.NOT_SUPPORTED) {
            suitManager.setResourceCallback(null);
            onSuitNotSupported.run();
            return;
        }
        handler.removeCallbacks(graphUpdater);
        errorLiveData.postValue(error);
        onSuitNotSupported = null;
        setLoggingEnabled(true);
        postReady();
    }

    private void requestHighConnectionPriority() {
        if (transport instanceof McuMgrBleTransport ble) {
            ble.requestConnectionPriority(ConnectionPriority.HIGH);
        }
    }

    private void setLoggingEnabled(final boolean enabled) {
        logging.setVerbose(enabled);
    }

    private void logError(final @NotNull Throwable throwable) {
        // This gets logged to nRF Logger:
        Timber.e(throwable.getMessage());
        // This goes only to LogCat.
        Log.e("ImageUpgradeViewModel", "Network error", throwable);
    }
}
