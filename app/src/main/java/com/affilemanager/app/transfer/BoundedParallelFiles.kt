package com.affilemanager.app.transfer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** At most five owned workers, not one waiting job per file or per group member. */
internal suspend fun forEachFileBounded(count: Int, parallelism: Int, abort: () -> Unit, action: suspend (Int) -> Unit) = coroutineScope {
    require(count in 0..NearbySourcePreparer.MAX_FILES && parallelism in 1..5)
    val next = AtomicInteger()
    repeat(minOf(count, parallelism)) {
        launch {
            try {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= count) break
                    action(index)
                }
            } catch (failure: Throwable) { abort(); throw failure }
        }
    }
}
