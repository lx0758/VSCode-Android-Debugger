package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.VirtualMachine
import com.sun.jdi.VirtualMachineManager
import com.sun.jdi.connect.AttachingConnector
import com.sun.jdi.connect.Connector.Argument
import java.util.concurrent.atomic.AtomicBoolean

class JdiConnection(private val host: String, private val port: Int) {

    private val attached = AtomicBoolean(false)
    private var vm: VirtualMachine? = null

    fun attach(): VirtualMachine {
        val vmm: VirtualMachineManager = com.sun.jdi.Bootstrap.virtualMachineManager()
        val connectors: List<AttachingConnector> = vmm.attachingConnectors()
        val connector = connectors.firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
            ?: throw IllegalStateException("未找到 SocketAttach 连接器")
        val args: Map<String, Argument> = connector.defaultArguments()
        args["hostname"]?.setValue(host)
        args["port"]?.setValue(port.toString())
        args["timeout"]?.setValue("10000")
        Logging.info("[JDI] 连接到 $host:$port via ${connector.name()}")
        val machine = connector.attach(args)
        vm = machine
        attached.set(true)
        return machine
    }

    fun getVm(): VirtualMachine? = vm

    fun close() {
        if (attached.compareAndSet(true, false)) {
            vm?.let {
                try { it.dispose() } catch (e: Exception) { Logging.warn("[JDI] vm.dispose 失败: ${e.message}") }
            }
            vm = null
        }
    }
}