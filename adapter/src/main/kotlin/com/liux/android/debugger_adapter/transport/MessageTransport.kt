package com.liux.android.debugger_adapter.transport

import com.google.gson.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

interface MessageTransport {
    fun write(message: JsonObject)
    fun close()
}

private const val HEADER_PREFIX = "Content-Length:"

private fun writeFramed(out: OutputStream, json: String) {
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
    synchronized(out) {
        out.write(header)
        out.write(bytes)
        out.flush()
    }
}

class StdioTransport : MessageTransport {
    override fun write(message: JsonObject) {
        writeFramed(System.out, message.toString())
    }

    override fun close() {
    }
}

class TcpTransport(private val socket: Socket) : MessageTransport {
    override fun write(message: JsonObject) {
        writeFramed(socket.getOutputStream(), message.toString())
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

fun openStdioStream(): InputStream = System.`in`

fun openTcpStream(socket: Socket): InputStream = socket.getInputStream()

fun readMessages(
    input: InputStream,
    onMessage: (String) -> Unit,
    onError: (String, Throwable) -> Unit,
) {
    while (true) {
        val firstLine = readAsciiLine(input) ?: return
        if (firstLine.isBlank()) continue

        val json: String = if (firstLine.startsWith(HEADER_PREFIX, ignoreCase = true)) {
            var contentLength: Int? = parseContentLength(firstLine)
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                if (line.startsWith(HEADER_PREFIX, ignoreCase = true)) {
                    contentLength = parseContentLength(line)
                }
            }
            val len = contentLength
            if (len == null || len < 0) {
                onError(firstLine, IllegalStateException("missing or invalid Content-Length"))
                continue
            }
            val bytes = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(bytes, off, len - off)
                if (n == -1) return
                off += n
            }
            String(bytes, StandardCharsets.UTF_8)
        } else {
            firstLine
        }

        try {
            onMessage(json)
        } catch (e: Exception) {
            onError(json, e)
        }
    }
}

private fun parseContentLength(line: String): Int? =
    line.substringAfter(':').trim().toIntOrNull()

private fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b == -1) {
            return if (sb.isEmpty()) null else sb.toString()
        }
        if (b == '\n'.code) {
            if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                sb.deleteCharAt(sb.length - 1)
            }
            return sb.toString()
        }
        sb.append(b.toChar())
    }
}
