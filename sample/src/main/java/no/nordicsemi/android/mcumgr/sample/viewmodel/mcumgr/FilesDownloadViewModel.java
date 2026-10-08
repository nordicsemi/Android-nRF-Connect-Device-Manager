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
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.managers.FsManager;
import no.nordicsemi.android.mcumgr.sample.log.LogController;
import no.nordicsemi.android.mcumgr.sample.viewmodel.SingleLiveEvent;
import no.nordicsemi.android.mcumgr.transfer.DownloadCallback;
import no.nordicsemi.android.mcumgr.transfer.TransferController;
import no.nordicsemi.kotlin.ble.client.android.ConnectionPriority;

@SuppressWarnings("unused")
public class FilesDownloadViewModel extends McuMgrViewModel implements DownloadCallback {
    private final FsManager manager;
    private final McuMgrTransport transport;
    private final LogController logging;
    private TransferController controller;

    private final MutableLiveData<Integer> progressLiveData = new MutableLiveData<>();
    private final MutableLiveData<byte[]> responseLiveData = new MutableLiveData<>();
    private final MutableLiveData<McuMgrException> errorLiveData = new MutableLiveData<>();
    private final SingleLiveEvent<Void> cancelledEvent = new SingleLiveEvent<>();

    @Inject
    FilesDownloadViewModel(final FsManager manager,
                           final McuMgrTransport transport,
                           final LogController logging,
                           @Named("busy") final MutableLiveData<Boolean> state) {
        super(state);
        this.transport = transport;
        this.logging = logging;
        this.manager = manager;
    }

    @NonNull
    public LiveData<Integer> getProgress() {
        return progressLiveData;
    }

    @NonNull
    public LiveData<byte[]> getResponse() {
        return responseLiveData;
    }

    @NonNull
    public LiveData<McuMgrException> getError() {
        return errorLiveData;
    }

    @NonNull
    public LiveData<Void> getCancelledEvent() {
        return cancelledEvent;
    }

    public void download(final String path) {
        if (controller != null) {
            return;
        }
        setBusy();
        requestHighConnectionPriority();
        setLoggingEnabled(false);
        controller = manager.fileDownload(path, this);
    }

    public void pause() {
        final TransferController controller = this.controller;
        if (controller != null) {
            setLoggingEnabled(true);
            controller.pause();
            setReady();
        }
    }

    public void resume() {
        final TransferController controller = this.controller;
        if (controller != null) {
            setBusy();
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
    public void onDownloadProgressChanged(final int current, final int total, final long timestamp) {
        // Convert to percent
        progressLiveData.postValue((int) (current * 100.f / total));
    }

    @Override
    public void onDownloadFailed(@NonNull final McuMgrException error) {
        controller = null;
        progressLiveData.postValue(0);
        errorLiveData.postValue(error);
        setLoggingEnabled(true);
        postReady();
    }

    @Override
    public void onDownloadCanceled() {
        controller = null;
        progressLiveData.postValue(0);
        cancelledEvent.post();
        setLoggingEnabled(true);
        postReady();
    }

    @Override
    public void onDownloadCompleted(@NonNull final byte[] data) {
        controller = null;
        progressLiveData.postValue(0);
        responseLiveData.postValue(data);
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
