package com.liux.android.debugger_adapter.util

import com.google.gson.JsonObject

object DtoTypes {
    fun initializeResult(): JsonObject = JsonObject().apply {
        // Breakpoint capabilities.
        addProperty("supportsConditionalBreakpoints", true)
        addProperty("supportsLogPoints", true)
        // supportsBreakpointOptions is the umbrella flag for the BP-level
        // options (condition/logMessage/hitCondition) set via setBreakpoints
        // args. Conditional + logPoint are already gated by their own flags
        // above, but VSCode also uses this to render the BP editor UI.
        addProperty("supportsBreakpointOptions", true)
        addProperty("supportsFunctionBreakpoints", false)
        addProperty("supportsHitConditionalBreakpoints", true)
        addProperty("supportsDataBreakpoints", false)
        // Stack-frame / stepping capabilities.
        addProperty("supportsStepBack", false)
        addProperty("supportsStepInTargetsRequest", false)
        addProperty("supportsGotoTargetsRequest", false)
        // Variable inspection capabilities.
        addProperty("supportsVariableType", true)
        addProperty("supportsVariablePaging", false)
        addProperty("supportsRunInTerminalRequest", false)
        // Expression / mutation capabilities. supportsSetVariable was the
        // missing piece that hid the "Set Value" right-click item. The
        // other two make sure VSCode actually sends evaluate / setExpression
        // requests to us (otherwise it routes to its own placeholder).
        addProperty("supportsEvaluate", true)
        addProperty("supportsSetExpression", true)
        addProperty("supportsSetVariable", true)
        // Lifecycle capabilities.
        addProperty("supportsConfigurationDone", true)
        addProperty("supportsTerminateRequest", true)
        addProperty("supportsRestartRequest", false)
        addProperty("supportsCancelRequest", true)
        // Exception breakpoints — we don't implement ExceptionRequest in
        // JDI yet, so leave filters empty and turn the umbrella flag off.
        addProperty("supportsExceptionFilterOptions", false)
        addProperty("supportsExceptionOptions", false)
        add("exceptionBreakpointFilters", com.google.gson.JsonArray())
        // Source listing — backed by SourceIndex, see handleLoadedSources.
        addProperty("supportsLoadedSourcesRequest", true)
        addProperty("supportsModulesRequest", false)
        // Memory / disassembly — not implemented.
        addProperty("supportsDisassembleRequest", false)
        addProperty("supportsReadMemoryRequest", false)
        addProperty("supportsWriteMemoryRequest", false)
        addProperty("supportsShowDataPreview", false)
    }

    fun breakpoint(id: Int, verified: Boolean, line: Int) = JsonObject().apply {
        addProperty("id", id)
        addProperty("verified", verified)
        addProperty("line", line)
    }

    /**
     * Body of a DAP `breakpoint` event. Used by handlers that set breakpoints
     * outside of the standard `setBreakpoints` request (e.g. `syncBreakpoints`)
     * to push verification status back to VSCode so it can render the BP as
     * verified (filled circle) instead of unverified (hollow circle).
     *
     * The `id` must match the id previously returned in the corresponding
     * `setBreakpoints` response — VSCode uses it to correlate the event back
     * to the UI breakpoint. Without a matching id the event is silently
     * ignored.
     */
    fun breakpointEvent(id: Int, verified: Boolean, line: Int, sourcePath: String?, message: String?): JsonObject {
        val bp = JsonObject().apply {
            addProperty("id", id)
            addProperty("verified", verified)
            addProperty("line", line)
            if (sourcePath != null) {
                add("source", JsonObject().apply {
                    val baseName = sourcePath.substringAfterLast(java.io.File.separatorChar)
                        .takeIf { it.isNotEmpty() } ?: sourcePath
                    addProperty("name", baseName)
                    addProperty("path", sourcePath)
                })
            }
            if (message != null) addProperty("message", message)
        }
        return JsonObject().apply {
            addProperty("reason", "changed")
            add("breakpoint", bp)
        }
    }

    fun source(path: String, name: String) = JsonObject().apply {
        addProperty("name", name)
        addProperty("path", path)
    }

    fun stopped(reason: String, threadId: Int) = JsonObject().apply {
        addProperty("reason", reason)
        addProperty("threadId", threadId)
        addProperty("allThreadsStopped", false)
        addProperty("preserveFocusHint", false)
    }

    fun thread(id: Long, name: String) = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
    }

    fun stackFrame(id: Int, name: String, line: Int, sourcePath: String?, sourceName: String?) = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        // DAP line numbers are 1-based. JDI returns -1 when line info is
        // missing (e.g. Android R8 / ProGuard stripped debug_info); VSCode
        // rejects 0 or negative lines with "Invalid arguments" when opening
        // the source editor. Fall back to 1 to keep the editor from crashing;
        // a wrong line is a lesser evil than a hard error.
        addProperty("line", if (line < 1) 1 else line)
        // DAP spec marks column optional, but VSCode's setSelection path
        // has been observed to throw "Invalid arguments" when the column is
        // not explicitly set on stackFrame. Pin to 1 to be safe.
        addProperty("column", 1)
        if (sourcePath != null) {
            add("source", JsonObject().apply {
                addProperty("name", sourceName ?: sourcePath)
                addProperty("path", sourcePath)
            })
        }
    }

    fun scope(name: String, variablesReference: Int) = JsonObject().apply {
        addProperty("name", name)
        addProperty("variablesReference", variablesReference)
        addProperty("expensive", false)
        addProperty("presentationHint", "locals")
    }

    fun variable(name: String, value: String, variablesReference: Int, type: String?) = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value)
        addProperty("variablesReference", variablesReference)
        if (type != null) addProperty("type", type)
    }

    fun output(category: String, output: String) = JsonObject().apply {
        addProperty("category", category)
        addProperty("output", output)
    }
}