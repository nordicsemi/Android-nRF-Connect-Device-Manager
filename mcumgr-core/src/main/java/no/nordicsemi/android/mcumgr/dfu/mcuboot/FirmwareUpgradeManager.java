package no.nordicsemi.android.mcumgr.dfu.mcuboot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeCallback;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeController;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeSettings;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet;
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.TargetImage;
import no.nordicsemi.android.mcumgr.exception.McuMgrException;
import no.nordicsemi.android.mcumgr.log.Category;
import no.nordicsemi.android.mcumgr.log.McuMgrLogger;
import no.nordicsemi.kotlin.log.Log;

/**
 * Manages a McuManager firmware upgrade. Once initialized, <b>this object can only perform a single
 * firmware upgrade for a single device to completion</b>. In other words, the same
 * FirmwareUpgradeManager may start the upload, cancel the upload, and call start once again to
 * restart the firmware upgrade. However, once the upload is completed, the same
 * FirmwareUpgradeManager cannot not be used for subsequent firmware upgrades.
 * <p>
 * Like other MCU managers, FirmwareUpgradeManagers must be initialized with a
 * {@link McuMgrTransport} which defines the scheme and implements the transport methods for
 * communicating with the device. FirmwareUpgradeManagers additionally require a callback and
 * the image data to upload.
 * <p>
 * Once initialized, a firmware upgrade is started by calling {@link FirmwareUpgradeManager#start},
 * and can be paused, resumed, and canceled using {@link FirmwareUpgradeManager#pause},
 * {@link FirmwareUpgradeManager#resume}, and {@link FirmwareUpgradeManager#cancel}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class FirmwareUpgradeManager implements FirmwareUpgradeController {

    /**
     * The logger of this manager, reporting under {@link Category#DFU}.
     * <p>
     * Nothing is logged until a sink is assigned using {@link #setLogger(Log.Sink)}.
     */
    @NotNull
    private final McuMgrLogger LOG = new McuMgrLogger(Category.DFU);

    //******************************************************************
    // Firmware Upgrade State
    //******************************************************************

    public enum State {
        NONE, VALIDATE, UPLOAD, TEST, RESET, CONFIRM;

        public boolean isInProgress() {
            return this != NONE;
        }
    }

    //******************************************************************
    // Firmware Upgrade Mode
    //******************************************************************

    public enum Mode {
        /**
         * When this mode is set, the manager will send the test and reset commands to
         * the device after the upload is complete. The device will reboot and will run the new
         * image on its next boot. If the new image supports auto-confirm feature, it will try to
         * confirm itself and change state to permanent. If not, test image will run just once
         * and will be swapped again with the original image on the next boot.
         * <p>
         * Use this mode if you just want to test the image, when it can confirm itself.
         */
        TEST_ONLY,
        /**
         * When this flag is set, the manager will send confirm and reset commands immediately
         * after upload.
         * <p>
         * Use this mode if when the new image does not support both auto-confirm feature and
         * SMP service and could not be confirmed otherwise.
         */
        CONFIRM_ONLY,
        /**
         * When this flag is set, the manager will first send test followed by reset commands,
         * then it will reconnect to the new application and will send confirm command.
         * <p>
         * Use this mode when the new image supports SMP service and you want to test it
         * before confirming.
         */
        TEST_AND_CONFIRM,

        /**
         * When this flag is set, the manager will immediately send the reset command after
         * the upload is complete. The device will reboot and will run the new image on its next
         * boot.
         * <p>
         * This mode should be used with "Direct XIP without Revert" bootloader mode
         * or when the confirm or test modes are not supported.
         * <p>
         * If the device supports <a href="https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/zephyr/services/device_mgmt/smp_groups/smp_group_0.html#bootloader-information">Bootloader Information</a>
         * command, the manager will check if the device is in "Direct XIP without Revert" mode
         * and will use this mode automatically.
         */
        NONE
    }

    //******************************************************************
    // Firmware Upgrade Mode
    //******************************************************************

    public static class Settings extends FirmwareUpgradeSettings {

        /**
         * Estimated time required for swapping images, in milliseconds.
         * If the mode is set to {@link Mode#TEST_AND_CONFIRM},
         * the manager will try to reconnect after this time. 0 by default.
         */
        public final int estimatedSwapTime;

        /**
         * Should the application settings be erased before applying
         * new firmware, default to false.
         */
        public final boolean eraseAppSettings;

        private Settings(final int estimatedSwapTime,
                         final int windowCapacity,
                         final int memoryAlignment,
                         final boolean eraseAppSettings) {
            super(windowCapacity, memoryAlignment);
            this.estimatedSwapTime = estimatedSwapTime;
            this.eraseAppSettings = eraseAppSettings;
        }

        public static class Builder extends FirmwareUpgradeSettings.Builder {
            private int estimatedSwapTime = 0;
            private boolean eraseAppSettings = false;

            public Builder() {
                super();
            }

            /**
             * Sets estimated time for the image swap. The device should reset after that time after
             * successful DFU operation.
             * <p>
             * If the device requires long time for the images to be swapped, and this is not set,
             * the reconnection may fail and DFU process may be reported as unsuccessful despite
             * it actually working correctly. This is important with
             * {@link FirmwareUpgradeManager.Mode#TEST_AND_CONFIRM} mode, which
             * reconnects to the device after it resets.
             * <p>
             * This value is ignored when the target device supports SUIT (Software Update for
             * Internet of Things).
             * </p>
             * @param time the swap time in milliseconds.
             * @return The builder.
             */
            public Builder setEstimatedSwapTime(final int time) {
                this.estimatedSwapTime = Math.max(0, time);
                return this;
            }

            /**
             * Should the application settings, including app data, bond information, etc,
             * be erase when the new image is applied.
             * <p>
             * Erasing application settings is useful when switching major version of the app,
             * which is not compatible with the old one, or when sending other non-compatible
             * firmware.
             * @param erase should app settings be eraser, or not, defaults to false.
             * @return The builder.
             */
            public Builder setEraseAppSettings(final boolean erase) {
                this.eraseAppSettings = erase;
                return this;
            }

            @Override
            public Builder setMemoryAlignment(int alignment) {
                super.setMemoryAlignment(alignment);
                return this;
            }

            @Override
            public Builder setWindowCapacity(int windowCapacity) {
                super.setWindowCapacity(windowCapacity);
                return this;
            }

            /**
             * Builds the settings object.
             * @return Settings.
             */
            @Override
            public Settings build() {
                return new Settings(estimatedSwapTime, windowCapacity, memoryAlignment, eraseAppSettings);
            }
        }
    }

    //******************************************************************
    // Properties
    //******************************************************************

    @NotNull
    private final FirmwareUpgradePerformer mPerformer;

    @NotNull
    private final McuMgrTransport mTransport;

    /**
     * Internal callback to route callbacks to the UI thread if the flag has been set.
     */
    private final FirmwareUpgradeCallback.Executor<State> mInternalCallback;

    /**
     * The manager mode. By default, the {@link Mode#TEST_AND_CONFIRM} mode is set.
     */
    private Mode mMode = Mode.TEST_AND_CONFIRM;

    //******************************************************************
    // Firmware Upgrade Manager API
    //******************************************************************

    /**
     * Construct a firmware upgrade manager. If using this constructor, the callback must be set
     * using {@link #setFirmwareUpgradeCallback(FirmwareUpgradeCallback)} before calling
     * {@link FirmwareUpgradeManager#start}.
     *
     * @param transport the transporter to use.
     */
    public FirmwareUpgradeManager(@NotNull final McuMgrTransport transport) {
        this(transport, null);
    }

    /**
     * Construct a firmware upgrade manager.
     *
     * @param transport the transporter to use.
     * @param callback  the callback.
     */
    public FirmwareUpgradeManager(@NotNull final McuMgrTransport transport,
                                  @Nullable final FirmwareUpgradeCallback<State> callback) {
        mTransport = transport;
        mInternalCallback = new FirmwareUpgradeCallback.Executor<>();
        mInternalCallback.setCallback(callback);
        mPerformer = new FirmwareUpgradePerformer(mInternalCallback, LOG);
    }

    /**
     * Sets the sink receiving the log entries emitted by the firmware upgrade.
     * <p>
     * The entries are reported under {@link Category#DFU}, except for those emitted by
     * the managers used internally. Set to null (the default) to stop logging.
     *
     * @param logger the sink to receive the log entries, or null.
     * @since 4.0
     */
    public void setLogger(@Nullable final Log.Sink<? super Category> logger) {
        LOG.setSink(logger);
    }

    /**
     * Get the transporter.
     *
     * @return Transporter for this new manager instance.
     */
    @NotNull
    public McuMgrTransport getTransporter() {
        return mTransport;
    }

    /**
     * Get the current {@link State} of the firmware upgrade.
     *
     * @return The current state.
     */
    public State getState() {
        return mPerformer.getState();
    }

    /**
     * If true, run all callbacks on the UI thread (default).
     *
     * @param uiThreadCallbacks true if all callbacks should run on the UI thread.
     */
    public void setCallbackOnUiThread(final boolean uiThreadCallbacks) {
        mInternalCallback.setRunOnIUThread(uiThreadCallbacks);
    }

    /**
     * Sets the manager callback.
     *
     * @param callback the callback for receiving status change events.
     */
    public void setFirmwareUpgradeCallback(@Nullable final FirmwareUpgradeCallback<State> callback) {
        mInternalCallback.setCallback(callback);
    }

    /**
     * Sets the manager mode. By default the {@link Mode#TEST_AND_CONFIRM} mode is used.
     * The mode may be set only before calling {@link #start(ImageSet, Settings)} method.
     * <p>
     * This value is ignored when the target device supports SUIT (Software Update for
     * Internet of Things).
     *
     * @param mode the manager mode.
     * @see Mode#TEST_ONLY
     * @see Mode#CONFIRM_ONLY
     * @see Mode#TEST_AND_CONFIRM
     * @see Mode#NONE
     */
    public void setMode(@NotNull final Mode mode) {
        if (mPerformer.isBusy()) {
            LOG.info("Firmware upgrade is already in progress");
            return;
        }
        mMode = mode;
    }

    /**
     * Start the upgrade.
     * <p>
     * The specified image file will be sent to the target using the
     * given transport, then depending on the mode, verified using "test" command or confirmed
     * using "confirm" command. If successful, the reset command will be sent. The device should
     * boot with the new firmware. The manager will try to connect to the SMP server on the new
     * firmware and confirm the new images if they were not confirmed before, or they didn't confirm
     * automatically.
     * <p>
     * This method should be used for SUIT files.
     */
    public synchronized void start(final byte @NotNull [] imageData,
                                   @NotNull final Settings settings) throws McuMgrException {
        start(new ImageSet().add(imageData), settings);
    }

    /**
     * Starts an upgrade with given image set. The targets define the image index and
     * slot to which the binary should be sent.
     * <p>
     * This method can be used for multi-core devices (each core is identified by
     * {@link TargetImage#imageIndex}) with and without Direct XIP feature.
     * <p>
     * Direct XIP is a feature added in NCS 2.5 allowing to boot a device from a non-primary slot.
     * For such devices the correct image has to be sent (compiled for that specific slot), depending
     * on the slot number with the active image. This feature removes the need for a swap after an
     * update, as the newly uploaded image is already in the correct slot.
     *
     * @param images   set of images. For direct XIP this set should contain images compiled
     *                 for both slots. The correct image will be chosen automatically.
     * @param settings settings applied to the upgrade process.
     * @since 1.8
     */
    public synchronized void start(@NotNull final ImageSet images,
                                   @NotNull final Settings settings) {
        if (mPerformer.isBusy()) {
            LOG.info("Firmware upgrade is already in progress");
            return;
        }

        // Start upgrade.
        mInternalCallback.onUpgradeStarted(this);
        mPerformer.start(mTransport, settings, images, mMode);
    }

    //******************************************************************
    // Upload Controller
    //******************************************************************

    @Override
    public synchronized void cancel() {
        mPerformer.cancel();
    }

    @Override
    public synchronized void pause() {
        mPerformer.pause();
    }

    @Override
    public synchronized void resume() {
        mPerformer.resume();
    }

    @Override
    public synchronized boolean isPaused() {
        return mPerformer.isPaused();
    }

    @Override
    public synchronized boolean isInProgress() {
        return mPerformer.isBusy() && !isPaused();
    }
}
