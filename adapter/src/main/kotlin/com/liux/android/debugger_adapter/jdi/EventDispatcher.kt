package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.EventQueue
import com.sun.jdi.event.EventSet
import com.sun.jdi.event.StepEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import java.util.concurrent.atomic.AtomicBoolean

class EventDispatcher(
    private val queue: EventQueue,
    private val eventHandler: (DapEvent) -> Unit
) : Runnable {

    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        thread = Thread(this, "jdi-event-dispatcher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    override fun run() {
        Logging.info("EventDispatcher 启动")
        while (running.get()) {
            try {
                // EventQueue.remove(timeout) returns null when the timeout
                // elapses without any events. Treat null as "nothing to do
                // this tick" and keep polling.
                val set: EventSet? = queue.remove(1000)
                if (set == null) continue
                for (event in set) handleEvent(event, set)
                // Only auto-resume event sets that don't suspend the VM
                // (SUSPEND_NONE: VMDeath/VMDisconnect). For SUSPEND_EVENT_THREAD
                // (breakpoint/step) and SUSPEND_ALL, the handler is responsible
                // for either (a) calling set.resume() to release the thread
                // (e.g. conditional/logpoint BPs that don't want to pause),
                // or (b) leaving the set suspended so the UI can query frames
                // until the user issues continue / next / stepIn / stepOut /
                // disconnect (which call vm.resume() under the hood).
                // EventSet.SUSPEND_NONE == 0 (Java interface constant;
                // Kotlin cannot reference it directly via EventSet.SUSPEND_NONE).
                if (set.suspendPolicy() == 0) {
                    set.resume()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: com.sun.jdi.VMDisconnectedException) {
                Logging.info("VM 已断开连接")
                eventHandler(DapEvent("terminated", null, null))
                break
            } catch (e: Exception) {
                Logging.error("EventDispatcher 异常", e)
            }
        }
        Logging.info("EventDispatcher 停止")
    }

    private fun handleEvent(event: Event, set: EventSet) {
        val kind = when (event) {
            is BreakpointEvent -> "breakpoint"
            is StepEvent -> "step"
            is VMStartEvent -> "started"
            is VMDeathEvent -> "exited"
            is VMDisconnectEvent -> "terminated"
            is ClassPrepareEvent -> "class-prepare"
            else -> null
        } ?: return
        eventHandler(DapEvent(kind, event, set))
    }

    data class DapEvent(val kind: String, val jdiEvent: Event?, val jdiSet: EventSet? = null)
}