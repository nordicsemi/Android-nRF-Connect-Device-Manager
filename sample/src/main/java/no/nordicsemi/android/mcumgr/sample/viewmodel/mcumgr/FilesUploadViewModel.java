/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Inject;
import javax.inject.Named;

import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.managers.FsManager;
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport;
import no.nordicsemi.android.mcumgr.sample.log.LogController;
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority;
import no.nordicsemi.android.mcumgr.sample.viewmodel.SingleLiveEvent;
import no.nordicsemi.android.mcumgr.transfer.FileUploader;
import no.nordicsemi.android.mcumgr.transfer.TransferController;
import no.nordicsemi.android.mcumgr.transfer.UploadCallback;

public class FilesUploadViewModel extends McuMgrViewModel implements UploadCallback {
    public enum State {
        IDLE,
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

    private final FsManager manager;
    private final McuMgrTransport transport;
    private final LogController logging;
    private TransferController controller;

    private final MutableLiveData<State> stateLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> progressLiveData = new MutableLiveData<>();
    private final MutableLiveData<Float> transferSpeedLiveData = new MutableLiveData<>();
    private final MutableLiveData<McuMgrException> errorLiveData = new MutableLiveData<>();
    private final SingleLiveEvent<Void> cancelledEvent = new SingleLiveEvent<>();

    private long uploadStartTimestamp;
    private int initialBytes;

    @Inject
    FilesUploadViewModel(final FsManager manager,
                         final McuMgrTransport transport,
                         final LogController logging,
                         @Named("busy") final MutableLiveData<Boolean> state) {
        super(state);
        this.transport = transport;
        this.logging = logging;
        this.manager = manager;
        this.stateLiveData.setValue(State.IDLE);
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

    public void upload(final String path, final byte[] data) {
        if (controller != null) {
            return;
        }
        setBusy();
        stateLiveData.setValue(State.UPLOADING);
        requestHighConnectionPriority();
        setLoggingEnabled(false);
        initialBytes = 0;

        // The previous way of uploading the file:
        // controller = manager.fileUpload(path, data, this);

        // Improved uploader which makes use of window upload mechanism
        // (sending multiple packets without waiting for the response):
        controller = new FileUploader(manager, path, data, 3, 4)
                .uploadAsync(this);
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
            stateLiveData.setValue(State.UPLOADING);
            setBusy();
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
    public void onUploadProgressChanged(final int bytesSent, final int fileSize, final long timestamp) {
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
        progressLiveData.postValue((int) (bytesSent * 100.f / fileSize));
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
