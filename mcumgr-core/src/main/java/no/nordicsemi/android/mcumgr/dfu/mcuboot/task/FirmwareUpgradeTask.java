package no.nordicsemi.android.mcumgr.dfu.mcuboot.task;

import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State;
import no.nordicsemi.android.mcumgr.task.Task;

public abstract class FirmwareUpgradeTask extends Task<Settings, State> {
	final static int PRIORITY_RESET_INITIAL = 0;
	final static int PRIORITY_VALIDATE = 1;
	final static int PRIORITY_UPLOAD = 2;
	final static int PRIORITY_ERASE_SETTINGS = PRIORITY_UPLOAD + 1;
	final static int PRIORITY_TEST_AFTER_UPLOAD = PRIORITY_ERASE_SETTINGS + 1;
	final static int PRIORITY_CONFIRM_AFTER_UPLOAD = PRIORITY_ERASE_SETTINGS + 1;
	final static int PRIORITY_RESET = 10;
	final static int PRIORITY_VERIFY_AFTER_RESET = PRIORITY_RESET + 1;
	final static int PRIORITY_CONFIRM_AFTER_RESET = PRIORITY_VERIFY_AFTER_RESET + 1;
}
