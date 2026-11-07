package com.phonewatch.engine

import com.phonewatch.data.Conn
import com.phonewatch.data.Repo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Batches log writes from the packet thread into Room once a second, so the
 * packet path never waits on disk (battery + latency friendly).
 */
object ConnLogger {
    sealed class Op {
        class Insert(val c: Conn) : Op()
        class Bytes(val id: Long, val up: Long, val down: Long) : Op()
        class Host(val id: Long, val host: String, val company: String?, val cats: Int, val via: String, val blocked: Boolean, val cname: String? = null) : Op()
    }

    private val ch = Channel<Op>(Channel.UNLIMITED)
    private val ids = AtomicLong(System.currentTimeMillis() * 1000)
    @Volatile private var started = false

    fun nextId() = ids.incrementAndGet()
    fun send(op: Op) { ch.trySend(op) }

    fun start() {
        if (started) return
        started = true
        Repo.io.launch {
            val dao = Repo.db.conn()
            val batch = ArrayList<Op>(256)
            while (true) {
                batch.add(ch.receive())
                delay(1000)
                while (true) { val o = ch.tryReceive().getOrNull() ?: break; batch.add(o) }
                runCatching {
                    Repo.db.runInTransaction {
                        val inserts = ArrayList<Conn>()
                        for (op in batch) when (op) {
                            is Op.Insert -> inserts += op.c
                            is Op.Bytes -> { if (inserts.isNotEmpty()) { dao.insertAll(inserts); inserts.clear() }; dao.addBytes(op.id, op.up, op.down) }
                            is Op.Host -> { if (inserts.isNotEmpty()) { dao.insertAll(inserts); inserts.clear() }; dao.setHost(op.id, op.host, op.company, op.cats, op.via, op.blocked, op.cname) }
                        }
                        if (inserts.isNotEmpty()) dao.insertAll(inserts)
                    }
                }
                batch.clear()
            }
        }
    }
}
