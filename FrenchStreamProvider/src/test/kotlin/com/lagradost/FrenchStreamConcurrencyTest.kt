package com.lagradost

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchStreamConcurrencyTest {
    @Test
    fun startsAllCardLookupsWithoutSequentialBatches() = runBlocking {
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val job = async {
            FrenchStreamCardEnrichment.forEachConcurrent((1..18).toList()) {
                started.incrementAndGet()
                release.await()
            }
        }

        withTimeout(1_000L) {
            while (started.get() < 18) yield()
        }
        release.complete(Unit)
        job.await()

        assertEquals(18, started.get())
        assertTrue(FrenchStreamCardEnrichment.TIMEOUT_MS <= 1_500L)
    }
}
