package no.nordicsemi.android.mcumgr.dfu.mcuboot.task

import no.nordicsemi.android.mcumgr.McuMgrErrorCode
import no.nordicsemi.android.mcumgr.McuMgrHeader
import no.nordicsemi.android.mcumgr.McuMgrScheme
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Mode
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.Settings
import no.nordicsemi.android.mcumgr.dfu.mcuboot.FirmwareUpgradeManager.State
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.ImageSet
import no.nordicsemi.android.mcumgr.dfu.mcuboot.model.TargetImage
import no.nordicsemi.android.mcumgr.exception.McuMgrErrorException
import no.nordicsemi.android.mcumgr.exception.McuMgrException
import no.nordicsemi.android.mcumgr.image.McuMgrImage
import no.nordicsemi.android.mcumgr.log.Category
import no.nordicsemi.android.mcumgr.log.McuMgrLogger
import no.nordicsemi.android.mcumgr.mock.McuMgrOperation
import no.nordicsemi.android.mcumgr.mock.MockBleMcuMgrTransport
import no.nordicsemi.android.mcumgr.mock.OverrideHandler
import no.nordicsemi.android.mcumgr.mock.buildMockResponse
import no.nordicsemi.android.mcumgr.mock.toResponse
import no.nordicsemi.android.mcumgr.response.McuMgrResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse
import no.nordicsemi.android.mcumgr.response.img.McuMgrImageStateResponse.ImageSlot
import no.nordicsemi.android.mcumgr.task.Task
import no.nordicsemi.android.mcumgr.task.TaskManager
import no.nordicsemi.android.mcumgr.task.TaskPerformer
import no.nordicsemi.android.mcumgr.util.CBOR
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VerifyAfterResetTest {
    private val oldImage = ByteArray(32) { 1 }
    private val newImage = ByteArray(32) { 2 }
    private val oldNetImage = ByteArray(32) { 3 }
    private val newNetImage = ByteArray(32) { 4 }

    /**
     * Emulates the image state command of a Zephyr device. A confirm marks the image to be
     * booted permanently, as Zephyr does by default also for an image that is not running.
     *
     * @param listError the return code of the response to the list request, or 0 for success.
     * @param listFailure thrown instead of answering the list request, like a lost connection.
     * @param listImages false to leave the images out of the responses.
     * @param onList called on the transport thread before the list request is answered.
     */
    private class ImageState(
        private vararg val slots: ImageSlot,
        private val listError: Int = 0,
        private val listFailure: McuMgrException? = null,
        private val listImages: Boolean = true,
        private val onList: () -> Unit = {},
    ) : OverrideHandler {
        override val groupId = 1 // Image group
        override val commandId = 0 // State command

        /** The hashes of all received confirm requests. */
        val confirmed = mutableListOf<ByteArray>()

        override fun <T : McuMgrResponse> handle(
            header: McuMgrHeader,
            payload: ByteArray,
            responseType: Class<T>
        ): T {
            val response = McuMgrImageStateResponse()
            if (header.op == McuMgrOperation.WRITE.value) {
                val hash = CBOR.getObject(payload, "hash", ByteArray::class.java)
                confirmed += hash
                slots.filter { it.hash.contentEquals(hash) }.forEach { it.permanent = true }
            } else {
                onList()
                listFailure?.let { throw it }
                response.rc = listError
            }
            if (listImages) response.images = arrayOf(*slots)
            return buildMockResponse(
                McuMgrScheme.BLE, header.toResponse(), CBOR.toBytes(response), responseType
            )
        }
    }

    /** Holds the response to the list request until [release] is called. */
    private class HeldList {
        private val requested = CountDownLatch(1)
        private val released = CountDownLatch(1)

        val onList: () -> Unit = {
            requested.countDown()
            released.await(5, TimeUnit.SECONDS)
        }

        fun awaitRequest() = assertTrue(requested.await(5, TimeUnit.SECONDS))
        fun release() = released.countDown()
    }

    /** The task runner of the firmware upgrade, reporting how the upgrade ended. */
    private class Runner : TaskPerformer<Settings, State>(McuMgrLogger(Category.DFU)) {
        /** Completes with null when the upgrade completes, or with the error when it fails. */
        val error = CompletableFuture<McuMgrException?>()
        val cancelled = CountDownLatch(1)
        /** Counts down when a confirmation becomes the current task, even if paused. */
        val confirmIsNext = CountDownLatch(1)

        override fun onTaskStarted(previousTask: Task<Settings, State>?, newTask: Task<Settings, State>) {
            if (newTask is ConfirmAfterReset) confirmIsNext.countDown()
        }
        override fun onCompleted(task: Task<Settings, State>) {
            error.complete(null)
        }
        override fun onCancelled(task: Task<Settings, State>) {
            cancelled.countDown()
        }
        override fun onTaskFailed(task: Task<Settings, State>, error: McuMgrException) {
            this.error.complete(error)
        }
    }

    /** Enqueues the given tasks, like Validate does. */
    private class Enqueue(private vararg val tasks: FirmwareUpgradeTask) : FirmwareUpgradeTask() {
        override fun getState() = State.VALIDATE
        override fun getPriority() = FirmwareUpgradeTask.PRIORITY_VALIDATE
        override fun start(performer: TaskManager<Settings, State>) {
            tasks.forEach { performer.enqueue(it) }
            performer.onTaskCompleted(this)
        }
    }

    /** Starts running the [tasks] against the [device]. */
    private fun upgrade(device: ImageState, vararg tasks: FirmwareUpgradeTask) = Runner().apply {
        start(
            MockBleMcuMgrTransport(handlerOverrides = listOf(device)),
            Settings.Builder().build(),
            Enqueue(*tasks),
        )
    }

    /** Runs the verification of the new image and returns its error, or null. */
    private fun verify(device: ImageState): McuMgrException? {
        val error = upgrade(device, VerifyAfterReset(0, newImage)).error.get(5, TimeUnit.SECONDS)
        assertTrue(device.confirmed.isEmpty(), "The verification must not confirm anything")
        return error
    }

    private fun slot(
        slot: Int,
        hash: ByteArray,
        active: Boolean,
        confirmed: Boolean = active,
        image: Int = 0,
    ) = ImageSlot().also {
        it.image = image
        it.slot = slot
        it.hash = hash
        it.bootable = true
        it.active = active
        it.confirmed = confirmed
    }

    /**
     * The slots of an image after a successful test boot: the new image runs, not confirmed yet,
     * and the previous one stays confirmed in the other slot. Zephyr's image list marks the
     * active slot of every image, so the same goes for image 1.
     */
    private fun testBoot(new: ByteArray, old: ByteArray, image: Int = 0) = arrayOf(
        slot(0, new, active = true, confirmed = false, image = image),
        slot(1, old, active = false, confirmed = true, image = image),
    )

    /** The slots of an image after MCUboot reverted the new one. */
    private fun reverted(new: ByteArray, old: ByteArray, image: Int = 0) = arrayOf(
        slot(0, old, active = true, image = image),
        slot(1, new, active = false, image = image),
    )

    private fun resource(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test
    fun `verification passes when the new image is running`() {
        assertNull(verify(ImageState(*testBoot(newImage, oldImage))))
    }

    @Test
    fun `verification passes when the new image runs from the secondary slot`() {
        // In Direct XIP mode, the new image runs from the slot it was uploaded to.
        val device = ImageState(
            slot(0, oldImage, active = false, confirmed = true),
            slot(1, newImage, active = true, confirmed = false),
        )

        assertNull(verify(device))
    }

    @Test
    fun `verification fails when the device reverted to the previous image`() {
        val error = verify(ImageState(*reverted(newImage, oldImage)))

        assertEquals("The new image is not active after the reset (image 0)", error?.message)
    }

    @Test
    fun `verification fails when the new image is not on the device`() {
        // In Direct XIP mode with revert, MCUboot may erase an image that failed to boot.
        val error = verify(ImageState(slot(0, oldImage, active = true)))

        assertEquals("The new image was not found after the reset (image 0)", error?.message)
    }

    @Test
    fun `verification fails when the device answers the list with an error`() {
        val error = verify(ImageState(*testBoot(newImage, oldImage), listError = 1))

        assertEquals(McuMgrErrorCode.UNKNOWN, assertIs<McuMgrErrorException>(error).code)
    }

    @Test
    fun `verification fails when the list request fails`() {
        val failure = McuMgrException("Disconnected")

        assertSame(failure, verify(ImageState(*testBoot(newImage, oldImage), listFailure = failure)))
    }

    @Test
    fun `verification fails when the response has no images`() {
        val error = verify(ImageState(*testBoot(newImage, oldImage), listImages = false))

        assertEquals("Missing images information", error?.message)
    }

    @Test
    fun `verification fails when the image is running as another image index`() {
        val device = ImageState(
            *reverted(newImage, oldImage, image = 0),
            slot(0, newImage, active = true, confirmed = false, image = 1),
        )

        assertEquals("The new image is not active after the reset (image 0)", verify(device)?.message)
    }

    @Test
    fun `nothing is confirmed when another image of the upgrade was reverted`() {
        // Image 1 was reverted on its own, so image 0 must not be confirmed either.
        val device = ImageState(
            *testBoot(newImage, oldImage, image = 0),
            *reverted(newNetImage, oldNetImage, image = 1),
        )

        val error = upgrade(
            device,
            VerifyAfterReset(0, newImage), VerifyAfterReset(1, newNetImage),
            ConfirmAfterReset(0, newImage), ConfirmAfterReset(1, newNetImage),
        ).error.get(5, TimeUnit.SECONDS)

        assertNotNull(error)
        assertTrue(error.message!!.contains("image 1"), "The error tells which image is not active")
        assertTrue(device.confirmed.isEmpty())
    }

    @Test
    fun `new images are confirmed after the verification`() {
        val device = ImageState(
            *testBoot(newImage, oldImage, image = 0),
            *testBoot(newNetImage, oldNetImage, image = 1),
        )

        val error = upgrade(
            device,
            VerifyAfterReset(0, newImage), VerifyAfterReset(1, newNetImage),
            ConfirmAfterReset(0, newImage), ConfirmAfterReset(1, newNetImage),
        ).error.get(5, TimeUnit.SECONDS)

        assertNull(error)
        assertEquals(2, device.confirmed.size)
        assertContentEquals(newImage, device.confirmed[0])
        assertContentEquals(newNetImage, device.confirmed[1])
    }

    @Test
    fun `nothing is confirmed when the upgrade is cancelled during the verification`() {
        val list = HeldList()
        val device = ImageState(*testBoot(newImage, oldImage), onList = list.onList)

        val runner = upgrade(device, VerifyAfterReset(0, newImage), ConfirmAfterReset(0, newImage))
        list.awaitRequest()
        runner.cancel()
        list.release()

        assertTrue(runner.cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(device.confirmed.isEmpty(), "A cancelled upgrade must not confirm the image")
    }

    @Test
    fun `nothing is confirmed while the upgrade is paused after the verification`() {
        val list = HeldList()
        val device = ImageState(*testBoot(newImage, oldImage), onList = list.onList)

        val runner = upgrade(device, VerifyAfterReset(0, newImage), ConfirmAfterReset(0, newImage))
        list.awaitRequest()
        runner.pause()
        list.release()

        // The verification completes, and the confirmation waits for the upgrade to resume.
        // It is not resumed here, as the runner may start the confirmation twice if resume()
        // comes before it checks the pause.
        assertTrue(runner.confirmIsNext.await(5, TimeUnit.SECONDS))
        assertTrue(device.confirmed.isEmpty(), "A paused upgrade must not confirm the image")
    }

    @Test
    fun `test and confirm plans the verification between the reset and the confirmation`() {
        val image = resource("slinky-no-prot-tlv.img")
        // The device runs the previous image, the new one is not on it yet.
        val device = ImageState(slot(0, oldImage, active = true))
        val planner = Planner(MockBleMcuMgrTransport(handlerOverrides = listOf(device)))

        Validate(Mode.TEST_AND_CONFIRM, ImageSet().add(image)).start(planner)

        assertNull(planner.error.get(5, TimeUnit.SECONDS))
        val planned = planner.inOrder()
        val reset = planned.indexOfFirst { it is Reset }
        val verify = planned.indexOfFirst { it is VerifyAfterReset }
        val confirm = planned.indexOfFirst { it is ConfirmAfterReset }
        val names = planned.map { it::class.simpleName }
        assertTrue(reset >= 0 && verify > reset && confirm > verify, "Planned: $names")

        // The planned verification checks the uploaded image.
        val newImageRunning = ImageState(*testBoot(McuMgrImage.getHash(image), oldImage))
        val runner = upgrade(newImageRunning, planned[verify] as VerifyAfterReset)
        assertNull(runner.error.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `test and confirm verifies every image before the first confirmation`() {
        val app = resource("slinky-no-prot-tlv.img")
        val net = resource("slinky-prot-tlv.img")
        // The device runs the previous images, the new ones are not on it yet.
        val device = ImageState(
            slot(0, oldImage, active = true),
            slot(0, oldNetImage, active = true, image = 1),
        )
        val planner = Planner(MockBleMcuMgrTransport(handlerOverrides = listOf(device)))
        val images = ImageSet().add(TargetImage(0, app)).add(TargetImage(1, net))

        Validate(Mode.TEST_AND_CONFIRM, images).start(planner)

        assertNull(planner.error.get(5, TimeUnit.SECONDS))
        val planned = planner.inOrder()
        val names = planned.map { it::class.simpleName }
        val verifications = planned.filterIsInstance<VerifyAfterReset>()
        assertEquals(2, verifications.size, "Planned: $names")
        val reset = planned.indexOfFirst { it is Reset }
        val firstVerify = planned.indexOfFirst { it is VerifyAfterReset }
        val lastVerify = planned.indexOfLast { it is VerifyAfterReset }
        val firstConfirm = planned.indexOfFirst { it is ConfirmAfterReset }
        assertTrue(reset >= 0 && firstVerify > reset && firstConfirm > lastVerify, "Planned: $names")

        // The planned verifications check the uploaded images.
        val newImagesRunning = ImageState(
            *testBoot(McuMgrImage.getHash(app), oldImage, image = 0),
            *testBoot(McuMgrImage.getHash(net), oldNetImage, image = 1),
        )
        assertNull(upgrade(newImagesRunning, *verifications.toTypedArray()).error.get(5, TimeUnit.SECONDS))
        val netReverted = ImageState(
            *testBoot(McuMgrImage.getHash(app), oldImage, image = 0),
            *reverted(McuMgrImage.getHash(net), oldNetImage, image = 1),
        )
        val error = upgrade(netReverted, *verifications.toTypedArray()).error.get(5, TimeUnit.SECONDS)
        assertEquals("The new image is not active after the reset (image 1)", error?.message)
    }

    /** Records the tasks that a task enqueues, without running them. */
    private class Planner(private val transport: McuMgrTransport) : TaskManager<Settings, State> {
        private val tasks = PriorityQueue<Task<Settings, State>>()
        /** Completes with null when the task completes, or with the error when it fails. */
        val error = CompletableFuture<McuMgrException?>()

        /** The recorded tasks, in the order the task runner would run them. */
        fun inOrder() = generateSequence { tasks.poll() }.toList()

        override fun getLog() = McuMgrLogger(Category.DFU)
        override fun getTransport() = transport
        override fun getSettings(): Settings = Settings.Builder().build()
        override fun enqueue(task: Task<Settings, State>) {
            tasks.add(task)
        }
        override fun onTaskProgressChanged(
            task: Task<Settings, State>, current: Int, total: Int, timestamp: Long
        ) {}
        override fun onTaskCompleted(task: Task<Settings, State>) {
            error.complete(null)
        }
        override fun onTaskFailed(task: Task<Settings, State>, error: McuMgrException) {
            this.error.complete(error)
        }
    }
}
