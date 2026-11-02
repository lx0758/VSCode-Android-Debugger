package com.liux.android.debugger_adapter.eval

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Bootstrap
import com.sun.jdi.ClassType
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector.Argument
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

/**
 * End-to-end evaluation against a **real** JVM.
 *
 * The unit tests around the evaluator use JDI proxies, which can only
 * prove that the code calls the right methods. This test instead attaches
 * to a live JVM over JDWP, breaks inside a Java method and evaluates
 * expressions there — the same situation the adapter faces when it
 * debugs a Java source file on a device.
 *
 * What it pins down is the Java-side behaviour users hit first:
 *
 *  - casts — `(String) s` used to fail with "无法将 java.lang.String
 *    转换为 String", because the short name was compared literally
 *    against the fully-qualified runtime type;
 *  - fully-qualified references — `java.util.Objects.isNull(x)` used to
 *    fail, because the dotted name was never looked up in the VM.
 */
class JavaEvaluationEndToEndTest {

    private var process: Process? = null
    private var vm: VirtualMachine? = null
    private var evaluator: RuntimeEvaluator? = null

    /**
     * The child JVM's stdout/stderr is redirected into this folder.
     *
     * It must be a JUnit-managed temporary folder: a hard-coded path
     * (e.g. `/tmp/...`) only exists on the machine that wrote it, and
     * `ProcessBuilder.redirectOutput` fails with `FileNotFoundException`
     * when the parent directory is missing — which broke every test in
     * this class on CI.
     */
    @Rule
    @JvmField
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Before fun attachAndBreak() {
        // A JVM without the `jdk.jdi` module cannot host JDWP at all —
        // skip instead of failing. Probed up front (rather than by
        // catching `NoClassDefFoundError` around the whole body, which
        // would also swallow a genuine missing-class regression).
        assumeTrue(
            "当前 JRE 不包含 com.sun.jdi, 跳过端到端求值测试",
            hasJdi(),
        )
        try {
            val port = freePort()
            process = ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$port",
                "-cp", System.getProperty("java.class.path"),
                "com.liux.android.debugger_adapter.eval.EvalTarget",
            ).redirectErrorStream(true)
                .redirectOutput(tempFolder.newFile("eval-target.log"))
                .start()

            // `attachingConnector()` / `arguments()` were removed in newer
            // JDKs — pick the socket connector and use `defaultArguments()`
            // (the same way `JdiConnection` does).
            val connector = Bootstrap.virtualMachineManager().attachingConnectors()
                .first { it.name() == "com.sun.jdi.SocketAttach" }
            val args: Map<String, Argument> = connector.defaultArguments()
            args["hostname"]?.setValue("127.0.0.1")
            args["port"]?.setValue(port.toString())
            args["timeout"]?.setValue("10000")
            // The child JVM needs a moment to bind the debug port — retry
            // until it accepts the connection.
            val attached = retryAttach(connector, args, deadline = 15_000)
            vm = attached
            attached.resume()

            val queue = attached.eventQueue()
            // `EvalTarget` is the *launch* class — it is already prepared
            // before the debugger attaches, so its ClassPrepareEvent was
            // never delivered to us. Look it up in the VM instead.
            val targetType = awaitClass(attached, "com.liux.android.debugger_adapter.eval.EvalTarget")
            // The target method waits for the READY gate, so installing the
            // breakpoint now cannot lose the race against `main`.
            val method = targetType.methodsByName("target").first()
            attached.eventRequestManager()
                .createBreakpointRequest(method.location())
                .enable()
            val readyField = targetType.allFields().first { it.name() == "READY" }
            targetType.setValue(readyField, attached.mirrorOf(true))

            var frame: StackFrame? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (frame == null && System.currentTimeMillis() < deadline) {
                for (event in queue.remove()) {
                    if (event is BreakpointEvent) {
                        // `Location.frame()` was removed in newer JDKs — the
                        // suspended thread's top frame is the breakpoint.
                        frame = event.thread().frame(0)
                    }
                }
            }
            checkNotNull(frame) { "断点未在超时前命中" }
            evaluator = RuntimeEvaluator(frame)
        } catch (e: Throwable) {
            // Every failure here is a real defect — the environment was
            // already probed for JDWP support above. Masking them would
            // turn a broken test silently green.
            throw e
        }
    }

    /** Whether this JVM ships the JDI implementation. */
    private fun hasJdi(): Boolean = try {
        Class.forName("com.sun.jdi.Bootstrap")
        true
    } catch (_: Throwable) {
        false
    }

    @After fun detach() {
        try {
            vm?.exit(1)
        } catch (_: Throwable) {
        }
        try {
            process?.destroyForcibly()
        } catch (_: Throwable) {
        }
        vm = null
        process = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Attach, retrying while the child JVM is still binding its port. */
    private fun retryAttach(
        connector: com.sun.jdi.connect.AttachingConnector,
        args: Map<String, Argument>,
        deadline: Long,
    ): VirtualMachine {
        val until = System.currentTimeMillis() + deadline
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < until) {
            try {
                return connector.attach(args)
            } catch (e: Throwable) {
                lastError = e
                Thread.sleep(100)
            }
        }
        throw IllegalStateException("attach 超时", lastError)
    }

    /** Wait until the child VM has loaded [name] and return its type. */
    private fun awaitClass(vm: VirtualMachine, name: String): ClassType {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            vm.classesByName(name).firstOrNull()?.let { return it as ClassType }
            Thread.sleep(50)
        }
        throw IllegalStateException("子 JVM 未加载类 $name")
    }

    /** Parse with the Java frontend and evaluate on the real VM. */
    private fun eval(expression: String): Value? =
        evaluator!!.eval(JavaFrontend.parse(expression))

    private fun stringOf(value: Value?): String? = (value as? StringReference)?.value()

    // ---------- casts ----------

    @Test fun `a cast to a short name works`() {
        assertEquals("hello", stringOf(eval("(String) s")))
    }

    @Test fun `a cast to a fully qualified name works`() {
        assertEquals("hello", stringOf(eval("(java.lang.String) s")))
    }

    @Test fun `a cast to a primitive works`() {
        assertEquals(7, (eval("(int) prim") as com.sun.jdi.IntegerValue).value())
    }

    @Test fun `a cast to a supertype works`() {
        assertEquals("hello", stringOf(eval("(Object) s")))
    }

    @Test fun `a cast to an unrelated type fails`() {
        try {
            eval("(Integer) s")
            org.junit.Assert.fail("应当抛出类型不匹配错误")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "错误信息应说明类型不匹配: ${e.message}",
                e.message!!.contains("ClassCastException"),
            )
        }
    }

    @Test fun `a cast to a generic type works`() {
        assertTrue(eval("(java.util.List<String>) obj") != null)
    }

    // ---------- fully-qualified references ----------

    @Test fun `a fully qualified static method call works`() {
        assertEquals(false, (eval("java.util.Objects.isNull(s)") as com.sun.jdi.BooleanValue).value())
    }

    @Test fun `a fully qualified static method with a return value works`() {
        assertEquals("hello", stringOf(eval("java.util.Objects.requireNonNull(s)")))
    }

    @Test fun `a fully qualified constructor call works`() {
        val list = eval("new java.util.ArrayList<String>()")
        assertTrue("应构造出 ArrayList", list != null)
        assertEquals("java.util.ArrayList", list!!.type().name())
    }

    @Test fun `a fully qualified class literal works`() {
        assertEquals(
            "java.lang.String",
            stringOf(eval("java.lang.String.class.getName()")),
        )
    }

    @Test fun `a fully qualified static field access works`() {
        assertEquals(2147483647, (eval("java.lang.Integer.MAX_VALUE") as com.sun.jdi.IntegerValue).value())
    }

    // ---------- instanceof ----------

    @Test fun `instanceof with a short name works`() {
        assertEquals(true, (eval("obj instanceof java.util.List") as com.sun.jdi.BooleanValue).value())
    }

    @Test fun `instanceof with a fully qualified name works`() {
        assertEquals(true, (eval("obj instanceof java.util.ArrayList") as com.sun.jdi.BooleanValue).value())
    }

    // ---------- plain evaluation still works ----------

    @Test fun `a local variable is readable`() {
        assertEquals("hello", stringOf(eval("s")))
    }

    @Test fun `a method call on a local works`() {
        assertEquals(5, (eval("s.length()") as com.sun.jdi.IntegerValue).value())
    }

    @Test fun `an arithmetic expression works`() {
        assertEquals(9, (eval("prim + 2") as com.sun.jdi.IntegerValue).value())
    }
}
