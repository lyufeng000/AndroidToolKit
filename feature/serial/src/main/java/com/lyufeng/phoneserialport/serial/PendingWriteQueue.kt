package com.lyufeng.phoneserialport.serial

import com.lyufeng.phoneserialport.core.WriteResult
import com.lyufeng.phoneserialport.data.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext

internal data class QueuedWrite(val bytes: ByteArray,val settings: AppSettings,val completion: CompletableDeferred<WriteResult>,val generation: Long)

/** Each submission settles its child promise, including rejection and queue closure. */
internal class PendingWriteQueue(capacity: Int,private val onRejected: (Int)->Unit) {
    private val channel=Channel<QueuedWrite>(capacity)
    private val lock=Any()
    private var generation=0L
    private var closed=false
    private var active: QueuedWrite?=null
    private var stopActive: (() -> Unit)?=null
    // Keep dequeued requests here until the driver coroutine has finished its cleanup.
    private val inFlight=mutableSetOf<QueuedWrite>()
    private val idleWaiters=mutableListOf<CompletableDeferred<Unit>>()
    val requests: ReceiveChannel<QueuedWrite> get()=channel
    suspend fun submit(bytes: ByteArray,settings: AppSettings): WriteResult {
        return enqueue(bytes,settings,currentCoroutineContext()[Job]).await()
    }
    fun enqueue(bytes: ByteArray,settings: AppSettings,parent: Job?): CompletableDeferred<WriteResult> {
        val completion=CompletableDeferred<WriteResult>(parent)
        val accepted=synchronized(lock) {
            val task=QueuedWrite(bytes.copyOf(),settings,completion,generation)
            if(channel.trySend(task).isSuccess) {inFlight.add(task);true} else false
        }
        if(!accepted) {
            val failure=WriteResult(0,IllegalStateException("Transmit queue is full or closed"))
            try {onRejected(bytes.size)} finally {completion.complete(failure)}
        }
        return completion
    }
    val isIdle: Boolean get()=synchronized(lock) {inFlight.isEmpty()}
    suspend fun awaitIdle() {
        val completion=synchronized(lock) {
            if(inFlight.isEmpty()) null else CompletableDeferred<Unit>().also {idleWaiters.add(it)}
        }
        completion?.await()
    }
    private fun finishLocked(task: QueuedWrite) {
        inFlight.remove(task)
        if(active === task) {active=null;stopActive=null}
        if(inFlight.isEmpty()) {
            idleWaiters.forEach {it.complete(Unit)}
            idleWaiters.clear()
        }
    }
    fun activate(task: QueuedWrite,stop: ()->Unit = {}): Boolean = synchronized(lock) {
        when {
            closed -> {task.completion.complete(WriteResult(0,IllegalStateException("Port closed")));finishLocked(task);false}
            task.generation != generation || task.completion.isCancelled -> {task.completion.cancel(CancellationException("Send stopped"));finishLocked(task);false}
            else -> {active=task;stopActive=stop;true}
        }
    }
    fun finish(task: QueuedWrite) = synchronized(lock) {finishLocked(task)}
    fun cancelPending() {
        val stop=synchronized(lock) {
            generation++
            active?.completion?.cancel(CancellationException("Send stopped"))
            while(true) {val task=channel.tryReceive().getOrNull() ?: break;task.completion.cancel(CancellationException("Send stopped"));finishLocked(task)}
            stopActive
        }
        stop?.invoke()
    }
    fun close() {
        val stop=synchronized(lock) {
            closed=true;channel.close()
            while(true) {
                val task=channel.tryReceive().getOrNull() ?: break
                task.completion.complete(WriteResult(0,IllegalStateException("Port closed")))
                finishLocked(task)
            }
            stopActive
        }
        stop?.invoke()
    }
}
