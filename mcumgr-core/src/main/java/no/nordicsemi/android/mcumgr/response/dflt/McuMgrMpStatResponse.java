/*
 * Copyright (c) Intellinium SAS, 2014-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.response.dflt;


import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unused")
public class McuMgrMpStatResponse extends McuMgrOsResponse {
    // For Mynewt see:
    // https://github.com/apache/mynewt-core/blob/master/kernel/os/include/os/os_mempool.h

    /**
     * Memory pool information. The keys of this map are the names of the memory pools.
     */
    @JsonProperty("mpools")
    public Map<String, MpStat> mpools = new HashMap<>();

    @JsonAnySetter
    public void addMpStat(String name, MpStat stat) {
        mpools.put(name, stat);
    }

    public Map<String, MpStat> getMpools() {
        return mpools;
    }

    @JsonCreator
    public McuMgrMpStatResponse() {}

    /**
     * Information describing a memory pool, used to return OS information
     * to the management layer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MpStat {
        public static int UNKNOWN = -1;

        /** Size of the memory blocks in the pool. */
        @JsonProperty("blksiz")
        public int blksiz = UNKNOWN;
        /** Number of memory blocks in the pool. */
        @JsonProperty("nblks")
        public int nblks;
        /** Number of free memory blocks. */
        @JsonProperty("nfree")
        public int nfree;
        /** Minimum number of free memory blocks ever. */
        @JsonProperty("min")
        public int min;

        @JsonCreator
        public MpStat() {}

        @NotNull
        @Override
        public String toString() {
            if (blksiz == UNKNOWN) {
                return "{ " +
                        "nblks=" + nblks +
                        ", nfree=" + nfree +
                        ", min=" + min +
                        " }";
            }
            return "{ " +
                    "blksiz=" + blksiz +
                    ", nblks=" + nblks +
                    ", nfree=" + nfree +
                    ", min=" + min +
                    " }";
        }
    }
}
