package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import java.util.concurrent.atomic.AtomicInteger

class BreakpointManager(private val vm: VirtualMachine) {

    private val erm: EventRequestManager = vm.eventRequestManager()
    private val requestIdSeq = AtomicInteger(1)
    private val activeRequests = HashMap<Int, BreakpointRequest>()
    private val metaById = HashMap<Int, BreakpointMeta>()
    // Stable adapter-side BP id per (filePath, line). DAP `breakpoint` events
    // must include the same id that was returned in the corresponding
    // `setBreakpoints` response, otherwise VSCode cannot correlate the event
    // back to the user's UI breakpoint. Reusing ids across re-sets (e.g. when
    // `syncBreakpoints` runs after a class loads) keeps the correlation intact.
    private val pathLineToId = HashMap<String, MutableMap<Int, Int>>()
    // Reverse map: EventRequest → adapter id. Uses IdentityHashMap because
    // JDI's `Mirror.equals` is structural (class + VM), which means every
    // BreakpointRequest on the same VM reports as equal to every other one —
    // unusable for distinguishing requests. Reference identity is what we
    // actually want: JDI returns the same request wrapper per event, and
    // IdentityHashMap matches that exactly.
    private val requestToId = java.util.IdentityHashMap<EventRequest, Int>()

    init {
        // Enable class-prepare events globally so we can re-try placing any
        // pending BPs the moment a new class (especially an anonymous inner
        // class) shows up in the VM. Without this, BPs set on a source line
        // inside `new Runnable() { ... }` would never resolve if the user
        // paused BEFORE the runtime had a chance to load OuterClass$1.
        // ClassPrepareRequest's default suspend policy is SUSPEND_ALL — the
        // EventDispatcher must call set.resume() on every class-prepare
        // EventSet or the app freezes after the first class load.
        erm.createClassPrepareRequest().enable()
    }

