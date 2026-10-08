package no.nordicsemi.android.mcumgr.dfu.mcuboot.task;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import no.nordicsemi.android.mcumgr.McuMgrCallback;
import no.nordicsemi.android.mcumgr.McuMgrErrorCode;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Mode;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.TargetImage;
import no.nordicsemi.android.mcumgr.dfu.suit.model.CacheImage;
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.log.McuMgrLogger;
import no.nordicsemi.android.mcumgr.image.ImageWithHash;
import no.nordicsemi.android.mcumgr.image.SUITImage;
import no.nordicsemi.android.mcumgr.managers.DefaultManager;
import no.nordicsemi.android.mcumgr.managers.ImageManager;
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrBootloaderInfoResponse;
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse;
import no.nordicsemi.android.mcumgr.task.TaskManager;

class Validate extends FirmwareUpgradeTask {
	@NotNull
	private final ImageSet images;
	@NotNull
	private final Mode mode;
	private final boolean forceReset;

	Validate(final @NotNull Mode mode,
			 final @NotNull ImageSet images) {
		this.mode = mode;
		this.images = images;
		this.forceReset = false;
	}

	private Validate(final @NotNull Mode mode,
					 final @NotNull ImageSet images,
					 final boolean forceReset) {
		this.mode = mode;
		this.images = images;
		this.forceReset = forceReset;
	}

	@Override
	@NotNull
	public State getState() {
		return State.VALIDATE;
	}

	@Override
	public int getPriority() {
		return PRIORITY_VALIDATE;
	}

	@Override
	public void start(@NotNull final TaskManager<Settings, State> performer) {
		final McuMgrLogger log = performer.getLog();

		final DefaultManager manager = new DefaultManager(performer.getTransport());
		manager.setLogger(log.getSink());

		// Starting from NCS 2.5 different bootloader modes allow sending the image in
		// slightly different ways. For that, we need to read bootloader info.
		// If that command is not supported, we assume the old, normal way of sending.
		manager.bootloaderInfo(DefaultManager.BOOTLOADER_INFO_QUERY_BOOTLOADER, new McuMgrCallback<>() {
			@Override
			public void onResponse(@NotNull final McuMgrBootloaderInfoResponse response) {
				log.info("Bootloader name: {}", response.bootloader);

				if ("MCUboot".equals(response.bootloader)) {
					manager.bootloaderInfo(DefaultManager.BOOTLOADER_INFO_MCUBOOT_QUERY_MODE, new McuMgrCallback<>() {
						@Override
						public void onResponse(@NotNull McuMgrBootloaderInfoResponse response) {
							log.info("Bootloader is in mode: {}, no downgrade: {}", parseMode(response.mode), response.noDowngrade);
							validate(performer,
									response.mode ==  McuMgrBootloaderInfoResponse.MODE_DIRECT_XIP ||
									response.mode == McuMgrBootloaderInfoResponse.MODE_DIRECT_XIP_WITH_REVERT ||
									response.mode == McuMgrBootloaderInfoResponse.MODE_FIRMWARE_LOADER,
									response.mode != McuMgrBootloaderInfoResponse.MODE_DIRECT_XIP &&
									response.mode != McuMgrBootloaderInfoResponse.MODE_FIRMWARE_LOADER,
									response.mode == McuMgrBootloaderInfoResponse.MODE_FIRMWARE_LOADER);
						}

						@Override
						public void onError(@NotNull McuMgrException error) {
							// Pretend nothing happened.
							validate(performer, false, true, false);
						}
					});
				} else {
					// It's some unknown bootloader. Try sending the old way.
					validate(performer, false, true, false);
				}
			}

			@Override
			public void onError(@NotNull final McuMgrException error) {
				// Pretend nothing happened.
				validate(performer, false, true, false);
			}
		});
	}

