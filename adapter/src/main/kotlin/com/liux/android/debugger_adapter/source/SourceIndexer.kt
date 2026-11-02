package com.liux.android.debugger_adapter.source

import com.google.gson.JsonArray

/**
 * Typed input entry for [SourceIndexer] — the wire shape of one
 * `custom/syncSourceIndex` item: `{fqn, file, sourceUri?, imports?}`.
 */
data class SourceEntryInput(
    val fqn: String,
    val file: String,
    val sourceUri: String? = null,
    val imports: List<String> = emptyList(),
)

class SourceIndexer {
    val index = SourceIndex()

    fun set(entries: List<SourceEntryInput>) {
        index.clear()
        add(entries)
    }

    fun add(entries: List<SourceEntryInput>) {
        for (entry in entries) {
            if (entry.fqn.isEmpty() || entry.file.isEmpty()) continue
            val packageName = entry.fqn.substringBeforeLast('.', "")
            index.add(
                FileEntry(
                    path = entry.file,
                    packageName = packageName,
                    fqns = listOf(entry.fqn),
                    uri = entry.sourceUri,
                    imports = entry.imports,
                )
            )
        }
    }

    fun sourceIndex(): SourceIndex = index

    companion object {
        /**
         * Parse the wire shape `Array<{fqn, file, sourceUri?, imports?}>`.
         * Entries without an `imports` field (legacy TS payloads) fall back
         * to an empty list — additive field, backward compatible.
         */
        fun parseEntries(arr: JsonArray): List<SourceEntryInput> {
            return (0 until arr.size()).map { i ->
                val obj = arr[i].asJsonObject
                SourceEntryInput(
                    fqn = obj.get("fqn")?.takeUnless { it.isJsonNull }?.asString ?: "",
                    file = obj.get("file")?.takeUnless { it.isJsonNull }?.asString ?: "",
                    sourceUri = obj.get("sourceUri")?.takeUnless { it.isJsonNull }?.asString,
                    imports = obj.get("imports")?.takeUnless { it.isJsonNull }
                        ?.asJsonArray
                        ?.map { it.asString }
                        ?: emptyList(),
                )
            }
        }
    }
}
