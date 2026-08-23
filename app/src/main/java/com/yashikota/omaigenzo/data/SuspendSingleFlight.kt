package com.yashikota.omaigenzo.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SuspendSingleFlight<K : Any, V> {
    private val mutex = Mutex()
    private val jobs = HashMap<K, CompletableDeferred<V>>()

    suspend fun run(key: K, cached: () -> V?, producer: suspend () -> V): V {
        cached()?.let { return it }
        var leader = false
        val result = mutex.withLock {
            cached()?.let { return it }
            jobs[key] ?: CompletableDeferred<V>().also {
                jobs[key] = it
                leader = true
            }
        }
        if (!leader) return result.await()

        return try {
            producer().also(result::complete)
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { jobs.remove(key, result) }
        }
    }
}
