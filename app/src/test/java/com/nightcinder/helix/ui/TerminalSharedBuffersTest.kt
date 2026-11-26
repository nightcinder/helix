package com.nightcinder.helix.ui

import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.terminal.TerminalBufferRegistry
import com.nightcinder.helix.ui.state.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSharedBuffersTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var transport: FakeTerminalTransport
    private lateinit var repo: FakeWorkspaceTreeRepository
    private lateinit var registry: TerminalBufferRegistry

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        transport = FakeTerminalTransport()
        repo = FakeWorkspaceTreeRepository()
        registry = TerminalBufferRegistry(CoroutineScope(dispatcher))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tab(id: String) = TerminalTabState(
        id = id,
        title = id,
        target = TerminalTarget.SshHost(hostId = 1, hostName = id),
    )

    @Test
    fun twoViewModelsShareBuffers_writtenExactlyOncePerOutput() = runTest {
        val vm1 = TerminalViewModel(transport, repo, registry)
        val vm2 = TerminalViewModel(transport, repo, registry)
        advanceUntilIdle()

        transport._events.emit(TerminalEvent.Output("t1", "hello\r\n"))
        advanceUntilIdle()

        assertEquals("hello", registry.bufferFor("t1").getVisibleText())
        assertEquals("hello", vm1.uiState.value.snapshots["t1"]?.visibleText)
        assertEquals("hello", vm2.uiState.value.snapshots["t1"]?.visibleText)

        // A ViewModel created later (e.g. a detached window) shows the existing buffer immediately.
        val vm3 = TerminalViewModel(transport, repo, registry)
        assertNotNull(vm3.uiState.value.snapshots["t1"])
    }

    @Test
    fun resizeIsIgnoredWhileUnfocused_andAppliedOnFocusGain() = runTest {
        repo._terminalTabs.value = listOf(tab("t1"))
        val vm = TerminalViewModel(transport, repo, registry)
        advanceUntilIdle()

        vm.setWindowFocused(false)
        vm.resize(100, 30)
        advanceUntilIdle()
        assertNull(transport.lastResized)

        vm.setWindowFocused(true)
        advanceUntilIdle()
        assertEquals(Triple("t1", 100, 30), transport.lastResized)
    }

    @Test
    fun selectTab_sendsLastKnownSizeToNewTab() = runTest {
        repo._terminalTabs.value = listOf(tab("t1"), tab("t2"))
        val vm = TerminalViewModel(transport, repo, registry)
        advanceUntilIdle()

        vm.resize(120, 40)
        advanceUntilIdle()
        assertEquals(Triple("t1", 120, 40), transport.lastResized)

        vm.selectTab("t2")
        advanceUntilIdle()
        assertEquals(Triple("t2", 120, 40), transport.lastResized)
    }
}
