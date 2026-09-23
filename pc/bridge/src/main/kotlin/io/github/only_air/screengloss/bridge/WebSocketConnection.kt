package io.github.only_air.screengloss.bridge

import java.io.InputStream
import java.io.OutputStream

/**
 * A minimal RFC 6455 server endpoint, hand-rolled.
 *
 * The obvious alternative is a WebSocket library. Two reasons not to:
 *
 *  - **`bridge` is the module that must not constrain the build.** §7.3 gives
 *    `bridge` one job, and a dependency here is a dependency of the whole
 *    desktop app. The JDK already ships a complete HTTP server
 *    (`com.sun.net.httpserver`); the only thing it does not ship is the frame
 *    layer, and the frame layer for *server-to-browser notifications* is small.
 *  - **The traffic is one-directional and low-rate.** The panel polls nothing
 *    and streams download progress, capability changes and hotkey activations.
 *    That is server→client text frames and client→server control frames. No
 *    fragmentation, no extensions, no compression (a `permessage-deflate`
 *    implementation that is never exercised is a liability).
 *
 * What is deliberately *not* implemented, so a reader does not have to guess:
 * continuation frames (a client large enough to fragment is a bug we would want
 * to see), subprotocol negotiation, and any extension. A close handshake is
 * implemented because a panel that reloads must not leave the server writing
 * into a dead socket.
 */
class WebSocketConnection(private val out: OutputStream) {

    @Volatile
    private var open = true

    private val writeLock = Any()

    /** Send one text frame. Silently drops the message when the peer is gone. */
    fun send(text: String) {
        if (!open) return
        val payload = text.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            try {
                writeFrame(OP_TEXT, payload)
            } catch (_: Throwable) {
                open = false
            }
        }
    }

    fun close() {
        if (!open) return
        open = false
        synchronized(writeLock) {
            runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
        }
    }

    /**
     * Read frames until the peer closes. Returns when the connection ends.
     *
     * The server ignores the payload of client frames on purpose: the panel has
     * no command it may issue that the HTTP API does not already gate better,
     * and a second write path is a second thing to audit. A client frame is
     * only allowed to be a ping, a pong, or a close.
     */
    fun pump(input: InputStream) {
        try {
            while (open) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = ((input.read() shl 8) or input.read()).toLong()
                } else if (len == 127L) {
                    len = 0L
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                val mask = ByteArray(4)
                if (masked) readFully(input, mask, 4)
                val payload = ByteArray(len.toInt().coerceAtMost(MAX_FRAME))
                readFully(input, payload, payload.size)
                if (masked) for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }

                when (opcode) {
                    OP_PING -> synchronized(writeLock) { runCatching { writeFrame(OP_PONG, payload) } }
                    OP_CLOSE -> {
                        close()
                        break
                    }
                    else -> Unit // see the method comment
                }
            }
        } catch (_: Throwable) {
            // A closed browser tab is the normal end of this loop.
        } finally {
            open = false
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ArrayList<Byte>(payload.size + 10)
        header.add((0x80 or opcode).toByte())  // FIN + opcode
        when {
            payload.size < 126 -> header.add(payload.size.toByte())
            payload.size < 65536 -> {
                header.add(126.toByte())
                header.add((payload.size ushr 8).toByte())
                header.add((payload.size and 0xFF).toByte())
            }
            else -> {
                header.add(127.toByte())
                for (shift in 56 downTo 0 step 8) {
                    header.add(((payload.size.toLong() ushr shift) and 0xFF).toByte())
                }
            }
        }
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var read = 0
        while (read < n) {
            val k = input.read(buf, read, n - read)
            if (k < 0) throw java.io.EOFException("peer closed mid-frame")
            read += k
        }
    }

    private companion object {
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_FRAME = 1 shl 20
    }
}
