package com.lyufeng.phoneserialport

import com.lyufeng.phoneserialport.core.WriteResult
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.serial.BoundedReceiveBuffer
import com.lyufeng.phoneserialport.serial.PendingWriteQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SerialQueueTest {
    @Test fun idleWaitsForDequeuedRequestsAndDriverCleanup() = runBlocking {
        val queue=PendingWriteQueue(1) {}
        val completion=queue.enqueue(byteArrayOf(1),AppSettings(),null)
        assertFalse(queue.isIdle)
        val task=queue.requests.receive()
        assertFalse(queue.isIdle)
        assertTrue(queue.activate(task))
        completion.complete(WriteResult(1))
        val idle=async(start=CoroutineStart.UNDISPATCHED) {queue.awaitIdle()}
        assertFalse(idle.isCompleted)
        queue.finish(task)
        withTimeout(1000) {idle.await()}
        assertTrue(queue.isIdle)
        queue.close()
    }
    @Test fun stoppingDequeuedRequestKeepsQueueBusyUntilActivationRejectsIt() = runBlocking {
        val queue=PendingWriteQueue(1) {}
        val completion=queue.enqueue(byteArrayOf(1),AppSettings(),null)
        val task=queue.requests.receive()
        queue.cancelPending()
        assertFalse(queue.isIdle)
        assertFalse(queue.activate(task))
        assertTrue(completion.isCancelled)
        assertTrue(queue.isIdle)
        queue.close()
    }
    @Test fun rejectedWriteDoesNotLeaveAnUnfinishedChildPromise() = runBlocking {
        var rejected=0
        val queue=PendingWriteQueue(0) {rejected+=it}
        withTimeout(2000) {
            coroutineScope {
                val result=queue.submit(byteArrayOf(1,2,3),AppSettings())
                assertEquals(0,result.confirmed);assertNotNull(result.error)
            }
        }
        assertEquals(3,rejected);queue.close()
    }
    @Test fun closingQueueSettlesPendingSendWithoutReplayingIt() = runBlocking {
        val queue=PendingWriteQueue(1) {}
        val pending=async {queue.submit(byteArrayOf(1),AppSettings())}
        yield();queue.close()
        val result=withTimeout(2000) {pending.await()}
        assertEquals(0,result.confirmed);assertNotNull(result.error)
        assertTrue(queue.requests.tryReceive().isClosed)
    }
    @Test fun queuedWriteOwnsBytesAndCancelledCallerCancelsItsRequest() = runBlocking {
        val queue=PendingWriteQueue(1) {}
        val bytes=byteArrayOf(1,2)
        val pending=async {queue.submit(bytes,AppSettings())}
        yield();bytes[0]=99
        val request=queue.requests.receive()
        assertArrayEquals(byteArrayOf(1,2),request.bytes)
        pending.cancelAndJoin();assertTrue(request.completion.isCancelled);queue.close()
    }
    @Test fun acceptedWriteReturnsOnlyItsOwnConfirmedProgress() = runBlocking {
        val queue=PendingWriteQueue(1) {}
        val pending=async {queue.submit(ByteArray(10),AppSettings())}
        yield();queue.requests.receive().completion.complete(WriteResult(3,IOException("partial")))
        assertEquals(3,pending.await().confirmed);queue.close()
    }
    @Test fun stoppingBetweenDequeueAndActivationRejectsTheOldRequestButAcceptsNewCommands() = runBlocking {
        val queue=PendingWriteQueue(2) {}
        val old=async(start=CoroutineStart.UNDISPATCHED) {queue.submit(byteArrayOf(1),AppSettings())}
        val stale=queue.requests.receive()
        queue.cancelPending()
        assertFalse(queue.activate(stale));old.join();assertTrue(old.isCancelled)
        val fresh=async(start=CoroutineStart.UNDISPATCHED) {queue.submit(byteArrayOf(2),AppSettings())}
        val request=queue.requests.receive();assertTrue(queue.activate(request))
        request.completion.complete(WriteResult(1));queue.finish(request)
        assertEquals(1,fresh.await().confirmed);queue.close()
    }
    @Test fun stoppingQueueCancelsBothTheActiveRequestAndAlreadyQueuedCommands() = runBlocking {
        val queue=PendingWriteQueue(2) {}
        val running=async(start=CoroutineStart.UNDISPATCHED) {queue.submit(byteArrayOf(1),AppSettings())}
        val request=queue.requests.receive();var stopped=false
        assertTrue(queue.activate(request) {stopped=true})
        val queued=async(start=CoroutineStart.UNDISPATCHED) {queue.submit(byteArrayOf(2),AppSettings())}
        queue.cancelPending();running.join();queued.join()
        assertTrue(stopped);assertTrue(running.isCancelled);assertTrue(queued.isCancelled)
        assertTrue(queue.requests.tryReceive().isFailure);queue.finish(request);queue.close()
    }
    @Test fun receiveBufferCopiesCallbackDataAndRetainsUnreadTail() {
        val buffer=BoundedReceiveBuffer(2)
        val source=byteArrayOf(1,2,3,4,5);buffer.offer(source,4);source.fill(99)
        val destination=ByteArray(3)
        assertEquals(3,buffer.read(destination,0));assertArrayEquals(byteArrayOf(1,2,3),destination)
        assertEquals(1,buffer.read(destination,0));assertEquals(4,destination[0].toInt())
        assertEquals(0,buffer.read(destination,0));buffer.close()
    }
    @Test fun receiveBufferCountsWholeRejectedPacketAndDoesNotOverwriteOldData() {
        val buffer=BoundedReceiveBuffer(1)
        buffer.offer(byteArrayOf(1,2),2);buffer.offer(byteArrayOf(3,4,5),3)
        assertEquals(3L,buffer.takeDropped());assertEquals(0L,buffer.takeDropped())
        val output=ByteArray(8);assertEquals(2,buffer.read(output,0));assertArrayEquals(byteArrayOf(1,2),output.copyOf(2));buffer.close()
    }
    @Test fun overflowBoundaryFollowsQueuedTailsAndMarksOnlyTheFirstPostGapRead() {
        val buffer=BoundedReceiveBuffer(1)
        buffer.offer(byteArrayOf(1,2),2)
        buffer.offer(byteArrayOf(3),1)
        assertEquals(1L,buffer.takeDropped())
        val output=ByteArray(1)
        assertEquals(1,buffer.read(output,0));assertEquals(1,output[0].toInt())
        assertFalse(buffer.takeDiscontinuity())
        buffer.offer(byteArrayOf(4,5),2)
        assertEquals(1,buffer.read(output,0));assertEquals(2,output[0].toInt())
        assertFalse(buffer.takeDiscontinuity())
        assertEquals(1,buffer.read(output,0));assertEquals(4,output[0].toInt())
        assertTrue(buffer.takeDiscontinuity());assertFalse(buffer.takeDiscontinuity())
        assertEquals(1,buffer.read(output,0));assertEquals(5,output[0].toInt())
        assertFalse(buffer.takeDiscontinuity())
        buffer.close()
    }
    @Test fun closeWakesAnAlreadyWaitingReader() = runBlocking {
        val buffer=BoundedReceiveBuffer(1)
        val entered=CompletableDeferred<Thread>()
        val reader=async(Dispatchers.IO) {
            entered.complete(Thread.currentThread())
            try {buffer.read(ByteArray(8),10000);false} catch(_: IOException) {true}
        }
        val thread=entered.await()
        withTimeout(2000) {while(thread.state != Thread.State.TIMED_WAITING) {check(!reader.isCompleted);delay(1)}}
        buffer.close()
        assertTrue(withTimeout(2000) {reader.await()})
    }
}
