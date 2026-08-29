package com.liux.android.debugger_adapter

import com.liux.android.debugger_adapter.protocol.Logging
import com.liux.android.debugger_adapter.server.DapServer
import com.liux.android.debugger_adapter.transport.MessageTransport
import com.liux.android.debugger_adapter.transport.StdioTransport
import com.liux.android.debugger_adapter.transport.TcpTransport
import com.liux.android.debugger_adapter.transport.openStdioStream
import com.liux.android.debugger_adapter.transport.openTcpStream
import com.liux.android.debugger_adapter.transport.readMessages
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Main

fun main(args: Array<String>) {
    Logging.info("Adapter 启动")
    val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "dap-handler").apply { isDaemon = true }
    }

    val portArg = args.firstOrNull { it.startsWith("--port=") }
    val explicitPort = portArg?.removePrefix("--port=")?.toIntOrNull()

    // --jdwp-port=<port>: when set, the adapter ignores the JDWP port
    // carried in the DAP `attach` request (which, for toolbar Restart,
    // is the port from the OLD session's configuration and is stale)
    // and connects to this port instead. The extension sets this when
    // it knows the new adb forward is at a different port than the
    // one VSCode is sending in the new session's config.
    val jdwpPortArg = args.firstOrNull { it.startsWith("--jdwp-port=") }
    val jdwpPortOverride = jdwpPortArg?.removePrefix("--jdwp-port=")?.toIntOrNull()

    val initialIndex = loadInitialIndex(args)

    if (explicitPort != null) {
        runTcp(explicitPort, executor, initialIndex, jdwpPortOverride)
    } else {
        runStdio(executor, initialIndex, jdwpPortOverride)
    }
}

/**
 * Read pre-built FQN→file index entries written by the extension (see
 * `runAttachCommand` in `extension/src/commands/attach.ts`). Loading the
 * index BEFORE the adapter starts listening for DAP requests guarantees that
 * when VSCode fires its automatic `setBreakpoints` right after `attach`, the
 * adapter can resolve source paths to FQNs and return real breakpoint ids —
 * otherwise the BPs come back as id=0, verified=false, and stay "unverified"
 * for the rest of the session.
 */
private fun loadInitialIndex(args: Array<String>): List<Map<String, String>> {
    val indexFileArg = args.firstOrNull { it.startsWith("--index-file=") }
        ?: return emptyList()
    val path = indexFileArg.removePrefix("--index-file=")
    return try {
        val json = java.io.File(path).readText(Charsets.UTF_8)
        val arr = JsonParser.parseString(json).asJsonArray
        val list = ArrayList<Map<String, String>>(arr.size())
        for (el in arr) {
            val obj = el.asJsonObject
            list += mapOf(
                "fqn" to (obj.get("fqn")?.asString ?: ""),
                "file" to (obj.get("file")?.asString ?: ""),
            )
        }
        Logging.info("从 $path 加载了 ${list.size} 条索引")
        list
    } catch (e: Exception) {
        Logging.error("加载初始索引失败: $path", e)
        emptyList()
    } finally {
        runCatching { java.io.File(path).delete() }
    }
}

private fun runStdio(
    executor: ExecutorService,
    initialIndex: List<Map<String, String>>,
    jdwpPortOverride: Int?,
) {
    val transport: MessageTransport = StdioTransport()
    val server = DapServer(executor, transport, initialIndex, jdwpPortOverride)
    try {
        readMessages(
            openStdioStream(),
            onMessage = { text ->
                val msg = JsonParser.parseString(text).asJsonObject
                server.handleIncoming(msg)
            },
            onError = { text, e ->
                Logging.error("解析消息失败: $text", e)
            },
        )
    } catch (e: Exception) {
        Logging.error("stdin 读取异常", e)
    } finally {
        server.shutdown()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}

private fun runTcp(
    port: Int,
    executor: ExecutorService,
    initialIndex: List<Map<String, String>>,
    jdwpPortOverride: Int?,
) {
    val serverSocket = ServerSocket(port)
    Logging.info("Adapter TCP 模式, listening on 0.0.0.0:$port (jdwp-port=${jdwpPortOverride ?: "<from-attach-request>"})")
    System.err.println("READY port=$port")
    System.err.flush()

    while (!Thread.currentThread().isInterrupted) {
        val client = try {
            serverSocket.accept()
        } catch (e: Exception) {
            Logging.info("serverSocket.accept 关闭, 退出 accept 循环: ${e.message}")
            break
        }
        Logging.info("Adapter accepted from ${client.remoteSocketAddress}")
        val sessionExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "dap-handler").apply { isDaemon = true }
        }
        Thread({
            val transport: MessageTransport = TcpTransport(client)
            val server = DapServer(sessionExecutor, transport, initialIndex, jdwpPortOverride)
            try {
                readMessages(
                    openTcpStream(client),
                    onMessage = { text ->
                        val msg = JsonParser.parseString(text).asJsonObject
                        server.handleIncoming(msg)
                    },
                    onError = { text, e ->
                        Logging.error("解析消息失败: $text", e)
                    },
                )
            } catch (e: Exception) {
                Logging.error("tcp 读取异常", e)
            } finally {
                server.shutdown()
                sessionExecutor.shutdown()
                sessionExecutor.awaitTermination(2, TimeUnit.SECONDS)
                try { client.close() } catch (_: Exception) { }
            }
        }, "dap-client-${client.port}").apply { isDaemon = true }.start()
    }
    try { serverSocket.close() } catch (_: Exception) { }
    executor.shutdown()
    executor.awaitTermination(2, TimeUnit.SECONDS)
}
