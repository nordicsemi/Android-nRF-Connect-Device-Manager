package no.nordicsemi.android.mcumgr.dfu.mcuboot.task;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State;
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.log.McuMgrLogger;
import no.nordicsemi.android.mcumgr.managers.ImageManager;
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse;
import no.nordicsemi.android.mcumgr.task.Task;
import no.nordicsemi.android.mcumgr.task.TaskManager;

/**
 * Checks that the new image is running after the reset, before it is confirmed.
 * <p>
 * An image that failed its test boot is not active: MCUboot reverted it, or erased it in Direct
 * XIP mode. Confirming it would install it permanently. MCUboot may also erase an image that fails
 * validation at boot, for example because of an invalid signature. This task is planned with
 * every {@link ConfirmAfterReset} and runs before all of them, so each image to be confirmed is
 * verified before the first one is.
 */
class VerifyAfterReset extends FirmwareUpgradeTask {
	private final byte @NotNull [] hash;
	private final int imageIndex;

	VerifyAfterReset(final int imageIndex, final byte @NotNull [] hash) {
		this.imageIndex = imageIndex;
		this.hash = hash;
	}

	@Override
	@NotNull
	public State getState() {
		return State.CONFIRM;
	}

	@Override
	public int getPriority() {
		return PRIORITY_VERIFY_AFTER_RESET;
	}

	@Override
	public int compareTo(Task<Settings, State> o) {
		// Images should be verified from lowest to highest image index.
		if (o instanceof VerifyAfterReset) {
			final VerifyAfterReset verify = (VerifyAfterReset) o;
			return imageIndex - verify.imageIndex;
		}
		return super.compareTo(o);
	}

	@Override
	public void start(final @NotNull TaskManager<Settings, State> performer) {
		final McuMgrLogger log = performer.getLog();

		final ImageManager manager = new ImageManager(performer.getTransport());
		manager.setLogger(log.getSink());
		manager.list(new McuMgrCallback<>() {
			@Override
			public void onResponse(@NotNull final McuMgrImageStateResponse response) {
				log.trace("List response: {}", response);
				// Check for an error return code.
				if (!response.isSuccess()) {
					performer.onTaskFailed(VerifyAfterReset.this, new McuMgrErrorException(response));
					return;
				}
				final McuMgrImageStateResponse.ImageSlot[] images = response.images;
				if (images == null) {
					log.error("Missing images information: {}", response);
					performer.onTaskFailed(VerifyAfterReset.this, new McuMgrException("Missing images information"));
					return;
				}
				// The new image must be in the active slot of its image.
				boolean found = false;
				for (final McuMgrImageStateResponse.ImageSlot slot : images) {
					if (slot.image == imageIndex && Arrays.equals(slot.hash, hash)) {
						if (slot.active) {
							performer.onTaskCompleted(VerifyAfterReset.this);
							return;
						}
						found = true;
					}
				}
				if (found) {
					log.warn("Image {} is not active after the reset, it will not be confirmed", imageIndex);
					performer.onTaskFailed(VerifyAfterReset.this,
							new McuMgrException("The new image is not active after the reset (image " + imageIndex + ")"));
				} else {
					log.warn("Image {} was not found after the reset, it will not be confirmed. Possible reason: the bootloader erased it", imageIndex);
					performer.onTaskFailed(VerifyAfterReset.this,
							new McuMgrException("The new image was not found after the reset (image " + imageIndex + ")"));
				}
			}

			@Override
			public void onError(@NotNull final McuMgrException e) {
				performer.onTaskFailed(VerifyAfterReset.this, e);
			}
		});
	}
}
