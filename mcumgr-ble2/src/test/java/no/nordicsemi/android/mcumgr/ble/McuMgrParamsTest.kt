package no.nordicsemi.android.mcumgr.ble

import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.dflt.McuMgrParamsResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McuMgrParamsTest {

    private fun parse(hex: String): McuMgrParamsResponse = McuMgrResponse.buildResponse(
        McuMgrScheme.BLE,
        hex.hexToByteArray(),
        McuMgrParamsResponse::class.java
    )

    @Test
    fun `buffer size is read from a device supporting the params command`() {
        // {"buf_size": 1230, "buf_count": 4}, as received from a Zephyr device.
        val response = parse("010000190000ff06bf686275665f73697a651904ce696275665f636f756e7404ff")
        assertEquals(1230, response.reassemblyBufferSize)
    }

    @Test
    fun `no buffer size from a device not supporting the params command`() {
        // {"rc": 8}, as received from a Zephyr device with CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS=n.
        val response = parse("010000060000ff06bf62726308ff")
        assertNull(response.reassemblyBufferSize)
    }

    @Test
    fun `no buffer size from a device reporting a group error`() {
        // {"err": {"group": 0, "rc": 8}}, an error in the format of SMP version 2.
        val response = parse("090000130000ff06bf63657272bf6567726f75700062726308ffff")
        assertNull(response.reassemblyBufferSize)
    }

    @Test
    fun `no buffer size from an error response with the buffer details`() {
        // {"buf_size": 1230, "buf_count": 4, "rc": 1}, an error despite the buffer details.
        val response = parse("0100001d0000ff06bf686275665f73697a651904ce696275665f636f756e740462726301ff")
        assertNull(response.reassemblyBufferSize)
    }

    @Test
    fun `no buffer size from a success response without the buffer details`() {
        // {}, a success response without the buffer details.
        val response = parse("010000010000ff06a0")
        assertNull(response.reassemblyBufferSize)
    }
}
