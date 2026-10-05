package no.nordicsemi.android.mcumgr.dfu.suit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

import no.nordicsemi.android.mcumgr.McuMgrTransport;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeCallback;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeController;
import no.nordicsemi.android.mcumgr.dfu.FirmwareUpgradeSettings;
import no.nordicsemi.android.mcumgr.dfu.suit.model.CacheImageSet;
import no.nordicsemi.android.mcumgr.log.Category;
import no.nordicsemi.android.mcumgr.log.McuMgrLogger;
import no.nordicsemi.kotlin.log.Log;

/** @noinspection unused*/
public class SUITUpgradeManager implements FirmwareUpgradeController {

    /**
     * The logger of this manager, reporting under {@link Category#DFU}.
     * <p>
     * Nothing is logged until a sink is assigned using {@link #setLogger(Log.Sink)}.
     */
    @NotNull
    private final McuMgrLogger LOG = new McuMgrLogger(Category.DFU);

    //******************************************************************
    // SUIT Upgrade State
    //******************************************************************
    public enum State {
        NONE,
        /** The SUIT manifest is being sent to the target device. */
        UPLOADING_ENVELOPE,
        /** The target device is processing the SUIT manifest. */
        PROCESSING,
        /** Requested resource is being uploaded to the target device. */
        UPLOADING_RESOURCE
    }

    //******************************************************************
    // Properties
    //******************************************************************

    @NotNull
    private final SUITUpgradePerformer mPerformer;

    @NotNull
    private final McuMgrTransport mTransport;

    /**
     * Internal callback to route callbacks to the UI thread if the flag has been set.
     */
    private final FirmwareUpgradeCallback.Executor<State> mInternalCallback;

    private SUITUpgradeManager.OnResourceRequiredCallback mResourceCallback;

    //******************************************************************
    // Response Callbacks
    //******************************************************************

    public interface ResourceCallback {
        void provide(byte @NotNull [] data);
        void error(@NotNull Exception e);
    }

    public interface OnResourceRequiredCallback {
        void onResourceRequired(@NotNull final URI uri, @NotNull ResourceCallback callback);
        void onUploadCancelled();
    }

    //******************************************************************
    // Firmware Upgrade Manager API
    //******************************************************************

    /**
     * Construct a firmware upgrade manager. If using this constructor, the callback must be set
     * using {@link #setFirmwareUpgradeCallback(FirmwareUpgradeCallback)} before calling
     * {@link #start}.
     *
     * @param transport the transporter to use.
     */
    public SUITUpgradeManager(@NotNull final McuMgrTransport transport) {
        this(transport, null, null);
    }

    /**
     * Construct a firmware upgrade manager.
     *
     * @param transport the transporter to use.
     * @param callback  the callback.
     */
    public SUITUpgradeManager(@NotNull final McuMgrTransport transport,
                              @Nullable final FirmwareUpgradeCallback<State> callback,
                              @Nullable final OnResourceRequiredCallback resourceCallback) {
        mTransport = transport;
        mInternalCallback = new FirmwareUpgradeCallback.Executor<>();
        mInternalCallback.setCallback(callback);
        mResourceCallback = resourceCallback;
        mPerformer = new SUITUpgradePerformer(mInternalCallback, LOG);
    }

    /**
     * Sets the sink receiving the log entries emitted by the SUIT firmware upgrade.
     * <p>
     * The entries are reported under {@link Category#DFU}, except for those emitted by
     * the managers used internally to talk to the device, which report under
     * {@link Category#DFU}. Set to null (the default) to stop logging.
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
     * Set the resource callback used to provide resources requested by the DFU target.
     *
     * @param resourceCallback the callback for providing resources.
     */
    public void setResourceCallback(@Nullable final OnResourceRequiredCallback resourceCallback) {
        this.mResourceCallback = resourceCallback;
    }

    /**
     * Start the upgrade.
     * <p>
     * This method should be used for SUIT candidate envelopes files.
     *
     * @param settings the firmware upgrade settings.
     * @param envelope the SUIT candidate envelope.
     */
    public synchronized void start(@NotNull final FirmwareUpgradeSettings settings,
                                   final byte @NotNull [] envelope) {
        start(settings, envelope, null);
    }

    /**
     * Start the upgrade.
     * <p>
     * This method should be used for SUIT candidate envelopes files.
     *
     * @param settings the firmware upgrade settings.
     * @param envelope the SUIT candidate envelope.
     * @param cacheImages cache images to be uploaded together with the SUIT envelope before
     *                    starting the update.
     */
    public synchronized void start(@NotNull final FirmwareUpgradeSettings settings,
                                   final byte @NotNull [] envelope,
                                   @Nullable final CacheImageSet cacheImages) {
        if (mPerformer.isBusy()) {
            LOG.info("Firmware upgrade is already in progress");
            return;
        }

        // Start upgrade.
        mInternalCallback.onUpgradeStarted(this);
        final SUITUpgradePerformer.Settings performerSettings =
                new SUITUpgradePerformer.Settings(settings, mResourceCallback);

        mPerformer.start(mTransport, performerSettings, envelope, cacheImages);
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
