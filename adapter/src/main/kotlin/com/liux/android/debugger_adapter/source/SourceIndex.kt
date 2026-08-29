package com.liux.android.debugger_adapter.source

data class FileEntry(
    val path: String,
    val packageName: String,
    val fqns: List<String>
) {
    val primaryFqn: String = fqns.first()
}

class SourceIndex {

    private val fqnToFile = HashMap<String, FileEntry>()
    private val fileToFqns = HashMap<String, MutableList<String>>()

    fun add(entry: FileEntry) {
        if (entry.fqns.isEmpty()) return
        fqnToFile[entry.primaryFqn] = entry
        val list = fileToFqns.getOrPut(entry.path) { mutableListOf() }
        list += entry.fqns
    }

    fun remove(path: String) {
        fileToFqns.remove(path)?.forEach { fqn ->
            fqnToFile.remove(fqn)
        }
    }

    fun resolveFqnsByFile(path: String): List<String> = fileToFqns[path] ?: emptyList()

    /**
     * Resolve a runtime FQN (the one JDI reports) to the source file the
     * user sees. The index is keyed on top-level class names only — the
     * source parser has no way to name `OuterClass$1` because anonymous
     * inner classes have no source-level identifier. The fallback strips
     * `$N` / `$InnerName` and looks up the enclosing class, which by
     * Java's compile rule shares the same .java file.
     *
     * Examples that hit this fallback:
     *   com.example.Outer$1          →  com.example.Outer     (anonymous)
     *   com.example.Outer$Inner       →  com.example.Outer     (named inner)
     *   com.example.Outer$1$2         →  com.example.Outer     (anon-in-anon)
     *   com.example.Outer$Inner$1     →  com.example.Outer     (anon-in-inner)
     *
     * Without this, `handleBreakpoint` cannot attach `source` / `line` to
     * the `stopped` event when execution pauses inside an anonymous inner
     * class, and VSCode silently has no line to highlight — the BP looks
     * unfired even though the JDI side hit it correctly.
     */
    fun resolveByFqn(fqn: String): FileEntry? {
        fqnToFile[fqn]?.let { return it }
        val dollarIdx = fqn.indexOf('$')
        if (dollarIdx > 0) {
            val outerFqn = fqn.substring(0, dollarIdx)
            fqnToFile[outerFqn]?.let { return it }
        }
        return null
    }

    fun resolveByPrimaryFqn(fqn: String): FileEntry? = resolveByFqn(fqn)

    fun entries(): Collection<FileEntry> = fqnToFile.values

    fun size(): Int = fqnToFile.size

    fun clear() {
        fqnToFile.clear()
        fileToFqns.clear()
    }
}