package com.yashikota.omaigenzo.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

enum class PrefetchPriority {
    IMMEDIATE_NEXT,
    PREVIOUS,
    LOOKAHEAD,
}

data class PrefetchRequest(
    val index: Int,
    val priority: PrefetchPriority,
)

class PrefetchCoordinator(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(dispatcher)
    private var currentPrefetchJob: Job? = null

    private val _latestActiveIndex = AtomicInteger(0)
    val latestActiveIndex: Int
        get() = _latestActiveIndex.get()

    var onPrefetchRequested: (suspend (request: PrefetchRequest) -> Unit)? = null

    fun updateCurrentIndex(currentIndex: Int, totalSize: Int) {
        _latestActiveIndex.set(currentIndex)

        currentPrefetchJob?.cancel()

        if (totalSize <= 1) return

        val requests = mutableListOf<PrefetchRequest>()

        // 1. Next photo (Highest prefetch priority)
        val nextIdx = currentIndex + 1
        if (nextIdx < totalSize) {
            requests.add(PrefetchRequest(nextIdx, PrefetchPriority.IMMEDIATE_NEXT))
        }

        // 2. Previous photo (For instant undo/back)
        val prevIdx = currentIndex - 1
        if (prevIdx >= 0) {
            requests.add(PrefetchRequest(prevIdx, PrefetchPriority.PREVIOUS))
        }

        // 3. Ahead photo (+2)
        val aheadIdx = currentIndex + 2
        if (aheadIdx < totalSize) {
            requests.add(PrefetchRequest(aheadIdx, PrefetchPriority.LOOKAHEAD))
        }

        currentPrefetchJob = scope.launch {
            for (req in requests) {
                onPrefetchRequested?.invoke(req)
            }
        }
    }

    fun cancelAll() {
        currentPrefetchJob?.cancel()
    }
}
