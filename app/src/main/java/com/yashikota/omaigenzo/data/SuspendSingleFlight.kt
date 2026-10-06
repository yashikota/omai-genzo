package com.yashikota.omaigenzo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Deduplicates concurrent work per key.
 *
 * The producer runs on [scope], not on the first caller, so cancelling one caller (for example a
 * stale prefetch) never fails callers that joined the same flight later. The work is cancelled only
 * once the last waiter has left, so abandoned speculative work stops as early as possible.
 * [scope] must use a [SupervisorJob] so a failing producer is reported to its waiters instead of
 * tearing the scope down.
 */
class SuspendSingleFlight<K : Any, V>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Flight<V>(val deferred: Deferred<V>) {
        var waiters = 0
    }

    private val mutex = Mutex()
    private val flights = HashMap<K, Flight<V>>()

    suspend fun run(key: K, cached: () -> V?, producer: suspend () -> V): V {
        cached()?.let { return it }
        var joined = false
        val flight = mutex.withLock {
            cached()?.let { return it }
            val existing = flights[key]
            joined = existing != null
            val flight = existing ?: Flight(scope.async { producer() }).also { flights[key] = it }
            flight.waiters++
            flight
        }
        if (joined) {
            PerfLogger.event("single_flight_join", "\"key\":\"${PerfLogger.escape(key.toString())}\"")
        }
        try {
            return flight.deferred.await()
        } finally {
            release(key, flight)
        }
    }

    private suspend fun release(key: K, flight: Flight<V>) {
        // Must complete even when the caller is already cancelled, otherwise waiters leak.
        withContext(NonCancellable) {
            mutex.withLock {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.deferred.cancel()
                }
            }
        }
    }
}
