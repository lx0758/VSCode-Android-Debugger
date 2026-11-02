package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.source.SourceIndex
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.StackFrame

enum class Language { JAVA, KOTLIN }

/**
 * Picks the expression frontend for an evaluation frame: the source index
 * (authoritative full paths from the TS indexer) wins, then the debug
 * info source name, defaulting to Java.
 */
internal object LanguageDetector {

    fun detectLanguage(frame: StackFrame, sourceIndex: SourceIndex): Language {
        val loc = frame.location()
        val sourceName = try {
            loc.sourceName()
        } catch (e: AbsentInformationException) {
            null
        }
        return detectLanguage(
            declaringFqn = loc.declaringType().name(),
            sourceName = sourceName,
            sourceIndex = sourceIndex,
        )
    }

    fun detectLanguage(declaringFqn: String, sourceName: String?, sourceIndex: SourceIndex): Language {
        sourceIndex.resolveByFqn(declaringFqn)?.path?.let { fileLanguage(it)?.let { l -> return l } }
        sourceName?.let { fileLanguage(it)?.let { l -> return l } }
        return Language.JAVA
    }

    private fun fileLanguage(path: String): Language? = when {
        path.endsWith(".kt") || path.endsWith(".kts") -> Language.KOTLIN
        path.endsWith(".java") -> Language.JAVA
        else -> null
    }
}
