package com.phonewatch.inspect

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus

/**
 * Blocking wrapper around an SSLEngine running on a plain socket's streams. One thread may read while
 * another writes (decrypt app -> encrypt upstream and back). [initial] holds bytes already read from the
 * socket (the ClientHello we peeked at to learn the server name).
 */
class TlsIo(private val engine: SSLEngine, private val input: InputStream, private val output: OutputStream, initial: ByteArray) {
    private var netIn: ByteBuffer = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, initial.size + 4096)).apply { put(initial); flip() }
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val writeLock = Any()
    private val readLock = Any()
    @Volatile var closed = false; private set

    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_UNWRAP -> synchronized(readLock) { unwrapOnce(handshaking = true) }
                HandshakeStatus.NEED_WRAP -> synchronized(writeLock) { wrap(ByteBuffer.allocate(0)) }
                HandshakeStatus.NEED_TASK -> runTasks()
                else -> return
            }
        }
    }

    private fun runTasks() { while (true) { val t = engine.delegatedTask ?: break; t.run() } }

    /** Reads more ciphertext from the socket into netIn (netIn is in read mode). */
    private fun fill() {
        netIn.compact()
        if (!netIn.hasRemaining()) netIn = ByteBuffer.allocate(netIn.capacity() * 2).apply { netIn.flip(); put(netIn) }
        val n = input.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
        if (n < 0) { netIn.flip(); throw EOFException("peer closed") }
        netIn.position(netIn.position() + n)
        netIn.flip()
    }

    /** One unwrap step; returns false on clean close. */
    private fun unwrapOnce(handshaking: Boolean): Boolean {
        appIn.compact()
        val r: SSLEngineResult = try { engine.unwrap(netIn, appIn) } finally { appIn.flip() }
        when (r.status) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> fill()
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
                bigger.put(appIn); bigger.flip(); appIn = bigger
            }
            SSLEngineResult.Status.CLOSED -> { closed = true; if (handshaking) throw EOFException("closed during handshake"); return false }
            else -> {}
        }
        if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
        if (!handshaking) while (engine.handshakeStatus == HandshakeStatus.NEED_WRAP) synchronized(writeLock) { wrap(ByteBuffer.allocate(0)) }
        return true
    }

    /** Reads decrypted bytes; -1 at end of stream. */
    fun read(buf: ByteArray): Int = synchronized(readLock) {
        while (!appIn.hasRemaining()) {
            if (closed) return -1
            try { if (!unwrapOnce(handshaking = false)) return -1 } catch (e: EOFException) { return -1 }
        }
        val n = minOf(buf.size, appIn.remaining())
        appIn.get(buf, 0, n)
        n
    }

    fun write(buf: ByteArray, off: Int, len: Int) = synchronized(writeLock) { wrap(ByteBuffer.wrap(buf, off, len)) }

    private fun wrap(src: ByteBuffer) {
        do {
            netOut.clear()
            val r = engine.wrap(src, netOut)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { netOut = ByteBuffer.allocate(netOut.capacity() * 2); continue }
                SSLEngineResult.Status.CLOSED -> { flushOut(); if (src.hasRemaining()) throw IOException("engine closed"); return }
                else -> {}
            }
            flushOut()
            if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
        } while (src.hasRemaining())
    }

    private fun flushOut() {
        netOut.flip()
        if (netOut.hasRemaining()) { output.write(netOut.array(), netOut.arrayOffset() + netOut.position(), netOut.remaining()); output.flush() }
    }

    fun close() {
        runCatching { synchronized(writeLock) { engine.closeOutbound(); wrap(ByteBuffer.allocate(0)) } }
    }
}
