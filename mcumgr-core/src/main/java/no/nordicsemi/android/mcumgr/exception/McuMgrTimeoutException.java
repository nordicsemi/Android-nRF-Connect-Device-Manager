/*
 * Copyright (c) 2017-2018 Runtime Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package no.nordicsemi.android.mcumgr.exception;

/**
 * Exception thrown when the response hasn't been received until
 * time run out.
 */
public class McuMgrTimeoutException extends McuMgrException {

	public McuMgrTimeoutException() {
		super("Timeout");
	}

	public McuMgrTimeoutException(Throwable cause) {
		super("Timeout", cause);
	}
}
