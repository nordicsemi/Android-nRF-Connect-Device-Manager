/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Named;

import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.image.McuMgrImage;
import no.nordicsemi.android.mcumgr.image.SUITImage;
import no.nordicsemi.android.mcumgr.managers.ImageManager;
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse;
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport;
import no.nordicsemi.android.mcumgr.sample.log.LogController;
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority;
import no.nordicsemi.android.mcumgr.sample.viewmodel.SingleLiveEvent;
import no.nordicsemi.android.mcumgr.transfer.TransferController;
import no.nordicsemi.android.mcumgr.transfer.UploadCallback;

public class ImageUploadViewModel extends McuMgrViewModel implements UploadCallback {
    public enum State {
        IDLE,
        VALIDATING,
        UPLOADING,
        PAUSED,
        COMPLETE;

        public boolean inProgress() {
            return this != IDLE && this != COMPLETE;
        }

        public boolean canPauseOrResume() {
            return this == UPLOADING || this == PAUSED;
        }

        public boolean canCancel() {
            return this == UPLOADING || this == PAUSED;
        }
    }

    private final ImageManager manager;
    private final McuMgrTransport transport;
    private final LogController logging;
    private TransferController controller;

    private final MutableLiveData<State> stateLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> progressLiveData = new MutableLiveData<>();
    private final MutableLiveData<Float> transferSpeedLiveData = new MutableLiveData<>();
    private final SingleLiveEvent<McuMgrException> errorLiveData = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> cancelledEvent = new SingleLiveEvent<>();
    private final SingleLiveEvent<Boolean> hashAlreadyFound = new SingleLiveEvent<>();

    private long uploadStartTimestamp;
    private int initialBytes;

    @Inject
    ImageUploadViewModel(final ImageManager manager,
                         final McuMgrTransport transport,
                         final LogController logging,
                         @Named("busy") final MutableLiveData<Boolean> state) {
        super(state);
        this.transport = transport;
        this.logging = logging;
        this.manager = manager;
        this.stateLiveData.setValue(State.IDLE);
        this.progressLiveData.setValue(0);
    }

    @NonNull
    public LiveData<State> getState() {
        return stateLiveData;
    }

    @NonNull
    public LiveData<Integer> getProgress() {
        return progressLiveData;
    }

    /**
     * Returns current transfer speed in KB/s.
     */
    @NonNull
    public LiveData<Float> getTransferSpeed() {
        return transferSpeedLiveData;
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
    public LiveData<Boolean> getHashAlreadyFoundEvent() {
        return hashAlreadyFound;
    }

    public void upload(@NonNull final byte[] data, final int image, boolean force) {
        if (controller != null) {
            return;
        }
        setBusy();
        stateLiveData.setValue(State.VALIDATING);

        byte[] tmpHash;
        try {
            tmpHash = McuMgrImage.getHash(data);
        } catch (final McuMgrException e) {
            try {
                tmpHash = SUITImage.getHash(data);
            } catch (final McuMgrException e2) {
                // If the file does not contain a hash, we will not be able to check if the
                // firmware has already been uploaded. We will upload it anyway.
                // This has been changed for the sake of cache images for devices with SUIT bootloader,
                // which do not have a hash in the image.
                // This will also allow to send any file to the device, not only firmware.
                // Good for testing.
                tmpHash = null;
            }
        }
        final byte[] hash = tmpHash;

        // Sends the firmware. This may return NO MEMORY error if slot 1 is
        // filled with an image with pending or confirmed flags set.
        final Runnable upload = () -> {
            requestHighConnectionPriority();
            stateLiveData.postValue(State.UPLOADING);
            initialBytes = 0;
            setLoggingEnabled(false);
            controller = manager.imageUpload(data, image, ImageUploadViewModel.this);
        };

        // Hash is null in case of SUIT cache partitions, or other files which aren't known firmware.
        if (tmpHash != null) {
            manager.list(new McuMgrCallback<>() {
                @Override
                public void onResponse(@NonNull final McuMgrImageStateResponse response) {
                    // Check if the fw has already been sent before.
                    McuMgrImageStateResponse.ImageSlot theSameImage = null;
                    for (final McuMgrImageStateResponse.ImageSlot image : response.images) {
                        if (Arrays.equals(hash, image.hash)) {
                            theSameImage = image;
                            break;
                        }
                    }
                    // If yes, no need to send again.
                    if (!force && theSameImage != null) {
                        hashAlreadyFound.postValue(theSameImage.active);
                        postReady();
                        return;
                    }

                    upload.run();
                }

                @Override
                public void onError(@NonNull final McuMgrException error) {
                    errorLiveData.postValue(error);
                    postReady();
                }
            });
        } else {
            upload.run();
        }
    }

    public void pause() {
        final TransferController controller = this.controller;
        if (controller != null) {
            stateLiveData.setValue(State.PAUSED);
            setLoggingEnabled(true);
            controller.pause();
            setReady();
        }
    }

    public void resume() {
        final TransferController controller = this.controller;
        if (controller != null) {
            setBusy();
            stateLiveData.setValue(State.UPLOADING);
            initialBytes = 0;
            setLoggingEnabled(false);
            controller.resume();
        }
    }

    public void cancel() {
        final TransferController controller = this.controller;
        if (controller != null) {
            controller.cancel();
        }
    }

    @Override
    public void onUploadProgressChanged(final int bytesSent, final int imageSize, final long timestamp) {
        if (initialBytes == 0) {
            uploadStartTimestamp = timestamp;
            initialBytes = bytesSent;
        } else {
            final int bytesSentSinceUploadStarted = bytesSent - initialBytes;
            final long timeSinceUploadStarted = timestamp - uploadStartTimestamp;
            // bytes / ms = KB/s
            transferSpeedLiveData.postValue((float) bytesSentSinceUploadStarted / (float) timeSinceUploadStarted);
        }
        // Convert to percent
        progressLiveData.postValue((int) (bytesSent * 100.f / imageSize));
    }

    @Override
    public void onUploadFailed(@NonNull final McuMgrException error) {
        controller = null;
        progressLiveData.postValue(0);
        errorLiveData.postValue(error);
        setLoggingEnabled(true);
        postReady();
    }

    @Override
    public void onUploadCanceled() {
        controller = null;
        progressLiveData.postValue(0);
        stateLiveData.postValue(State.IDLE);
        cancelledEvent.post();
        setLoggingEnabled(true);
        postReady();
    }

    @Override
    public void onUploadCompleted() {
        controller = null;
        progressLiveData.postValue(0);
        stateLiveData.postValue(State.COMPLETE);
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
}
