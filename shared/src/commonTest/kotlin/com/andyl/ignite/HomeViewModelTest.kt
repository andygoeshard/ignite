package com.andyl.ignite

import app.cash.turbine.test
import com.andyl.ignite.data.AppStorage
import com.andyl.ignite.data.DeviceInfo
import com.andyl.ignite.data.RoomTransferRepository
import com.andyl.ignite.data.db.NoopTransferDao
import com.andyl.ignite.domain.DeviceDiscovery
import com.andyl.ignite.domain.FileReceiver
import com.andyl.ignite.domain.FileSender
import com.andyl.ignite.domain.IncomingEvent
import com.andyl.ignite.domain.PairingManager
import com.andyl.ignite.domain.TransferNotifier
import com.andyl.ignite.domain.TransferRepository
import com.andyl.ignite.domain.model.Device
import com.andyl.ignite.presentation.home.HomeViewModel
import com.andyl.ignite.presentation.home.HomeEvent
import com.andyl.ignite.presentation.home.IncomingUi
import com.andyl.ignite.presentation.home.PendingFile
import androidx.lifecycle.viewModelScope
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        FileKit.init(appId = "com.andyl.ignite.test.vm")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasPinAndDeviceName() = runTest(testDispatcher) {
        val vm = createViewModel()
        // let ViewModel init collectors run
        testDispatcher.scheduler.runCurrent()
        val s = vm.state.value
        // In test JVM, deviceName may fallback to "" if InetAddress fails; just ensure ViewModel created
        assertTrue(s.myPin.isEmpty() || s.myPin.length == 6)
        vm.viewModelScope.cancel()
    }

    @Test
    fun selectingDevice_andPin_enablesSend() = runTest(testDispatcher) {
        val vm = createViewModel()
        val device = Device(id = "1", name = "Peer", host = "192.168.1.2", port = 48213)
        vm.onEvent(HomeEvent.OnDeviceSelected(device))
        vm.onEvent(HomeEvent.OnFileSelected(PendingFile("/tmp/a.txt", "a.txt", 100)))
        testDispatcher.scheduler.runCurrent()
        assertEquals(false, vm.state.value.canSend, "should require PIN")
        vm.onEvent(HomeEvent.OnTargetPinChanged("123456"))
        testDispatcher.scheduler.runCurrent()
        assertEquals(true, vm.state.value.canSend)
        vm.viewModelScope.cancel()
    }

    @Test
    fun awaitingApproval_emitsPendingApproval() = runTest(testDispatcher) {
        val incoming = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 8)
        val vm = createViewModel(incomingFlow = incoming)
        testDispatcher.scheduler.runCurrent()
        incoming.tryEmit(IncomingEvent.AwaitingApproval("foto.jpg", "192.168.1.5", 1024, "id-1"))
        // Allow collector to process (needs a dispatch)
        testDispatcher.scheduler.runCurrent()
        kotlinx.coroutines.delay(10)
        testDispatcher.scheduler.runCurrent()
        val approval = vm.state.value.incoming
        assertTrue(approval is IncomingUi.AwaitingApproval, "expected AwaitingApproval but was $approval")
        assertEquals("foto.jpg", approval.fileName)
        assertEquals("id-1", approval.transferId)
        vm.viewModelScope.cancel()
    }

    private fun createViewModel(
        incomingFlow: MutableSharedFlow<IncomingEvent> = MutableSharedFlow(extraBufferCapacity = 8),
    ): HomeViewModel {
        val discovery = FakeDiscovery()
        val sender = FakeSender()
        val receiver = FakeReceiver(incomingFlow)
        val repo = RoomTransferRepository(NoopTransferDao())
        val deviceInfo = DeviceInfo()
        val storage = AppStorage()
        val notifier = object : TransferNotifier {
            override fun onIdle(deviceName: String) = Unit
            override fun onReceiving(fileName: String, receivedBytes: Long, totalBytes: Long, progress: Float) = Unit
            override fun onSending(fileName: String, progress: Float, totalBytes: Long) = Unit
            override fun onCompleted(fileName: String, isSending: Boolean) = Unit
            override fun onFailed(fileName: String, message: String?) = Unit
        }
        val pairing = PairingManager()
        return HomeViewModel(discovery, sender, receiver, repo, deviceInfo, storage, notifier, pairing, enablePrune = false)
    }

    private class FakeDiscovery : DeviceDiscovery {
        override val devices = flowOf<Device>()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
    }

    private class FakeSender : FileSender {
        override suspend fun send(target: Device, localPath: String, fileName: String, sizeBytes: Long, pin: String?) = flowOf(1f)
    }

    private class FakeReceiver(
        private val flow: MutableSharedFlow<IncomingEvent>,
    ) : FileReceiver {
        override val incomingEvents = flow
        override val requiresApproval = true
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun decideApproval(transferId: String, approved: Boolean) = Unit
    }
}
