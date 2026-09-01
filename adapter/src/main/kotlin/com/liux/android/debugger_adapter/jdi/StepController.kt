package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.StepRequest

class StepController(private val vm: VirtualMachine) {

    private val erm: EventRequestManager = vm.eventRequestManager()
    private var activeRequest: StepRequest? = null

    fun stepOver(thread: ThreadReference) = step(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER)

    fun stepIn(thread: ThreadReference) = step(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO)

    fun stepOut(thread: ThreadReference) = step(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT)

    private fun step(thread: ThreadReference, size: Int, depth: Int) {
        clearActive()
        val req = erm.createStepRequest(thread, size, depth)
        req.setSuspendPolicy(StepRequest.SUSPEND_EVENT_THREAD)
        req.enable()
        activeRequest = req
        Logging.info("[Step] Step request: thread=${thread.name()} depth=$depth")
        vm.resume()
    }

    fun clearActive() {
        activeRequest?.let { erm.deleteEventRequest(it) }
        activeRequest = null
    }

    fun isStepInProgress(): Boolean = activeRequest != null

    fun resume() {
        clearActive()
        vm.resume()
    }

    fun pause(thread: ThreadReference?) {
        if (thread != null) thread.suspend() else vm.suspend()
    }
}