    /**
     * Set a JDI BP using an adapter-side id supplied by the caller. The id
     * is recorded in `pathLineToId` so subsequent calls with the same
     * (file, line) reuse it instead of allocating a fresh one — this keeps
     * the adapter id stable across `setBreakpoints` and the later
     * `syncBreakpoints` replay, which VSCode needs to correlate
     * `breakpoint` events back to the UI BP.
     */
    fun setBreakpointWithId(
        bpId: Int,
        refType: ReferenceType,
        location: com.sun.jdi.Location,
        filePath: String,
        line: Int,
        condition: String?,
        logMessage: String?,
    ) {
        val lineMap = pathLineToId.getOrPut(filePath) { mutableMapOf() }
        lineMap[line] = bpId
        // If a JDI request already exists for this id, leave it alone
        // (condition/logMessage changes are not supported; user must remove
        // and re-add the BP to update them).
        if (activeRequests.containsKey(bpId)) return

        val req = erm.createBreakpointRequest(location)
        req.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD)
        // NOTE: JDI's BreakpointRequest does not support expression conditions.
        // The condition/logMessage parameters are stored in BreakpointMeta for
        // the EventDispatcher to evaluate after the BP fires (see handleJdiEvent).
        req.enable()
        metaById[bpId] = BreakpointMeta(filePath, line, condition, logMessage)
        activeRequests[bpId] = req
        requestToId[req] = bpId
        Logging.info("断点已设置: id=$bpId ${refType.name()}:${location.codeIndex()}")
    }

    fun setBreakpoint(
        refType: ReferenceType,
        location: com.sun.jdi.Location,
        filePath: String,
        line: Int,
        condition: String?,
        logMessage: String?
    ): Int {
        val lineMap = pathLineToId.getOrPut(filePath) { mutableMapOf() }
        val existing = lineMap[line]
        if (existing != null && activeRequests.containsKey(existing)) {
            // Already set for this (file, line) — reuse the id.
            return existing
        }

        val req = erm.createBreakpointRequest(location)
        req.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD)
        // NOTE: JDI's BreakpointRequest does not support expression conditions.
        // The condition/logMessage parameters are stored in BreakpointMeta for
        // the EventDispatcher to evaluate after the BP fires (see handleJdiEvent).
        req.enable()
        val id = requestIdSeq.getAndIncrement()
        lineMap[line] = id
        metaById[id] = BreakpointMeta(filePath, line, condition, logMessage)
        activeRequests[id] = req
        requestToId[req] = id
        Logging.info("断点已设置: id=$id ${refType.name()}:${location.codeIndex()}")
        return id
    }

    fun clearBreakpointsForFile(filePath: String) {
        val lineMap = pathLineToId.remove(filePath) ?: return
        for ((_, id) in lineMap) {
            activeRequests.remove(id)?.let {
                erm.deleteEventRequest(it)
                requestToId.remove(it)
            }
            metaById.remove(id)
        }
    }

    /**
     * Remove a single BP at (file, line). Used by the sync path to drop BPs
     * the user just disabled or deleted in the editor — without that, the
     * JDI side keeps the old request firing even though VSCode shows a
     * hollow glyph.
     * Returns the id that was freed (or null if nothing was registered).
     */
    fun removeBreakpoint(filePath: String, line: Int): Int? {
        val lineMap = pathLineToId[filePath] ?: return null
        val id = lineMap.remove(line) ?: return null
        activeRequests.remove(id)?.let {
            erm.deleteEventRequest(it)
            requestToId.remove(it)
        }
        metaById.remove(id)
        // Drop the filePath key entirely once empty so `bpIdRegistry[filePath]`
        // doesn't carry dangling empty maps.
        if (lineMap.isEmpty()) pathLineToId.remove(filePath)
        return id
    }

    fun clearAll() {
        activeRequests.values.forEach {
            erm.deleteEventRequest(it)
            requestToId.remove(it)
        }
        activeRequests.clear()
        metaById.clear()
    }

    fun findMeta(event: BreakpointEvent): BreakpointMeta? {
        val id = requestToId[event.request()] ?: return null
        return metaById[id]
    }

    fun findIdForRequest(req: com.sun.jdi.request.EventRequest): Int? {
        return requestToId[req]
    }

    fun resolveReferenceType(fqn: String): List<ReferenceType> = vm.classesByName(fqn)

    /**
     * Resolve every ReferenceType that might own a location at (filePath, line).
     * Includes the indexed classes AND their anonymous/named inner siblings
     * (OuterClass$1, OuterClass$InnerName, OuterClass$1$1, ...). For each,
     * find a Location at `line` whose source filename matches `filePath`.
     *
     * Anonymous inner classes are compiled into a SEPARATE class file whose
     * methods are NOT visible in `enclosingClass.allMethods()` — they're
     * only reachable via `vm.allClasses()` filtered by the
     * `${enclosingClass.fqn}$` prefix. Without this, line-50 BPs inside
     * `new Runnable() { void run() { ... } }` (compiled to OuterClass$1)
     * silently never match, even when the class IS already loaded.
     *
     * Source filename verification (`location.sourceName() == filePath's
     * basename`) protects against the line number being shared with a
     * sibling inner class that happens to live in a different source file
     * (e.g. if the project compiled multiple top-level classes to one
     * file via `package-info.java` shenanigans).
     *
     * `hintType` short-circuits the `vm.allClasses()` walk when called from
     * the class-prepare handler — we already know the freshly-loaded class
     * exactly, so checking it directly is O(1) vs O(N) for the broad scan.
     * Caller passes `null` from non-class-prepare paths (setBreakpoints,
     * syncBreakpoints, drainPendingBps) where we DO need the broad scan.
     */
    fun findLocationsForLine(
        filePath: String,
        line: Int,
        indexedFqns: List<String>,
        hintType: ReferenceType? = null,
    ): List<Pair<ReferenceType, Location>> {
        val sourceFileName = filePath.substringAfterLast(java.io.File.separatorChar)
        val results = mutableListOf<Pair<ReferenceType, Location>>()
        val seen = HashSet<ReferenceType>()

        // 1. Indexed FQNs directly.
        for (fqn in indexedFqns) {
            for (rt in vm.classesByName(fqn)) {
                if (!seen.add(rt)) continue
                findLocationInSource(rt, line, sourceFileName)?.let { results.add(rt to it) }
            }
        }

        // 2. Hint class — the ReferenceType that just got prepared (or any
        //    other class the caller has already resolved). Checking it
        //    directly is the cheapest possible lookup and avoids the
        //    vm.allClasses() walk in the common case.
        if (hintType != null && seen.add(hintType)) {
            findLocationInSource(hintType, line, sourceFileName)?.let { results.add(hintType to it) }
        }

        // 3. Inner / anonymous siblings — only when we actually need them.
        //
        //    The two skip conditions both guard against spending O(N) on a
        //    broad vm.allClasses() walk when we already know the answer:
        //
        //    a) `results.isNotEmpty()` — the BP's line lives in the indexed
        //       class itself (the overwhelmingly common case). Anonymous /
        //       inner sibling lookup would be redundant.
        //
        //    b) `!outerClassLoaded` — none of the indexed FQNs have been
        //       loaded yet. Anonymous inner classes can't exist without
        //       their enclosing class, so a sibling walk would be wasted.
        //       This saves a lot of work at attach time when BPs are queued
        //       for files whose classes haven't been touched yet.
        //
        //    When we DO run the broad walk, we stop on the first match
        //    because the caller takes `matches.first()` anyway — no point
        //    spending more JNI calls to enumerate additional matches.
        if (results.isEmpty() && hintType == null) {
            val innerPrefixes = indexedFqns.map { "$it\$" }
            if (innerPrefixes.isNotEmpty()) {
                val outerClassLoaded = indexedFqns.any { fqn ->
                    vm.classesByName(fqn).isNotEmpty()
                }
                if (outerClassLoaded) {
                    for (rt in vm.allClasses()) {
                        val name = rt.name()
                        if (innerPrefixes.none { name.startsWith(it) }) continue
                        if (!seen.add(rt)) continue
                        findLocationInSource(rt, line, sourceFileName)
                            ?.also { results.add(rt to it) }
                        // Stop at first match — caller only consumes the
                        // first one, and the broad walk is the bottleneck
                        // we are trying to shrink.
                        if (results.isNotEmpty()) break
                    }
                }
            }
        }

        return results
    }

    private fun findLocationInSource(
        rt: ReferenceType,
        line: Int,
        expectedSourceName: String,
    ): Location? {
        for (m in rt.allMethods()) {
            try {
                for (loc in m.locationsOfLine(line)) {
                    val src = try {
                        loc.sourceName()
                    } catch (_: AbsentInformationException) {
                        continue
                    }
                    if (src == expectedSourceName) return loc
                }
            } catch (_: AbsentInformationException) {
                // No line table — skip this method.
            }
        }
        return null
    }

    companion object {
        /**
         * Legacy single-class lookup. Prefer `findLocationsForLine` — that one
         * also walks anonymous inner siblings and verifies the source filename
         * before returning a match.
         */
        fun findLocationForLine(refType: ReferenceType, line: Int): Location? {
            for (m in refType.allMethods()) {
                try {
                    val locs: List<Location> = m.locationsOfLine(line)
                    if (locs.isNotEmpty()) return locs[0]
                } catch (_: AbsentInformationException) { }
            }
            return null
        }
    }

    data class BreakpointMeta(
        val filePath: String,
        val line: Int,
        val condition: String?,
        val logMessage: String?
    ) {
        val isLog: Boolean get() = !logMessage.isNullOrEmpty()
        val isConditional: Boolean get() = !condition.isNullOrEmpty()
    }
}