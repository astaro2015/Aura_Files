package com.aurafiles.app.transfer

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TransferController {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val _pausedState = MutableStateFlow(false)
    val pausedState: StateFlow<Boolean> = _pausedState.asStateFlow()

    fun pause() {
        if (!cancelled.get()) {
            paused.set(true)
            _pausedState.value = true
        }
    }

    fun resume() {
        paused.set(false)
        _pausedState.value = false
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
        _pausedState.value = false
    }

    suspend fun checkpoint(onPaused: (Boolean) -> Unit = {}) {
        if (cancelled.get()) throw CancellationException("Transfer cancelled")
        if (paused.get()) onPaused(true)
        while (paused.get() && !cancelled.get()) delay(80)
        onPaused(false)
        if (cancelled.get()) throw CancellationException("Transfer cancelled")
    }

    /**
     * SMBJ exposes blocking streams, so its progress callback cannot call the
     * suspending checkpoint(). This variant is only for an already-IO thread.
     */
    fun checkpointBlocking(onPaused: (Boolean) -> Unit = {}) {
        if (cancelled.get()) throw CancellationException("Transfer cancelled")
        if (paused.get()) onPaused(true)
        while (paused.get() && !cancelled.get()) {
            Thread.sleep(80L)
        }
        onPaused(false)
        if (cancelled.get()) throw CancellationException("Transfer cancelled")
    }
}
