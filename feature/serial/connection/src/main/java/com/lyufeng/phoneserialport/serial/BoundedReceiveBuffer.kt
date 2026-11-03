package com.lyufeng.phoneserialport.serial

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Copies callback-owned bytes and preserves unread tails without hiding overflow. */
internal class BoundedReceiveBuffer(capacity: Int) {
    private data class Packet(val bytes: ByteArray, val gapBefore: Boolean)
    private val queue=ArrayBlockingQueue<Packet>(capacity)
    private val offerLock=Any()
    private var pendingGap=false
    private val readGap=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val dropped=AtomicLong()
    private var current=byteArrayOf()
    private var offset=0
    fun offer(bytes: ByteArray,length: Int): Unit = synchronized(offerLock) {
        require(length in 0..bytes.size) {"Invalid receive callback length"}
        if(length == 0 || closed.get()) return@synchronized
        if(queue.offer(Packet(bytes.copyOf(length),pendingGap))) pendingGap=false
        else {pendingGap=true;dropped.addAndGet(length.toLong())}
    }
    fun takeDropped(): Long = dropped.getAndSet(0)
    fun takeDiscontinuity(): Boolean = readGap.getAndSet(false)
    @Synchronized fun read(destination: ByteArray,waitMs: Long = 100): Int {
        require(destination.isNotEmpty()) {"Read buffer is empty"}
        if(closed.get()) throw IOException("Receive buffer closed")
        if(offset == current.size) {
            val packet=queue.poll(waitMs,TimeUnit.MILLISECONDS)
            current=packet?.bytes ?: byteArrayOf()
            if(packet?.gapBefore == true) readGap.set(true)
            offset=0
        }
        if(closed.get()) throw IOException("Receive buffer closed")
        val length=minOf(destination.size,current.size-offset)
        current.copyInto(destination,0,offset,offset+length)
        offset+=length
        return length
    }
    fun close() {
        if(closed.compareAndSet(false,true)) {queue.clear();queue.offer(Packet(byteArrayOf(),false))}
    }
}
