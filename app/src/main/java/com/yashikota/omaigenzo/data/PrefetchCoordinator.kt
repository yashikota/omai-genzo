package com.yashikota.omaigenzo.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
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

object PrefetchPlanner {
    /**
     * Photos to decode ahead of the user, most urgent first. [direction] is the way the user is
     * moving: the photo they just left is the least likely to be needed, so it goes last.
     */
    fun plan(currentIndex: Int, totalSize: Int, direction: Int, maxEntries: Int): List<PrefetchRequest> {
        if (totalSize <= 1 || maxEntries <= 0) return emptyList()
        val step = if (direction < 0) -1 else 1
        val requests = ArrayList<PrefetchRequest>(maxEntries)

        fun add(offset: Int, priority: PrefetchPriority) {
            val index = currentIndex + offset
            if (requests.size < maxEntries && index in 0 until totalSize) requests += PrefetchRequest(index, priority)
        }

        add(step, PrefetchPriority.IMMEDIATE_NEXT)
        add(step * 2, PrefetchPriority.LOOKAHEAD)
        add(step * 3, PrefetchPriority.LOOKAHEAD)
        add(-step, PrefetchPriority.PREVIOUS)
        return requests
    }
}

class PrefetchCoordinator(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    @Volatile var maxEntries: Int = 3,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentGeneration: Job? = null
    private var lastIndex = -1

    private val _latestActiveIndex = AtomicInteger(0)
    val latestActiveIndex: Int
        get() = _latestActiveIndex.get()

    var onPrefetchRequested: (suspend (request: PrefetchRequest) -> Unit)? = null

    /** Every call starts a new generation; work from older generations is cancelled and never runs. */
    fun updateCurrentIndex(currentIndex: Int, totalSize: Int) {
        _latestActiveIndex.set(currentIndex)
        currentGeneration?.cancel()

        val direction = if (lastIndex >= 0 && currentIndex < lastIndex) -1 else 1
        lastIndex = currentIndex

        val requests = PrefetchPlanner.plan(currentIndex, totalSize, direction, maxEntries)
        if (requests.isEmpty()) return

        val generation = SupervisorJob(scope.coroutineContext[Job])
        currentGeneration = generation
        // Launched in priority order, so a dispatcher with limited parallelism starts the most
        // urgent photo first while still decoding several at once.
        for (request in requests) {
            scope.launch(generation) {
                ensureActive()
                onPrefetchRequested?.invoke(request)
            }
        }
    }

    fun cancelAll() {
        currentGeneration?.cancel()
    }
}
