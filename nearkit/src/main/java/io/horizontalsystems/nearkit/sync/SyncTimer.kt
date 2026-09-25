package io.horizontalsystems.nearkit.sync

import io.horizontalsystems.nearkit.NearKit
import io.horizontalsystems.nearkit.network.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit
import kotlin.time.toDuration

internal class SyncTimer(
    private val syncInterval: Long,
    private val connectionManager: ConnectionManager,
) {

    interface Listener {
        fun onUpdateSyncTimerState(state: State)
        fun sync()
    }

    private var scope: CoroutineScope? = null
    private var isStarted = false
    private var isPaused = false
    private var timerJob: Job? = null
    private var listener: Listener? = null

    init {
        connectionManager.listener = object : ConnectionManager.Listener {
            override fun onConnectionChange() {
                handleConnectionChange()
            }
        }
    }

    var state: State = State.NotReady(NearKit.SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                listener?.onUpdateSyncTimerState(value)
            }
        }

    fun start(listener: Listener, scope: CoroutineScope) {
        isStarted = true
        this.listener = listener
        this.scope = scope
        connectionManager.start()
        handleConnectionChange()
    }

    fun stop() {
        isStarted = false
        isPaused = false
        connectionManager.stop()
        state = State.NotReady(NearKit.SyncError.NotStarted())
        scope = null
        stopTimer()
    }

    fun pause() {
        if (!isStarted || isPaused) return
        isPaused = true
        stopTimer()
    }

    fun resume() {
        if (!isStarted || !isPaused) return
        isPaused = false
        if (connectionManager.isConnected) {
            startTimer()
        }
    }

    private fun handleConnectionChange() {
        if (!isStarted) return

        if (connectionManager.isConnected) {
            state = State.Ready
            if (!isPaused) {
                startTimer()
            }
        } else {
            state = State.NotReady(NearKit.SyncError.NoNetworkConnection())
            stopTimer()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope?.launch {
            while (isActive) {
                listener?.sync()
                delay(syncInterval.toDuration(DurationUnit.SECONDS))
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    sealed class State {
        object Ready : State()
        class NotReady(val error: Throwable) : State()
    }
}