	/**
	 * Validates the current firmware on the device and adds the required tasks to the queue.
	 *
	 * @param performer The task performer.
	 * @param noSwap Whether the bootloader is in Direct XIP mode and there will be no swapping.
	 * @param allowRevert Whether the bootloader requires confirming images.
	 * @param forcePrimarySlot Whether the image should be sent to the primary slot (0) despite
	 *                         the flags. This is a case for Firmware Loader, where the secondary
	 *                         slot is used for the firmware loader itself, and we always update
	 *                         the primary slot.
	 */
	private void validate(@NotNull final TaskManager<Settings, State> performer,
						  final boolean noSwap,
						  final boolean allowRevert,
						  final boolean forcePrimarySlot) {
		final McuMgrLogger log = performer.getLog();

		final Settings settings = performer.getSettings();
		final ImageManager manager = new ImageManager(performer.getTransport());
		manager.setLogger(log.getSink());

		manager.list(new McuMgrCallback<>() {
			@Override
			public void onResponse(@NotNull final McuMgrImageStateResponse response) {
				log.trace("Validation response: {}", response);

				// Check for an error return code.
				if (!response.isSuccess()) {
					performer.onTaskFailed(Validate.this, new McuMgrErrorException(response.getReturnCode()));
					return;
				}

				// Initial validation.
				McuMgrImageStateResponse.ImageSlot[] slots = response.images;
				if (slots == null) {
					log.error("Missing images information: {}", response);
					performer.onTaskFailed(Validate.this, new McuMgrException("Missing images information"));
					return;
				}

				// For each core (image index) there may be one or two images given.
				// One, if the image will be placed in the secondary slot and swapped on reboot,
				// or two, if the MCUboot is in Direct XIP mode (with or without revert) and each
				// image targets its own slot. Depending on the active slot, the image will be
				// sent to the other one.
				// However, it may happen, that the firmware that the user is trying to send is
				// already running, that is the hash of the active slot is equal to the hash of
				// one of the images. In that case, we need to remove images for this image index,
				// as that core is already up-to-date.
				if (images.getImages().size() > 1) {
					// Iterate over all slots looking for active ones.
					for (final McuMgrImageStateResponse.ImageSlot slot : slots) {
						if (slot.active) {
							// Check if any of the images has the same hash as the image on the active slot.
							for (final TargetImage image : images.getImages()) {
								final ImageWithHash mcuMgrImage = image.image;
								if (slot.image == image.imageIndex && Arrays.equals(slot.hash, mcuMgrImage.getHash())) {
									// The image was found on an active slot, which means that core
									// does not need to be updated.
									images.removeImagesWithImageIndex(image.imageIndex);
									// Note: This break is important, as we just modified list that
									//       we're iterating over.
									break;
								}
							}
						}
					}
				}

				// The following code adds Erase, Upload, Test, Reset and Confirm operations
				// to the task priority queue. The priorities of those tasks ensure they are executed
				// in the right (given 2 lines above) order.

				// The flag indicates whether a reset operation should be performed during the process.
				boolean resetRequired = forceReset;

				// For each image that is to be sent, check if the same image has already been sent.
				for (final TargetImage image : images.getImages()) {
					final int imageIndex = image.imageIndex;
					final ImageWithHash mcuMgrImage = image.image;

					// The following flags will be updated based on the received slot information.
					boolean found = false;     // An image with the same hash was found on the device
					boolean skip = false;      // When this flag is set the image will not be uploaded
					boolean pending = false;   // TEST command was sent
					boolean permanent = false; // CONFIRM command was sent
					boolean confirmed = false; // Image has booted and confirmed itself
					boolean active = false;    // Image is currently running
					for (final McuMgrImageStateResponse.ImageSlot slot : slots) {
						// Skip slots of a different core than the image is for.
						if (slot.image != imageIndex)
							continue;

						// If the same image was found in any of the slots, the upload will not be
						// required. The image may need testing or confirming, or may already be running.
						if (Arrays.equals(slot.hash, mcuMgrImage.getHash())) {
							found = true;
							pending = slot.pending;
							permanent = slot.permanent;
							confirmed = slot.confirmed;
							active = slot.active;

							// If the image has been found on its target slot, and it's confirmed,
							// we just need to restart the device in order for it to be swapped back to
							// primary slot.
							if (mcuMgrImage.needsConfirmation() && confirmed && slot.slot == image.slot && !noSwap) {
								resetRequired = true;
							}
							break;
						} else {
							// In the Firmware Loader mode, the secondary slot is used for the
							// firmware loader itself. Updating the app or the loader is done
							// using the primary slot only.
							if (forcePrimarySlot) {
								slot.slot = TargetImage.SLOT_PRIMARY;
							}

							// The `image.slot` determines to which slot the image will be uploaded.
							// If the target slot of the image matches the slot number it means that
							// a Direct XIP mode is used. In that case the firmware comes in two
							// versions, one for each slot. We need to determine which one to send.
							if (slot.slot == image.slot) {
								// There are 5 cases the slots can be in:
								//
								// In "Swap" mode slots A and B are primary and secondary slots.
								// In "Direct XIP" mode, slot A is the confirmed one, and slot B is the
								// other one, but any of them can be primary.
								//
								// ----------------------------|--------|-----------|-----------------------|---------------------|
								//                    | Normal | Tested | Confirmed | Test Mode Unconfirmed | Test Mode Confirmed |
								// ----------------------------|--------|-----------|-----------------------|---------------------|
								// Slot A | active    |   *    |   *    |    *      |                       |                     |
								//        | confirmed |   *    |   *    |    *      |           *           |         *           |
								//        | pending   |        |        |           |                       |                     |
								//        | permanent |        |        |           |                       |                     |
								// ----------------------------|--------|-----------|-----------------------|---------------------|
								// Slot B | active    |        |        |           |           *           |         *           |
								//        | confirmed |        |        |           |                       |                     |
								//		  | pending   |        |   *    |    *      |                       |         *           |
								//		  | permanent |        |        |    *      |                       |         *           |
								// ----------------------------|--------|-----------|-----------------------|---------------------|
								//
								// Update can only be made in the Normal state, where the "other" slot is
								// empty or has "pending" flag clear (the existing firmware will get
								// erased automatically if needed).
								// Sending Reset command will have the following effect:
								// - Confirmed             -> Normal
								// - Tested                -> Test Mode Unconfirmed
								// - Test Mode Unconfirmed -> Normal
								// - Test Mode Confirmed   -> Normal

								// If the slot is pending or the device is in test mode (one slot
								// is confirmed and the other active), we need to reset
								// the device before uploading the image. Both slots in that case
								// are in use and cannot be erased.
								//
								// Reset will cause MCUboot to boot the other slot and change flags,
								// so we need to Validate again.
								// Note:
								//    It may happen that initial reset will be done two times.
								//    If the image on the secondary slot has been marked as pending
								//    (using Test command), the reset will switch the device to Test
								//    mode. In that case, the image on the primary slot will be marked
								//    as confirmed, and the one on the secondary slot as active.
								//    Again, neither can be erased. Second reset will switch the device
								//    back to the primary slot, and the secondary image will be erased
								//    automatically.
								// Note 2:
								//    In the Firmware Loader mode the primary slot is always erasable
								//    even with the confirmed flag is set.
								if (!forcePrimarySlot && slot.pending || slot.confirmed != slot.active) {
									// Both slots are in use, we need to reset the device.
									performer.enqueue(new ResetBeforeUpload(noSwap));
									// And schedule the validation again.
									performer.enqueue(new Validate(mode, images));
									performer.onTaskCompleted(Validate.this);
									return;
								}
								// If the image on the target slot is confirmed we cannot send
								// there anything, so we skip this image. It will not be uploaded.
								// This can happen on the primary slot or, when Direct XIP feature
								// is enabled, also on the secondary slot.
								if (slot.confirmed) {
									skip = true;
								}
							}
						}
					}
					if (skip) {
						continue;
					}
					if (!found) {
						performer.enqueue(new Upload(mcuMgrImage.getData(), imageIndex));
						if (mcuMgrImage.needsConfirmation() && (!allowRevert || mode == Mode.NONE)) {
							resetRequired = true;
						}
					}
					if (!mcuMgrImage.needsConfirmation()) {
						// Since nRF Connect SDK v.2.8 the SUIT image requires no confirmation.
						if (mcuMgrImage instanceof SUITImage) {
							performer.enqueue(new Confirm());
						}
						continue;
					}
					if (allowRevert && mode != Mode.NONE) {
						switch (mode) {
							case TEST_AND_CONFIRM: {
								// If the image is not pending (test command has not been sent) and not
								// confirmed (another image is under test), and isn't the currently
								// running image, send test command and update the flag.
								if (!pending && !confirmed && !active) {
									performer.enqueue(new Test(imageIndex, mcuMgrImage.getHash()));
									pending = true;
								}
								// If the image is pending, reset is required.
								if (pending) {
									resetRequired = true;
								}
								if (!permanent && !confirmed) {
									performer.enqueue(new ConfirmAfterReset(imageIndex, mcuMgrImage.getHash()));
								}
								break;
							}
							case TEST_ONLY: {
								// If the image is not pending (test command has not been sent) and not
								// confirmed (another image is under test), and isn't the currently
								// running image, send test command and update the flag.
								if (!pending && !confirmed && !active) {
									performer.enqueue(new Test(imageIndex, mcuMgrImage.getHash()));
									pending = true;
								}
								// If the image is pending, reset is required.
								if (pending) {
									resetRequired = true;
								}
								break;
							}
							case CONFIRM_ONLY: {
								// If the firmware is not confirmed yet, confirm t.
								if (!permanent && !confirmed) {
									performer.enqueue(new Confirm(imageIndex, mcuMgrImage.getHash()));
									permanent = true;
								}
								if (permanent) {
									resetRequired = true;
								}
								break;
							}
						}
					}
				}

				// Enqueue uploading all cache images.
				final List<CacheImage> cacheImages = images.getCacheImages();
				if (cacheImages != null) {
					for (final CacheImage cacheImage : cacheImages) {
						performer.enqueue(new Upload(cacheImage.image, cacheImage.partitionId));
					}
				}

				// To make sure the reset command are added just once, they're added based on flags.
				if (resetRequired) {
					if (settings.eraseAppSettings)
						performer.enqueue(new EraseStorage());
					performer.enqueue(new Reset(noSwap));
				}

				performer.onTaskCompleted(Validate.this);
			}

			@Override
			public void onError(@NotNull final McuMgrException e) {
				// In case of a Firmware Loader mode, the application returns NOT_SUPPORTED
				// for Image List command. A reset to bootloader modes is required.
				// For now, before the automatic reset is implemented, we just
				// notify the user that the reset is required.
				// Use DefaultManager.reset(BOOT_MODE_TYPE_BOOTLOADER, false, callback).
				if (forcePrimarySlot &&
					e instanceof McuMgrErrorException &&
					((McuMgrErrorException) e).getCode() == McuMgrErrorCode.NOT_SUPPORTED) {
					final int randomNumber = (int) (Math.random() * 99999);
					final String advName = String.format(Locale.US, "FL_%05d", randomNumber);
					performer.enqueue(new ResetBeforeUpload(advName));
					performer.enqueue(new Scan(advName));
					performer.enqueue(new Validate(mode, images, true));
					performer.onTaskCompleted(Validate.this);
				} else {
					performer.onTaskFailed(Validate.this, e);
				}
			}
		});
	}

	private String parseMode(final int mode) {
		switch (mode) {
			case 0: return "Single App";
			case 1: return "Swap Scratch";
			case 2: return "Overwrite-only";
			case 3: return "Swap Without Scratch";
			case 4: return "Direct XIP Without Revert";
			case 5: return "Direct XIP With Revert";
			case 6: return "RAM Loader";
			case 7: return "Firmware Loader";
			default: return "Unknown (" + mode + ")";
		}
	}
}
