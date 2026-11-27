package dev.arcdrift.mcp.transport

import dev.arcdrift.mcp.server.ArcDriftMcpServer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Standard newline-delimited JSON-RPC 2.0 transport over `stdin` / `stdout`
 * for integrating `arc mcp` with Claude Code, Cursor, Antigravity, and other MCP hosts.
 */
class StdioMcpTransport(
    private val server: ArcDriftMcpServer,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out
) {
    fun runLoop() {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

        while (true) {
            val line = reader.readLine() ?: break
            val responseJson = server.handleJsonRpcLine(line)
            if (responseJson != null) {
                writer.write(responseJson)
                writer.write("\n")
                writer.flush()
            }
        }
    }
}
