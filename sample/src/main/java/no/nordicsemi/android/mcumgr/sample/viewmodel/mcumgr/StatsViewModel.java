/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.managers.DefaultManager;
import no.nordicsemi.android.mcumgr.managers.StatsManager;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrMpStatResponse;
import no.nordicsemi.android.mcumgr.response.stat.McuMgrStatListResponse;
import no.nordicsemi.android.mcumgr.response.stat.McuMgrStatResponse;

public class StatsViewModel extends McuMgrViewModel {
    private final StatsManager manager;
    private final DefaultManager osManager;

    private final MutableLiveData<List<McuMgrStatResponse>> responseLiveData = new MutableLiveData<>();
    private final MutableLiveData<McuMgrException> errorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Map<String, McuMgrMpStatResponse.MpStat>> memoryPoolsLiveData = new MutableLiveData<>();
    private final MutableLiveData<McuMgrException> memoryPoolsErrorLiveData = new MutableLiveData<>();

    @Inject
    StatsViewModel(final StatsManager manager,
                   final DefaultManager osManager,
                   @Named("busy") final MutableLiveData<Boolean> state) {
        super(state);
        this.manager = manager;
        this.osManager = osManager;
    }

    public LiveData<List<McuMgrStatResponse>> getResponse() {
        return responseLiveData;
    }

    @NonNull
    public LiveData<McuMgrException> getError() {
        return errorLiveData;
    }

    /** The memory pools, by name (or ID, if the device does not report names). */
    @NonNull
    public LiveData<Map<String, McuMgrMpStatResponse.MpStat>> getMemoryPools() {
        return memoryPoolsLiveData;
    }

    @NonNull
    public LiveData<McuMgrException> getMemoryPoolsError() {
        return memoryPoolsErrorLiveData;
    }

    public void readMemoryPools() {
        setBusy();
        memoryPoolsErrorLiveData.setValue(null);
        osManager.mpstat(new McuMgrCallback<>() {
            @Override
            public void onResponse(@NonNull final McuMgrMpStatResponse response) {
                memoryPoolsLiveData.postValue(response.getMpools());
                postReady();
            }

            @Override
            public void onError(@NonNull final McuMgrException error) {
                memoryPoolsErrorLiveData.postValue(error);
                postReady();
            }
        });
    }

    public void readStats() {
        setBusy();
        errorLiveData.setValue(null);
        manager.list(new McuMgrCallback<>() {
            @Override
            public void onResponse(@NonNull final McuMgrStatListResponse listResponse) {
                final List<McuMgrStatResponse> list = new ArrayList<>(listResponse.stat_list.length);

                // Request stats for each module.
                if (listResponse.stat_list.length > 0) {
                    final String module = listResponse.stat_list[0];
                    manager.read(module, new McuMgrCallback<>() {
                        private int i = 1;

                        @Override
                        public void onResponse(@NonNull final McuMgrStatResponse response) {
                            list.add(response);
                            responseLiveData.postValue(list);

                            if (i < listResponse.stat_list.length) {
                                final String module = listResponse.stat_list[i++];
                                manager.read(module, this);
                            } else {
                                postReady();
                            }
                        }
                        @Override
                        public void onError(@NonNull final McuMgrException error) {
                            errorLiveData.postValue(error);
                            postReady();
                        }
                    });
                } else {
                    responseLiveData.postValue(list);
                    postReady();
                }
            }

            @Override
            public void onError(@NonNull final McuMgrException error) {
                errorLiveData.postValue(error);
                postReady();
            }
        });
    }
}
