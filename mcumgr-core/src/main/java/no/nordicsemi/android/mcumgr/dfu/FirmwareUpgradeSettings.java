package no.nordicsemi.android.mcumgr.dfu;

public class FirmwareUpgradeSettings {

    /**
     * The upload window capacity for faster image uploads. A capacity greater than 1 will enable
     * using the faster window upload implementation.
     */
    public final int windowCapacity;

    /**
     * Memory alignment. Value 1 disables memory alignment.
     */
    public final int memoryAlignment;

    protected FirmwareUpgradeSettings(final int windowCapacity,
                                      final int memoryAlignment) {
        this.windowCapacity = windowCapacity;
        this.memoryAlignment = memoryAlignment;
    }

    public static class Builder {
        protected int windowCapacity = 1;
        protected int memoryAlignment = 1;

        public Builder() {}

        /**
         * Sets window capacity.
         * <p>
         * On Zephyr this is equal to MCUMGR_TRANSPORT_NETBUF_COUNT - 1 value, where
         * one buffer (if more than 1) is used for responses.
         * @param windowCapacity number of windows that can be sent in parallel.
         * @see <a href="https://github.com/zephyrproject-rtos/zephyr/blob/19f645edd40b38e54f505135beced1919fdc7715/subsys/mgmt/mcumgr/transport/Kconfig#L32">MCUMGR_TRANSPORT_NETBUF_COUNT</a>
         * @return The builder.
         */
        public FirmwareUpgradeSettings.Builder setWindowCapacity(final int windowCapacity) {
            this.windowCapacity = Math.max(1, windowCapacity);
            return this;
        }

        /**
         * The memory alignment value should match the device's memory layout.
         * Some devices require the chunks to be word or 16-byte aligned to be saved.
         * <p>
         * Value 1 disables alignment and chunks will be sent as big as possible.
         * @param alignment device memory alignment.
         * @return The builder.
         */
        public FirmwareUpgradeSettings.Builder setMemoryAlignment(final int alignment) {
            this.memoryAlignment = Math.max(1, alignment);
            return this;
        }

        /**
         * Builds the settings object.
         * @return Settings.
         */
        public FirmwareUpgradeSettings build() {
            return new FirmwareUpgradeSettings(windowCapacity, memoryAlignment);
        }
    }
}
