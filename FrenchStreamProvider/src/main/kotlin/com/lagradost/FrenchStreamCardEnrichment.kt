package com.lagradost

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal object FrenchStreamCardEnrichment {
    const val TIMEOUT_MS = 1_500L
    private val requestSlots = Semaphore(24)

    suspend fun <T> forEachConcurrent(items: List<T>, action: suspend (T) -> Unit) {
        coroutineScope {
            items.map { item ->
                async {
                    requestSlots.withPermit { action(item) }
                }
            }.awaitAll()
        }
    }
}
