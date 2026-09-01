package com.liux.android.debugger_adapter.util

/**
 * Platform-independent path basename. The separator is chosen by the
 * PATH'S OWN flavor, never by the host OS: the adapter may run on any OS
 * while source keys carry the flavor of the filesystem they came from
 * (posix `/home/...` from a Remote-SSH workspace even on a Windows host,
 * `C:\...` from a local one).
 *
 * - Keys containing `/` are cut there. Absolute posix keys always contain
 *   one, so a legal-but-unusual `\` inside a Linux filename survives.
 * - Windows keys never contain `/` (reserved character on NTFS), so they
 *   fall through to the `\` branch.
 *
 * Cutting on the host's `File.separatorChar` instead would re-break the
 * original bug: a posix key on a Windows host would come back unsplit and
 * fail every `sourceName()` comparison.
 */
fun sourceBasename(path: String): String =
    if ('/' in path) path.substringAfterLast('/')
    else path.substringAfterLast('\\')
