package io.runtime.mcumgr.dfu.mcuboot.task;

import android.os.Handler;
import android.os.SystemClock;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.runtime.mcumgr.McuMgrCallback;
import io.runtime.mcumgr.McuMgrTransport;
import io.runtime.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings;
import io.runtime.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State;
import io.runtime.mcumgr.exception.McuMgrErrorException;
import io.runtime.mcumgr.exception.McuMgrException;
import io.runtime.mcumgr.managers.DefaultManager;
import io.runtime.mcumgr.response.dflt.McuMgrOsResponse;
import io.runtime.mcumgr.task.TaskManager;

class Reset extends FirmwareUpgradeTask {
	private final static Logger LOG = LoggerFactory.getLogger(Reset.class);

	/**
	 * The timestamp at which the response to Reset command was received.
	 * Assuming that the target device has reset just after sending this response,
	 * the time difference between this moment and receiving disconnection event may be deducted
	 * from the estimated swap time.
	 */
	private long mResetResponseTime;

	private final boolean mNoSwap;

	Reset(final boolean noSwap) {
		this.mNoSwap = noSwap;
	}

	@Override
	@NotNull
	public State getState() {
		return State.RESET;
	}

	@Override
	public int getPriority() {
		return PRIORITY_RESET;
	}

	@Override
	public void start(@NotNull final TaskManager<Settings, State> performer) {
		final Settings settings = performer.getSettings();
		final McuMgrTransport transport = performer.getTransport();

		transport.addObserver(new McuMgrTransport.ConnectionObserver() {
			@Override
			public void onConnected() {
				// Do nothing
			}

			@Override
			public void onDisconnected() {
				LOG.info("Device disconnected");

				transport.removeObserver(this);

				// If there is no swap, we're done. No need to wait anything.
				if (mNoSwap) {
					performer.onTaskCompleted(Reset.this);
					return;
				}

				// Calculate the delay need that we need to wait until the swap is complete.
				long now = SystemClock.elapsedRealtime();
				if (mResetResponseTime == 0) {
					// In case the response to Reset command wasn't received before the disconnection
					// start counting remaining time from now.
					mResetResponseTime = now;
				}
				long timeSinceReset = now - mResetResponseTime;
				long remainingTime = settings.estimatedSwapTime - timeSinceReset;
				final Runnable complete = () -> performer.onTaskCompleted(Reset.this);

				if (remainingTime > 0) {
					LOG.trace("Waiting remaining {} ms for the swap operation to complete", remainingTime);
					new Handler().postDelayed(complete, remainingTime);
				} else {
					complete.run();
				}
			}
		});

		final DefaultManager manager = new DefaultManager(transport);
		manager.reset(new McuMgrCallback<>() {
			@Override
			public void onResponse(@NotNull final McuMgrOsResponse response) {
				// Check for an error return code.
				if (!response.isSuccess()) {
					performer.onTaskFailed(Reset.this, new McuMgrErrorException(response.getReturnCode()));
					return;
				}
				mResetResponseTime = SystemClock.elapsedRealtime();
				LOG.trace("Reset request success. Waiting for disconnect...");
			}

			@Override
			public void onError(@NotNull final McuMgrException error) {
				// A McuMgrDisconnectedException may be returned if the device has disconnected
				// before the response was received. In this case, assume that the reset
				// was successful and the device is now disconnected.
				// See: https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/issues/242
			}
		});
	}
}
