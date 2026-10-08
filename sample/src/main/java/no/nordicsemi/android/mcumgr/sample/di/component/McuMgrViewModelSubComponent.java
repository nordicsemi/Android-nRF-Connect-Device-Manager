/*
 * Copyright (c) 2018, Nordic Semiconductor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.sample.di.component;

import dagger.Subcomponent;
import no.nordicsemi.android.mcumgr.sample.viewmodel.FileBrowserViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.DeviceStatusViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.EchoViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ExecViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.FilesDownloadViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.FilesUploadViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ImageControlViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ImageSettingsViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ImageUpgradeViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ImageUploadViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.McuMgrViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.McuMgrViewModelFactory;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ObservabilityViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.ResetViewModel;
import no.nordicsemi.android.mcumgr.sample.viewmodel.mcumgr.StatsViewModel;

/**
 * A subcomponent to create ViewModels. It is called by the
 * {@link McuMgrViewModelFactory}. Using this component allows
 * ViewModels to define {@link javax.inject.Inject} constructors.
 */
@Subcomponent
public interface McuMgrViewModelSubComponent {
    @Subcomponent.Builder
    interface Builder {
        McuMgrViewModelSubComponent build();
    }

    DeviceStatusViewModel deviceStatusViewModel();
    EchoViewModel echoViewModel();
    ResetViewModel resetViewModel();
    ObservabilityViewModel observabilityViewModel();
    StatsViewModel statsViewModel();
    McuMgrViewModel mcuMgrViewModel();
    FileBrowserViewModel fileBrowserViewModel();
    ImageUpgradeViewModel imageUpgradeViewModel();
    ImageUploadViewModel imageUploadViewModel();
    ImageControlViewModel imageControlViewModel();
    ImageSettingsViewModel imageSettingsViewModel();
    FilesDownloadViewModel filesDownloadViewModel();
    FilesUploadViewModel filesUploadViewModel();
    ExecViewModel execViewModel();
}
