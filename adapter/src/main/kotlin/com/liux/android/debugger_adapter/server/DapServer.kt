package com.liux.android.debugger_adapter.server

import com.liux.android.debugger_adapter.jdi.BreakpointManager
import com.liux.android.debugger_adapter.jdi.EventDispatcher
import com.liux.android.debugger_adapter.jdi.ExpressionEvaluator
import com.liux.android.debugger_adapter.jdi.JdiConnection
import com.liux.android.debugger_adapter.jdi.StepController
import com.liux.android.debugger_adapter.jdi.VariableFormatter
import com.liux.android.debugger_adapter.protocol.Logging
import com.liux.android.debugger_adapter.source.SourceIndexer
import com.liux.android.debugger_adapter.transport.MessageTransport
import com.liux.android.debugger_adapter.util.DtoTypes
import com.liux.android.debugger_adapter.util.HelpText
import com.liux.android.debugger_adapter.util.sourceBasename
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sun.jdi.ArrayReference
import com.sun.jdi.Field
import com.sun.jdi.Location
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import java.util.concurrent.ExecutorService

class DapServer(
    private val executor: ExecutorService,
    private val transport: MessageTransport,
    /**
     * When set, the adapter uses this port for the JDI `SocketAttach`
     * connection instead of the `port` field carried in the DAP `attach`
     * request. This is the launcher-level knob that lets the extension
     * recover from a toolbar Restart, where VSCode reuses the OLD session
     * configuration (and thus the OLD __jdwpPort) but the actual adb
     * forward now lives at a freshly-allocated port: by passing
     * `--jdwp-port=<new>` on the JVM command line we override what the
     * DAP request would otherwise tell us.
     */
    private val jdwpPortOverride: Int? = null,
) {

    private val indexer = SourceIndexer()
    private val exprEvaluator = ExpressionEvaluator(indexer.index)
    private val varFormatter = VariableFormatter()

    private var jdi: JdiConnection? = null
    private var bpManager: BreakpointManager? = null
    private var stepController: StepController? = null
    private var eventDispatcher: EventDispatcher? = null

    private val frameRefByReqId = HashMap<String, Int>()
    private var nextFrameId = 1
    private val reqFrameToFrameId = HashMap<Int, Int>()
    private val frameIdToIndex = HashMap<Int, Int>()
    private val frameIdToThreadId = HashMap<Int, Int>()
    @Volatile private var shutdown = false
    private val eventSeq = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * Single lock for ALL JDI access from request handlers and the
     * EventDispatcher's `handleJdiEvent` callback.
     *
     * Why this exists: JDI's `VirtualMachine` is documented as not thread-safe.
     * Specifically, `vm.classesByName()` and `vm.allClasses()` return lists
     * backed by the VM's live class table; if a class is loaded by the target
     * process while we iterate one of those lists, we get a
     * ConcurrentModificationException. This actually happened in the wild —
     * `setBreakpoints` and `custom/syncBreakpoints` running on different executor
     * threads both called `findLocationsForLine` at the same time, the app
     * loaded a class mid-iteration, and the adapter crashed with CME.
     *
     * Both the request handler executor threads AND the EventDispatcher
     * thread (which calls `tryDrainForClass` on `ClassPrepareEvent`) touch
     * JDI, so the lock has to span both. Wrapping `dispatch()` (the request
     * entry point) and `handleJdiEvent()` (the event entry point) is enough
     * — every other JDI call is reachable through one of those two.
     *
     * Non-JDI DAP work (loadedSources, custom/syncSourceIndex, configurationDone,
     * initialize) doesn't need the lock; we keep those handlers
     * lock-free for parallel throughput. The few that don't need JDI run
     * inside `dispatch()` but contend on the lock briefly — acceptable.
     */
    private val jdiLock = Any()
    private inline fun <T> withJdiLock(block: () -> T): T = synchronized(jdiLock) { block() }

    // Centralised (file, line) -> adapter id mapping. Decoupled from
    // BreakpointManager so we can assign stable ids even BEFORE the JDI
    // connection is established (VSCode fires setBreakpoints before attach).
    // BPs that cannot be placed immediately are tracked in `pendingJdiBps`
    // and re-tried by `handleAttach` once bpManager exists.
    private val bpIdSeq = java.util.concurrent.atomic.AtomicInteger(1)
    private val bpIdRegistry = HashMap<String, MutableMap<Int, Int>>()
    private val pendingJdiBps = mutableListOf<PendingBp>()

    /**
     * normalized canonical key → the path form VSCode sent us (full
     * `vscode-remote://...` URI in remote windows, fsPath locally). All
     * DAP echoes (breakpoint events, stopped events) MUST use this form —
     * a bare posix key renders as `\home\...` on a Windows client and
     * never resolves back to a file.
     */
    private val displayPathByFile = HashMap<String, String>()

    private data class PendingBp(
        val id: Int,
        val filePath: String,
        val line: Int,
        val condition: String?,
        val logMessage: String?,
    )

    companion object {
        // IDs >= this value are variable references (object/array children).
        // IDs below are stack frame IDs. Avoids collision between the two
        // ID spaces.
        private const val VAR_REF_BASE = 1000
        private val HELP_TRIGGERS = setOf("help", "/help", "?", "/?")
    }

    fun handleIncoming(msg: JsonObject) {
        val type = msg.get("type")?.asString ?: return
        if (type == "request") handleRequest(msg)
    }

    private fun handleRequest(req: JsonObject) {
        val command = req.get("command").asString
        val seq = req.get("seq").asInt
        Logging.info("[Server] 收到请求: $command (seq=$seq)")
        executor.execute {
            try {
                val args = req.get("arguments")?.asJsonObject ?: JsonObject()
                val body = dispatch(command, args)
                sendResponse(seq, command, true, null, body)
            } catch (e: Exception) {
                Logging.error("[Server] 处理请求失败: $command", e)
                sendResponse(seq, command, false, e.message, null)
            }
        }
    }

    private fun dispatch(command: String, args: JsonObject): JsonObject = withJdiLock {
        when (command) {
            "initialize" -> handleInitialize()
            "attach" -> handleAttach(args)
            "disconnect" -> handleDisconnect(args)
            "configurationDone" -> handleConfigurationDone()
            "setBreakpoints" -> handleSetBreakpoints(args)
            "threads" -> handleThreads()
            "stackTrace" -> handleStackTrace(args)
            "scopes" -> handleScopes(args)
            "variables" -> handleVariables(args)
            "continue" -> handleContinue(args)
            "next" -> handleNext(args)
            "stepIn" -> handleStepIn(args)
            "stepOut" -> handleStepOut(args)
            "pause" -> handlePause(args)
            "evaluate" -> handleEvaluate(args)
            "setVariable" -> handleSetVariable(args)
            "setExpression" -> handleSetExpression(args)
            "loadedSources" -> handleLoadedSources(args)
            "custom/syncBreakpoints" -> handleSyncBreakpoints(args)
            "custom/syncSourceIndex" -> handleSyncSourceIndex(args)
            else -> {
                Logging.warn("[Server] 未实现请求: $command")
                JsonObject()
            }
        }
    }

    private fun handleInitialize(): JsonObject {
        val body = DtoTypes.initializeResult()
        sendEvent("initialized", JsonObject())
        return body
    }

    private fun handleAttach(args: JsonObject): JsonObject {
        val host = args.get("hostName").asString
        val portFromRequest = args.get("port").asInt
        // If the JVM was launched with --jdwp-port=<port>, that port is
        // authoritative — it points to the adb forward the extension
        // actually set up for THIS session. The DAP `attach` request's
        // `port` field is the value VSCode carries in its session
        // configuration, which on toolbar Restart is the OLD session's
        // port (and the forward at that port has been torn down by the
        // atomic teardown policy).
        val port = jdwpPortOverride ?: portFromRequest
        if (jdwpPortOverride != null && jdwpPortOverride != portFromRequest) {
            Logging.info("[Server] handleAttach: 使用 --jdwp-port 覆盖 DAP 请求的 port ($portFromRequest → $port)")
        }
        val conn = JdiConnection(host, port)
        val vm = conn.attach()
        jdi = conn
        bpManager = BreakpointManager(vm)
        stepController = StepController(vm)
        eventDispatcher = EventDispatcher(vm.eventQueue()) { event -> handleJdiEvent(event) }.also { it.start() }

        // Load the initial source index from the DAP `attach` request's
        // `arguments.fqnInfo` field (sent by the extension as a JSON array
        // of {fqn, file} entries). MUST happen BEFORE drainPendingBps() —
        // VSCode may have already queued setBreakpoints while we were
        // connecting, and those BPs need an indexed SourceIndex to resolve
        // on the first drain pass.
        val fqnInfo = args.get("fqnInfo")?.asJsonArray
        if (fqnInfo != null && fqnInfo.size() > 0) {
            val list = SourceIndexer.parseEntries(fqnInfo)
            indexer.add(list)
            Logging.info("[Server] handleAttach: 从 arguments.fqnInfo 加载 ${list.size} 条索引")
        }

        // VSCode fires setBreakpoints BEFORE attach, so those BPs were
        // answered with verified=false because bpManager did not yet
        // exist. Now that the JDI connection is up, retry every deferred
        // BP and push a `breakpoint` event so VSCode can flip the UI BP
        // from unverified to verified.
        drainPendingBps()

        return JsonObject()
    }

    /**
     * Walk `pendingJdiBps` once, attempting to install each in JDI now that
     * `bpManager` exists. Successfully-placed BPs emit a `breakpoint` event
     * with verified=true; those that still cannot be placed (class not
     * loaded, etc.) are pushed back onto `pendingJdiBps` for the next
     * class-prepare event / re-set.
     */
    private fun drainPendingBps() {
        if (pendingJdiBps.isEmpty()) return
        Logging.info("[Server] draining ${pendingJdiBps.size} deferred BPs after attach")
        val toProcess = pendingJdiBps.toList()
        pendingJdiBps.clear()
        for (pb in toProcess) {
            val (placed, reason) = tryPlaceBp(pb.filePath, pb.line, pb.condition, pb.logMessage)
            // Per-BP log stays at DEBUG — attach can carry dozens of pending
            // BPs and we don't want each one burning a synchronous stderr
            // flush during startup.
            Logging.debug {
                "[Server] drain bp id=${pb.id} file=${pb.filePath} line=${pb.line} placed=$placed reason=$reason"
            }
            if (placed) {
                sendEvent(
                    "breakpoint",
                    DtoTypes.breakpointEvent(pb.id, true, pb.line, pb.filePath, null)
                )
            } else {
                // Still not placeable. Re-queue for the next attempt.
                pendingJdiBps.add(pb)
            }
        }
    }

    /**
     * Targeted version of `drainPendingBps` invoked from the class-prepare
     * event handler. Only the BPs whose source file's indexed FQNs could
     * possibly live inside the newly-loaded class are retried — every other
     * BP would just re-run the inner-sibling walk and come back with the
     * same "not placed" reason.
     *
     * Why this matters: a debug session sees thousands of class-prepare
     * events (one per loaded class), and `findLocationsForLine` walks
     * `vm.allClasses()` per pending BP. Naively draining on every event
     * makes startup cost roughly O(classLoads × pendingBPs × loadedClasses),
     * which is what made the original implementation perceptibly slow.
     * Skipping non-matching BPs turns the class-prepare path into
     * O(classLoads × pendingBPs) for the namespace check itself.
     */
    private fun tryDrainForClass(
        loadedClassName: String,
        loadedType: com.sun.jdi.ReferenceType?,
    ) {
        if (pendingJdiBps.isEmpty()) return

        // Pre-compute which pending BPs are reachable from `loadedClassName`.
        // A BP for filePath F is reachable iff some indexed FQN `fqn` of F
        // satisfies `loadedClassName == fqn` (exact match) or
        // `loadedClassName.startsWith("$fqn$")` (the class is an inner /
        // anonymous sibling of fqn's top-level class).
        val reachable = pendingJdiBps.filter { pending ->
            val fqns = indexer.sourceIndex().resolveFqnsByFile(pending.filePath)
            fqns.any { fqn ->
                loadedClassName == fqn || loadedClassName.startsWith("$fqn\$")
            }
        }
        if (reachable.isEmpty()) return

        Logging.debug(
            "[Server] class-prepare $loadedClassName: ${reachable.size}/${pendingJdiBps.size} pending BPs match"
        )

        for (pb in reachable) {
            val (placed, reason) = tryPlaceBp(pb.filePath, pb.line, pb.condition, pb.logMessage, loadedType)
            if (placed) {
                pendingJdiBps.removeAll { it.id == pb.id }
                Logging.info(
                    "[Server] class-prepare $loadedClassName 命中 pending bp: " +
                        "id=${pb.id} file=${pb.filePath} line=${pb.line}"
                )
                sendEvent(
                    "breakpoint",
                    DtoTypes.breakpointEvent(pb.id, true, pb.line, pb.filePath, null)
                )
            } else {
                Logging.debug(
                    "[Server] class-prepare $loadedClassName: bp id=${pb.id} 仍未匹配: $reason"
                )
            }
        }
    }

    private fun handleConfigurationDone(): JsonObject {
        // VSCode sends configurationDone after the initial set of requests
        // (initialize, attach, setBreakpoints, ...). For attach, the VM is
        // already running, but if any pending event sets from a SUSPEND_ALL
        // event (e.g. VMStart) are still in the queue, we resume the VM
        // here so they are released.
        jdi?.getVm()?.resume()
        return JsonObject()
    }

    private fun handleDisconnect(@Suppress("UNUSED_PARAMETER") args: JsonObject): JsonObject {
        shutdown = true
        eventDispatcher?.stop()
        bpManager?.clearAll()
        jdi?.close()
        try { jdi?.getVm()?.exit(0) } catch (_: Exception) { }
        executor.shutdown()
        return JsonObject()
    }

    private fun handleSyncSourceIndex(args: JsonObject): JsonObject {
        val entries = args.get("entries")?.asJsonArray ?: JsonArray()
        val list = SourceIndexer.parseEntries(entries)

        // Compare file sets BEFORE replacing the index. Any file in oldFiles
        // but not in newFiles belonged to a source dir the user just removed
        // — those BPs must be torn down in JDI and reported as unverified.
        // Any file in newFiles but not in oldFiles was just added — those
        // BPs get a placement attempt.
        val oldFiles = indexer.sourceIndex().entries().map { it.path }.toSet()

        indexer.set(list)

        val newFiles = indexer.sourceIndex().entries().map { it.path }.toSet()
        val removedFiles = oldFiles - newFiles
        val addedFiles = newFiles - oldFiles

        for (filePath in removedFiles) {
            markBreakpointsUnverified(filePath, "源目录已移除")
        }
        for (filePath in addedFiles) {
            tryPlaceBreakpointsForFile(filePath)
        }

        return JsonObject().apply { addProperty("indexedClasses", indexer.sourceIndex().size()) }
    }

    /**
     * Mark every BP tracked for `filePath` as unverified: tear down the JDI
     * request so it stops firing, but KEEP the bpIdRegistry entry so the
     * UI BP marker stays visible and we can re-verify when the source dir
     * is re-added. Each affected BP gets queued back into `pendingJdiBps`
     * so the next `tryPlaceBp` pass picks it up.
     */
    private fun markBreakpointsUnverified(filePath: String, message: String) {
        val lineMap = bpIdRegistry[filePath] ?: return
        bpManager?.clearBreakpointsForFile(filePath)
        for ((line, id) in lineMap) {
            if (pendingJdiBps.none { it.id == id }) {
                pendingJdiBps.add(PendingBp(id, filePath, line, null, null))
            }
            Logging.info("[Server] 源目录移除, BP 转未验证: id=$id file=$filePath line=$line")
            sendEvent(
                "breakpoint",
                DtoTypes.breakpointEvent(id, false, line, displayPathFor(filePath), message)
            )
        }
    }

    /**
     * Walk every BP registered for `filePath` and try to (re-)install it in
     * JDI. Successful placements emit a `breakpoint` changed event with
     * verified=true; failures (class not loaded yet, line invalid) leave
     * the BP queued in `pendingJdiBps`.
     */
    private fun tryPlaceBreakpointsForFile(filePath: String) {
        val lineMap = bpIdRegistry[filePath] ?: return
        for ((line, id) in lineMap) {
            val (placed, _) = tryPlaceBp(filePath, line, null, null)
            if (placed) {
                pendingJdiBps.removeAll { it.id == id }
                Logging.info("[Server] 源目录新增后, BP 重新验证: id=$id file=$filePath line=$line")
                sendEvent(
                    "breakpoint",
                    DtoTypes.breakpointEvent(id, true, line, displayPathFor(filePath), null)
                )
            }
        }
    }

    /**
     * Canonicalize a source path into the same key space the extension's
     * source index uses (`file:` URIs → fsPath form; everything else → the
     * URI path). VSCode sends `source.path` as a full URI in remote windows
     * (e.g. `vscode-remote://ssh-remote%2Bhost/home/liux/X.java`), while the
     * custom/syncSourceIndex entries use the bare posix path `/home/liux/X.java`
     * — without normalization `resolveFqnsByFile` would never match.
     */
    private fun normalizeSourcePath(raw: String): String {
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/]*)(/.*)$").find(raw) ?: return raw
        val (scheme, _, path) = m.destructured
        if (scheme == "file" && Regex("^/[A-Za-z]:/").matches(path)) {
            // Windows drive form `file:///C:/x` → `C:/x`
            return path.substring(1)
        }
        return path
    }

    private fun handleSetBreakpoints(args: JsonObject): JsonObject {
        val src = args.get("source")?.asJsonObject ?: JsonObject()
        val rawPath = src.get("path")?.asString ?: ""
        val filePath = normalizeSourcePath(rawPath)
        if (filePath.isNotEmpty()) {
            displayPathByFile[filePath] = rawPath
        }
        val bps = args.get("breakpoints")?.asJsonArray ?: JsonArray()

        Logging.info("[Server] setBreakpoints file=$filePath lines=${bps.map { it.asJsonObject.get("line")?.asInt }}")

        // Clear JDI BPs for this file. DO NOT touch bpIdRegistry — see
        // handleSyncBreakpoints for the state machine: DISABLE preserves the
        // id (so re-enable can reuse it), REMOVE clears the id via the diff.
        // DAP's setBreakpoints cannot distinguish disable from remove (both
        // arrive as lines=[]); the disambiguation happens in custom/syncBreakpoints
        // driven by e.changed vs e.removed.
        bpManager?.clearBreakpointsForFile(filePath)

        val fqns = indexer.sourceIndex().resolveFqnsByFile(filePath)
        Logging.info("[Server] setBreakpoints resolved FQNs=$fqns")

        val resultBps = JsonArray()
        for (bpEl in bps) {
            val bp = bpEl.asJsonObject
            val line = bp.get("line")?.asInt ?: 0
            val condition = bp.get("condition")?.takeUnless { it.isJsonNull }?.asString
            val logMessage = bp.get("logMessage")?.takeUnless { it.isJsonNull }?.asString

            // Reuse id if already in registry (setBreakpoints fires after
            // custom/syncBreakpoints placed the BP), allocate only for fresh adds.
            val bpId = bpIdRegistry
                .getOrPut(filePath) { mutableMapOf() }
                .getOrPut(line) { bpIdSeq.getAndIncrement() }

            val (placed, reason) = tryPlaceBp(filePath, line, condition, logMessage)
            if (!placed) {
                pendingJdiBps.add(PendingBp(bpId, filePath, line, condition, logMessage))
            }
            Logging.info("[Server] setBreakpoints line=$line id=$bpId verified=$placed reason=$reason file=$filePath")
            resultBps.add(DtoTypes.breakpoint(bpId, placed, line))
        }
        return JsonObject().apply { add("breakpoints", resultBps) }
    }

    /**
     * Try to install a JDI BP for (file, line) right now. Returns
     * (placed, reason) where reason is a short diagnostic suitable for logs.
     */
    private fun tryPlaceBp(
        filePath: String,
        line: Int,
        condition: String?,
        logMessage: String?,
        hintType: com.sun.jdi.ReferenceType? = null,
    ): Pair<Boolean, String> {
        val bpManager = this.bpManager ?: return false to "vm not attached yet"
        val fqns = indexer.sourceIndex().resolveFqnsByFile(filePath)
        if (fqns.isEmpty()) return false to "no FQNs indexed for file"

        val bpId = bpIdRegistry[filePath]?.get(line) ?: return false to "no id assigned"
        // Resolve every ReferenceType (including anonymous inner siblings
        // OuterClass$1, OuterClass$InnerName) that has a Location at `line`
        // in `filePath`. Without the sibling walk, line numbers inside
        // `new Runnable() { void run() { ... } }` — compiled to a separate
        // class file — silently never resolve and the BP shows as
        // "unverified" forever. `hintType` (when supplied by the class-
        // prepare handler) skips the expensive vm.allClasses() walk.
        val matches = bpManager.findLocationsForLine(filePath, line, fqns, hintType)
        if (matches.isEmpty()) {
            return false to "line $line not in ${fqns.joinToString()} (and no anonymous inner class matched)"
        }
        // Place on the first match. setBreakpointWithId short-circuits for
        // subsequent calls with the same bpId (activeRequests check), so
        // duplicate matches collapse into one JDI request. If a source line
        // genuinely maps to multiple inner classes the user can narrow with
        // a `condition` expression to disambiguate.
        val (rt, loc) = matches.first()
        bpManager.setBreakpointWithId(bpId, rt, loc, filePath, line, condition, logMessage)
        return true to "placed in ${rt.name()}"
    }

    private fun handleThreads(): JsonObject {
        val result = JsonArray()
        jdi?.getVm()?.allThreads()?.forEach { result.add(DtoTypes.thread(it.uniqueID(), it.name())) }
        return JsonObject().apply { add("threads", result) }
    }

    private fun handleStackTrace(args: JsonObject): JsonObject {
        val threadId = args.get("threadId").asInt
        val thread = findThread(threadId) ?: throw Exception("线程不存在: $threadId")
        val frames = JsonArray()
        var idx = 0
        for (frame in thread.frames()) {
            val loc = frame.location()
            val fqn = loc.declaringType().name()
            val line = loc.lineNumber()
            val entry = indexer.sourceIndex().resolveByFqn(fqn)
            val sourcePath = entry?.displayPath
            val sourceName = sourcePath?.let { sourceBasename(it).takeIf { n -> n.isNotEmpty() } ?: it }
            val frameName = "${loc.declaringType().name()}.${loc.method().name()}"
            val frameId = nextFrameId++
            frameIdToIndex[frameId] = idx
            frameIdToThreadId[frameId] = threadId
            frameRefByReqId["$threadId:$idx"] = frameId
            val sf = DtoTypes.stackFrame(frameId, frameName, line, sourcePath, sourceName)
            frames.add(sf)
            if (idx == 0) {
                Logging.info(
                    "[Server] stackTrace[0] fqn=$fqn line=$line sourcePath=$sourcePath sourceName=$sourceName"
                )
            }
            idx++
        }
        return JsonObject().apply {
            add("stackFrames", frames)
            addProperty("totalFrames", idx)
        }
    }

    private fun handleScopes(args: JsonObject): JsonObject {
        val frameId = args.get("frameId").asInt
        if (frameIdToIndex[frameId] == null) throw Exception("栈帧不存在: $frameId")
        val scopes = JsonArray()
        scopes.add(DtoTypes.scope("Locals", frameId))
        return JsonObject().apply { add("scopes", scopes) }
    }

    private fun handleVariables(args: JsonObject): JsonObject {
        val ref = args.get("variablesReference").asInt
        val result = JsonArray()
        when {
            ref == 0 -> {
                // Not expandable
            }
            ref < VAR_REF_BASE -> {
                // Frame reference → expand to locals
                val frame = findFrame(ref) ?: throw Exception("栈帧不存在: $ref")
                val vars = varFormatter.formatLocals(frame)
                for (v in vars) {
                    result.add(DtoTypes.variable(v.name, v.value, v.variablesReference, v.type))
                }
            }
            else -> {
                // Variable reference (object / array) → expand fields / elements
                val vars = varFormatter.expand(ref)
                for (v in vars) {
                    result.add(DtoTypes.variable(v.name, v.value, v.variablesReference, v.type))
                }
            }
        }
        return JsonObject().apply { add("variables", result) }
    }

    private fun handleContinue(@Suppress("UNUSED_PARAMETER") args: JsonObject): JsonObject {
        clearFrameRefs()
        varFormatter.clear()
        stepController?.resume()
        return JsonObject().apply { addProperty("allThreadsContinued", true) }
    }

    private fun handleNext(args: JsonObject): JsonObject {
        clearFrameRefs()
        varFormatter.clear()
        val threadId = args.get("threadId").asInt
        stepController?.stepOver(findThread(threadId) ?: throw Exception("线程不存在: $threadId"))
        return JsonObject()
    }

    private fun handleStepIn(args: JsonObject): JsonObject {
        clearFrameRefs()
        varFormatter.clear()
        val threadId = args.get("threadId").asInt
        stepController?.stepIn(findThread(threadId) ?: throw Exception("线程不存在: $threadId"))
        return JsonObject()
    }

    private fun handleStepOut(args: JsonObject): JsonObject {
        clearFrameRefs()
        varFormatter.clear()
        val threadId = args.get("threadId").asInt
        stepController?.stepOut(findThread(threadId) ?: throw Exception("线程不存在: $threadId"))
        return JsonObject()
    }

    private fun handlePause(args: JsonObject): JsonObject {
        val threadId = args.get("threadId")?.asInt ?: -1
        val target = if (threadId > 0) findThread(threadId) else null
        stepController?.pause(target)

        // Suspending the threads is not enough on its own — VSCode's debug
        // toolbar only flips from "running" to "paused" once it sees a
        // `stopped` event with reason="pause". Without that event the UI
        // stays in the running state and the user can't even click
        // continue (or if they do, the response comes back but the toolbar
        // never updates). Send the event after the suspend completes.
        val vm = jdi?.getVm() ?: return JsonObject()
        val thread = target
            ?: run {
                val all = vm.allThreads()
                // Selection priority for the auto-picked thread on VM-wide
                // pause: main > running non-virtual > non-virtual > any.
                // Android main thread is the only one users care about for
                // inspection when no specific thread was named.
                all.firstOrNull { it.name() == "main" }
                    ?: all.firstOrNull {
                        !it.isVirtual && it.status() == com.sun.jdi.ThreadReference.THREAD_STATUS_RUNNING
                    }
                    ?: all.firstOrNull { !it.isVirtual }
                    ?: all.firstOrNull()
            }
        if (thread == null) {
            Logging.warn("[Server] pause: VM 中没有可用线程, 跳过 stopped event")
            return JsonObject()
        }
        val stopped = DtoTypes.stopped("pause", thread.uniqueID().toInt())
        stopped.addProperty("allThreadsStopped", target == null)
        try {
            if (thread.frameCount() > 0) {
                val frame = thread.frame(0)
                val loc = frame.location()
                val fqn = loc.declaringType().name()
                val entry = indexer.sourceIndex().resolveByFqn(fqn)
                val effectiveLine = if (loc.lineNumber() >= 1) loc.lineNumber() else 1
                if (entry != null) {
                    val sourcePath = entry.displayPath
                    val sourceName = sourceBasename(sourcePath).takeIf { it.isNotEmpty() } ?: sourcePath
                    stopped.add("source", DtoTypes.source(sourcePath, sourceName))
                    stopped.addProperty("line", effectiveLine)
                    stopped.addProperty("column", 1)
                } else {
                    Logging.warn("[Server] pause: 未找到 FQN 对应的源: $fqn")
                    stopped.addProperty("line", effectiveLine)
                    stopped.addProperty("column", 1)
                }
            }
        } catch (e: Exception) {
            // VM-resume races with frame-walk happen sometimes; ignore.
            Logging.warn("[Server] pause: 获取栈帧失败: ${e.message}")
        }
        sendEvent("stopped", stopped)
        Logging.info("[Server] pause: 已暂停 thread=${thread.name()}, 发送 stopped event")
        return JsonObject()
    }

    private fun handleEvaluate(args: JsonObject): JsonObject {
        val expr = args.get("expression").asString
        val trimmed = expr.trim()

        // Built-in help: bypass the parser AND the frame lookup so users
        // get the cheat sheet even when no frame is in context (e.g. they
        // typed `?` in a console that isn't attached to a paused thread).
        if (trimmed.lowercase() in HELP_TRIGGERS) {
            Logging.info("[Server] evaluate: 返回表达式语法手册")
            return JsonObject().apply {
                addProperty("result", HelpText.EVALUATE_LANGUAGE_REFERENCE)
                addProperty("variablesReference", 0)
            }
        }

        // frameId is optional per the DAP spec, but any expression that
        // resolves a local variable needs one. Surface a clean error
        // instead of NPE'ing on `args.get("frameId").asInt`.
        val frameIdEl = args.get("frameId")
        if (frameIdEl == null || frameIdEl.isJsonNull) {
            throw Exception(
                "表达式求值需要一个暂停的栈帧 (请先触发断点或暂停). " +
                    "如果只是想查看语法手册, 请输入 help 或 ?."
            )
        }
        val frameId = frameIdEl.asInt
        val frame = findFrame(frameId) ?: throw Exception("栈帧不存在: $frameId")
        // D30: single evaluation pass returns the raw Value; when it is an
        // expandable object/array we register it with the formatter so the
        // Watch pane can expand the result, matching the variables pane.
        val (value, display) = exprEvaluator.evaluateForResponse(expr, frame)
        val ref = if (value != null && isExpandable(value)) varFormatter.registerExpandable(value) else 0
        return JsonObject().apply {
            addProperty("result", display)
            addProperty("variablesReference", ref)
        }
    }

    /**
     * DAP `setVariable`: user-edited a value in the Variables pane.
     *
     * - `variablesReference` <  VAR_REF_BASE → it is a frameId, `name` is a
     *   local / parameter; call `StackFrame.setValue`.
     * - `variablesReference` >= VAR_REF_BASE → it points to a previously
     *   expanded object / array we cached; `name` is a field name
     *   (objects) or `[N]` (arrays). Resolve the new value expression in
     *   some available frame (typically a literal, so frame context only
     *   matters for VM access).
     */
    private fun handleSetVariable(args: JsonObject): JsonObject {
        val ref = args.get("variablesReference").asInt
        val name = args.get("name").asString
        val valueExpr = args.get("value").asString

        val evalFrame = findAnySuspendedFrame()
            ?: throw Exception("没有可用的栈帧, 暂不能修改变量")

        // The `value` field of setVariable is a free-form expression string;
        // resolve it directly. Most cases are literals (`"foo"`, `42`, `true`)
        // which don't need a frame, but allowing arbitrary expressions
        // mirrors the REPL.
        val newValue = exprEvaluator.resolveExpression(
            valueExpr.trim().trimEnd(';').trim(),
            evalFrame
        )

        if (ref < VAR_REF_BASE) {
            val frame = findFrame(ref) ?: throw Exception("栈帧不存在: $ref")
            val local = try {
                frame.visibleVariables().firstOrNull { it.name() == name }
            } catch (e: com.sun.jdi.AbsentInformationException) {
                throw Exception("no local variable info available (compile with -g)")
            } ?: throw Exception("找不到局部变量: $name")
            try {
                frame.setValue(local, newValue)
            } catch (e: IllegalArgumentException) {
                // LocalVariable doesn't expose isFinal() in JDI, so we
                // can't distinguish "final" from "synthetic / arg slot"
                // here. The IllegalArgumentException typically comes from
                // any of: final, synthetic, arg-out-of-scope, or arg-slot
                // write-protection. Tell the user the JVM rejected it.
                throw Exception(
                    "无法修改局部变量 '$name': JVM 拒绝了写入 (可能是 final / 合成变量 / 参数槽位). " +
                        "若需修改请检查源码声明. 原始错误: ${e.message}"
                )
            }
            Logging.info("[Server] setVariable: 帧级变量 $name = $valueExpr")
            val newRef = if (newValue != null && isExpandable(newValue)) varFormatter.registerExpandable(newValue) else 0
            return JsonObject().apply {
                addProperty("value", VariableFormatter.formatValueForResponse(newValue))
                addProperty("variablesReference", newRef)
                addProperty("type", newValue?.type()?.name() ?: "null")
            }
        }

        val parent = varFormatter.resolveValue(ref)
            ?: throw Exception("变量引用已失效, 请重新展开变量 (ref=$ref)")
        if (parent is ObjectReference) {
            val type = parent.referenceType()
            // ReferenceType.allFields() includes inherited fields, so a
            // single pass is enough — no need to walk the class hierarchy
            // by hand.
            val field: Field? = type.allFields().firstOrNull { it.name() == name }
            field ?: throw Exception("找不到字段: $name (${type.name()})")
            try {
                parent.setValue(field, newValue)
            } catch (e: com.sun.jdi.InvalidTypeException) {
                // Final field write is rejected by the target VM. Static
                // finals are additionally constant-folded at compile time,
                // so even an Unsafe bypass wouldn't help — the inlined
                // value is what code actually reads.
                val kind = if (field.isStatic) "static final" else "final"
                val caveat = if (field.isStatic)
                    " 且编译器通常会内联 static final 常量, 即使绕过也可能不生效"
                else ""
                throw Exception(
                    "无法修改 ${kind} 字段 '${type.name()}.$name': " +
                        "JVM 在字段层强制 final$caveat. " +
                        "若需调试请修改源码去掉 final 后重新部署."
                )
            }
            Logging.info("[Server] setVariable: 字段 ${type.name()}.$name = $valueExpr")
            val newRef = if (newValue != null && isExpandable(newValue)) varFormatter.registerExpandable(newValue) else 0
            return JsonObject().apply {
                addProperty("value", VariableFormatter.formatValueForResponse(newValue))
                addProperty("variablesReference", newRef)
                addProperty("type", newValue?.type()?.name() ?: "null")
            }
        }
        throw Exception("暂不支持修改该类型变量 (ref=$ref, type=${parent.type().name()})")
    }

    private fun findAnySuspendedFrame(): com.sun.jdi.StackFrame? {
        val vm = jdi?.getVm() ?: return null
        for (t in vm.allThreads()) {
            try {
                if (t.isSuspended && t.frameCount() > 0) return t.frame(0)
            } catch (_: Exception) { /* ignore */ }
        }
        return null
    }

    private fun isExpandable(value: Value?): Boolean {
        if (value == null) return false
        if (value is ArrayReference) return value.length() > 0
        if (value is ObjectReference) {
            return try { value.referenceType().visibleFields().isNotEmpty() } catch (_: Exception) { false }
        }
        return false
    }

    /**
     * DAP `setExpression`: user assigns a value to the result of an
     * expression (e.g. WATCH pane "edit value"). Implemented as an
     * evaluate-with-assignment under the hood — `expression = value`.
     */
    private fun handleSetExpression(args: JsonObject): JsonObject {
        val expression = args.get("expression").asString
        val value = args.get("value").asString
        val frameId = args.get("frameId")?.takeUnless { it.isJsonNull }?.asInt
        val frame = if (frameId != null) {
            findFrame(frameId) ?: throw Exception("栈帧不存在: $frameId")
        } else {
            findAnySuspendedFrame() ?: throw Exception("没有可用的栈帧")
        }
        val newValue = exprEvaluator.resolveExpression(value, frame)
        Logging.info("[Server] setExpression: $expression = $newValue")
        // Run the assignment through evaluate so we get the formatted text
        // back and reuse the existing assignment code path.
        val display = exprEvaluator.evaluateExpression("$expression = $value", frame)
        return JsonObject().apply {
            addProperty("value", display)
            addProperty("variablesReference", 0)
        }
    }

    /**
     * DAP `loadedSources`: enumerate every source file the adapter
     * currently has indexed. Backed by SourceIndex so VSCode can show a
     * source list / filter — handy when the user opens a file that
     * happens not to be in the editor's open-tabs set.
     */
    private fun handleLoadedSources(@Suppress("UNUSED_PARAMETER") args: JsonObject?): JsonObject {
        val sources = JsonArray()
        val seenPaths = HashSet<String>()
        for (entry in indexer.sourceIndex().entries()) {
            if (entry.path in seenPaths) continue
            seenPaths.add(entry.path)
            val shownPath = entry.displayPath
            val sourceName = sourceBasename(shownPath)
                .takeIf { it.isNotEmpty() } ?: shownPath
            sources.add(DtoTypes.source(shownPath, sourceName))
        }
        Logging.info("[Server] loadedSources: 返回 ${sources.size()} 个源文件")
        return JsonObject().apply { add("sources", sources) }
    }

    private fun handleJdiEvent(event: EventDispatcher.DapEvent): Unit = withJdiLock {
        when (event.kind) {
            "breakpoint" -> {
                val bp = event.jdiEvent as? com.sun.jdi.event.BreakpointEvent
                val loc = bp?.location()
                Logging.info(
                    "[Server] JDI event: breakpoint line=${loc?.lineNumber()} fqn=${loc?.declaringType()?.name()}"
                )
                val set = event.jdiSet ?: return@withJdiLock
                handleBreakpoint(bp ?: return@withJdiLock, set)
            }
            "step" -> {
                val s = event.jdiEvent as? com.sun.jdi.event.StepEvent
                val loc = s?.location()
                Logging.info(
                    "[Server] JDI event: step line=${loc?.lineNumber()} fqn=${loc?.declaringType()?.name()}"
                )
                val set = event.jdiSet ?: return@withJdiLock
                handleStep(s ?: return@withJdiLock, set)
            }
            "class-prepare" -> {
                // ClassPrepareRequest's default suspend policy is SUSPEND_ALL,
                // which would freeze the VM after every class load. Resume
                // BEFORE doing any work so the app keeps running.
                val cp = event.jdiEvent as? com.sun.jdi.event.ClassPrepareEvent
                val loadedType = cp?.referenceType()
                val className = loadedType?.name().orEmpty()
                event.jdiSet?.resume()

                // Class-prepare fires for EVERY class the VM loads, including
                // stdlib classes (java.*, kotlin.*, etc.) that can never match
                // any of our pending BPs. Logging at INFO level would also
                // spam thousands of lines at startup. Both issues were the
                // root cause of the "slow after class-prepare" symptom.
                Logging.debug("[Server] JDI event: class-prepare $className")
                if (pendingJdiBps.isEmpty()) return@withJdiLock
                tryDrainForClass(className, loadedType)
            }
            else -> {
                Logging.info("[Server] JDI event: ${event.kind}")
                when (event.kind) {
                    "terminated" -> sendEvent("terminated", JsonObject())
                    "exited" -> sendEvent("exited", JsonObject())
                }
            }
        }
    }

    private fun handleBreakpoint(bpEvent: com.sun.jdi.event.BreakpointEvent, set: com.sun.jdi.event.EventSet) {
        val thread = bpEvent.thread()
        val loc = bpEvent.location()
        val fqn = loc.declaringType().name()
        val entry = indexer.sourceIndex().resolveByFqn(fqn)
        val meta = bpManager?.findMeta(bpEvent)
        val bpId = bpManager?.findIdForRequest(bpEvent.request()) ?: 0

        // Push a `breakpoint` event with verified=true as soon as the BP
        // fires. This is the standard DAP hook for transitioning a BP from
        // "unverified" to "verified": even if our initial setBreakpoints
        // response or earlier custom/syncBreakpoints event was missed/lost, the hit
        // itself proves the BP is valid, and this event lets VSCode mark it
        // as verified.
        if (bpId != 0) {
            val effectiveLine = when {
                loc.lineNumber() >= 1 -> loc.lineNumber()
                meta != null -> meta.line
                else -> 1
            }
            val hitSourcePath = entry?.displayPath ?: meta?.filePath?.let { displayPathFor(it) }
            sendEvent(
                "breakpoint",
                DtoTypes.breakpointEvent(bpId, true, effectiveLine, hitSourcePath, null)
            )
            Logging.info("[Server] BP id=$bpId 命中, 已推送 verified=true event")
        } else {
            // BP fired but we can't find it in the manager. Could be:
            //   - race with a clearBreakpointsForFile during source-dir change
            //   - stale request still enabled in the VM
            //   - a duplicate event for an already-removed BP
            // Either way: DO NOT send a stopped event (that would confuse
            // VSCode into thinking the user is paused) — just release the
            // suspended thread and move on.
            Logging.warn(
                "[Server] BP 命中但找不到 adapter id (line=${loc.lineNumber()} fqn=$fqn), 释放线程"
            )
            set.resume()
            return
        }

        if (meta?.isLog == true) {
            val frame = thread.frame(0)
            val formatted = exprEvaluator.evaluate(meta.logMessage!!, frame)
            sendEvent("output", DtoTypes.output("console", "[${meta.filePath}:${meta.line}] $formatted\n"))
            // Logpoint: emit the message but don't stop — release the thread
            // via the EventSet so JDI's queue doesn't keep re-firing the
            // same event.
            set.resume()
            return
        }

        if (meta?.isConditional == true) {
            val frame = thread.frame(0)
            val result = exprEvaluator.evaluateExpression(meta.condition!!, frame)
            if (!isTruthy(result)) {
                Logging.debug("[Server] 条件不满足, 跳过停止: ${meta.condition}")
                // Critical fix: use set.resume() (not thread.resume()) so the
                // EventSet is fully released. Otherwise the next
                // queue.remove() may either block or re-deliver the same
                // suspended set, causing the verified/stopped-event flood
                // observed in tight-loop conditional BPs.
                set.resume()
                return
            }
        }

        val stopped = DtoTypes.stopped("breakpoint", thread.uniqueID().toInt())
        if (entry != null) {
            val sourcePath = entry.path
            val sourceName = sourceBasename(sourcePath).takeIf { it.isNotEmpty() } ?: sourcePath
            val effectiveLine = when {
                loc.lineNumber() >= 1 -> loc.lineNumber()
                meta != null -> meta.line
                else -> 1
            }
            stopped.add("source", DtoTypes.source(sourcePath, sourceName))
            stopped.addProperty("line", effectiveLine)
            stopped.addProperty("column", 1)
        } else {
            Logging.warn("[Server] 未找到 FQN 对应的源: $fqn")
            val effectiveLine = if (loc.lineNumber() >= 1) loc.lineNumber() else (meta?.line ?: 1)
            stopped.addProperty("line", effectiveLine)
            stopped.addProperty("column", 1)
        }
        sendEvent("stopped", stopped)
        // Set stays suspended; the user will issue continue / next / etc.
    }

    /**
     * Custom DAP request used by the extension to push down the full set of
     * source breakpoints after attach. VSCode's built-in `setBreakpoints` only
     * fires for files that are open in the editor at session start, so we
     * additionally iterate `vscode.debug.breakpoints` on the extension side
     * and replay them through this request to ensure all BPs are installed,
     * including those in non-open files.
     *
     * Args: { files: [ { source: { path: String }, breakpoints: [ { line, condition?, logMessage? } ] } ] }
     */
    /** Path to echo back to VSCode for a canonical file key. */
    private fun displayPathFor(filePath: String): String =
        displayPathByFile[filePath] ?: filePath

    private fun handleSyncBreakpoints(args: JsonObject): JsonObject {
        val files = args.get("files")?.asJsonArray ?: JsonArray()
        Logging.info("[Server] custom/syncBreakpoints 共 ${files.size()} 个文件")
        var total = 0
        var placedTotal = 0
        for (fileEl in files) {
            val fileObj = fileEl.asJsonObject
            val src = fileObj.get("source")?.asJsonObject ?: continue
            val rawPath = src.get("path")?.asString ?: continue
            val filePath = normalizeSourcePath(rawPath)
            if (filePath.isNotEmpty()) {
                displayPathByFile[filePath] = rawPath
            }
            val bps = fileObj.get("breakpoints")?.asJsonArray ?: continue

            // Lines the extension is currently aware of for this file.
            // We do NOT pre-filter here — every BP is acted on according to
            // its `enabled` flag, because that's how we distinguish the
            // three states (ACTIVE / DISABLED / REMOVED) without relying on
            // the destructive diff in handleSetBreakpoints.
            val requestedLines = HashSet<Int>()
            for (bpEl in bps) {
                val bp = bpEl.asJsonObject
                val line = bp.get("line")?.asInt ?: continue
                val condition = bp.get("condition")?.takeUnless { it.isJsonNull }?.asString
                val logMessage = bp.get("logMessage")?.takeUnless { it.isJsonNull }?.asString
                // `enabled` defaults to true when the field is absent so
                // existing senders stay backwards-compatible.
                val enabled = bp.get("enabled")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
                requestedLines.add(line)
                total++

                // Reuse id from the earlier setBreakpoints response (or a
                // prior sync that placed it). Allocate only for first-time
                // adds — DISABLED→ACTIVE reuses the same id, which keeps
                // VSCode's BP correlation stable across toggles.
                val bpId = bpIdRegistry
                    .getOrPut(filePath) { mutableMapOf() }
                    .getOrPut(line) { bpIdSeq.getAndIncrement() }

                if (!enabled) {
                    // DISABLED state: keep id, drop JDI. JDI has no notion
                    // of "disabled" and would fire regardless, so the BP
                    // must be torn out of the VM. The id stays so re-enable
                    // reuses it — VSCode sees the same id for the same
                    // editor BP across toggle cycles.
                    bpManager?.removeBreakpoint(filePath, line)
                    Logging.info(
                        "[Server] custom/syncBreakpoints: BP 已禁用, 移除 JDI 但保留 id: " +
                            "file=$filePath line=$line id=$bpId"
                    )
                    sendEvent(
                        "breakpoint",
                        DtoTypes.breakpointEvent(bpId, false, line, displayPathFor(filePath), "已禁用")
                    )
                    continue
                }

                // ACTIVE state: ensure JDI is installed. setBreakpointWithId
                // is idempotent — re-issuing for a line that already has a
                // live request short-circuits without touching the VM.
                val fqns = indexer.sourceIndex().resolveFqnsByFile(filePath)
                val (placed, reason) = tryPlaceBp(filePath, line, condition, logMessage)
                if (!placed) {
                    pendingJdiBps.add(PendingBp(bpId, filePath, line, condition, logMessage))
                }
                // The raw tryPlaceBp reason is the actionable signal here —
                // the event message below is only a coarse user-facing hint.
                Logging.info(
                    "[Server] custom/syncBreakpoints: file=$filePath line=$line id=$bpId " +
                        "placed=$placed reason=$reason"
                )

                val message = when {
                    placed -> null
                    fqns.isEmpty() -> "源文件未索引"
                    else -> "类尚未被 JVM 加载或该行不可放置: ${fqns.joinToString()}"
                }
                sendEvent("breakpoint", DtoTypes.breakpointEvent(bpId, placed, line, displayPathFor(filePath), message))
                if (placed) placedTotal++
            }

            // REMOVED state: lines that exist in bpIdRegistry but were NOT
            // included in the payload. The extension's pushBreakpointsForFiles
            // iterates vscode.debug.breakpoints — anything not there was
            // truly deleted by the user (e.removed), so the adapter-side id
            // AND the JDI request must both go away. Re-add at the same
            // line later will allocate a fresh id (matches VSCode's BP
            // lifecycle: each add gets its own vsId, and we mirror that
            // here so the same id never points at two different BPs).
            val existingLines = bpIdRegistry[filePath]?.keys?.toList() ?: emptyList()
            for (line in existingLines) {
                if (line !in requestedLines) {
                    val removedId = bpManager?.removeBreakpoint(filePath, line)
                    if (removedId != null) {
                        pendingJdiBps.removeAll { it.id == removedId }
                        bpIdRegistry[filePath]?.remove(line)
                        if (bpIdRegistry[filePath]?.isEmpty() == true) {
                            bpIdRegistry.remove(filePath)
                        }
                        Logging.info(
                            "[Server] custom/syncBreakpoints: 移除已删除的 BP: file=$filePath line=$line id=$removedId"
                        )
                        sendEvent(
                            "breakpoint",
                            DtoTypes.breakpointEvent(removedId, false, line, displayPathFor(filePath), "已删除")
                        )
                    }
                }
            }
        }
        Logging.info("[Server] custom/syncBreakpoints 完成: requested=$total placed=$placedTotal")
        return JsonObject().apply {
            addProperty("requested", total)
            addProperty("placed", placedTotal)
        }
    }

    private fun handleStep(stepEvent: com.sun.jdi.event.StepEvent, @Suppress("UNUSED_PARAMETER") set: com.sun.jdi.event.EventSet) {
        stepController?.clearActive()
        sendEvent("stopped", DtoTypes.stopped("step", stepEvent.thread().uniqueID().toInt()))
        // Set stays suspended; the user will issue continue / next / etc.
    }

    private fun isTruthy(result: String): Boolean = result !in setOf("null", "false", "0")

    private fun findThread(id: Int): ThreadReference? {
        return jdi?.getVm()?.allThreads()?.firstOrNull { (it.uniqueID().toInt()) == id }
    }

    private fun findFrame(frameId: Int): StackFrame? {
        val vm = jdi?.getVm() ?: return null
        val idx = frameIdToIndex[frameId] ?: return null
        val threadId = frameIdToThreadId[frameId]
        val targetThread = if (threadId != null) {
            findThread(threadId)
        } else {
            null
        }
        val candidates = if (targetThread != null) listOf(targetThread) else vm.allThreads().toList()
        for (t in candidates) {
            try {
                val frames = t.frames()
                if (idx in frames.indices) return frames[idx]
            } catch (e: Exception) {
                Logging.warn("[Server] 获取线程栈失败: ${t.name()} - ${e.message}")
            }
        }
        return null
    }

    private fun clearFrameRefs() {
        frameRefByReqId.clear()
        frameIdToIndex.clear()
        frameIdToThreadId.clear()
        reqFrameToFrameId.clear()
    }

    private fun sendResponse(seq: Int, command: String, success: Boolean, message: String?, body: JsonObject?) {
        val resp = JsonObject()
        resp.addProperty("seq", 1)
        resp.addProperty("type", "response")
        resp.addProperty("request_seq", seq)
        resp.addProperty("command", command)
        resp.addProperty("success", success)
        if (message != null) resp.addProperty("message", message)
        if (body != null) resp.add("body", body)
        writeJson(resp)
    }

    private fun sendEvent(event: String, body: JsonObject?) {
        val evt = JsonObject()
        evt.addProperty("seq", eventSeq.getAndIncrement())
        evt.addProperty("type", "event")
        evt.addProperty("event", event)
        if (body != null) evt.add("body", body)
        writeJson(evt)
        // Always log breakpoint events so we can see what id / verified
        // status we are pushing to VSCode. Other events stay at debug level.
        if (event == "breakpoint") {
            Logging.info("[Server] SEND breakpoint event: $evt")
        } else {
            Logging.debug("[Server] SEND event[$event]: $evt")
        }
    }

    private fun writeJson(msg: JsonObject) {
        transport.write(msg)
    }

    fun shutdown() {
        if (shutdown) return
        shutdown = true
        eventDispatcher?.stop()
        bpManager?.clearAll()
        clearFrameRefs()
        varFormatter.clear()
        jdi?.close()
        transport.close()
    }
}