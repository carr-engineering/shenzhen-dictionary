package com.szdict.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Monotonic request generation (GLB-040). A result is applied only if the generation it was
 * started with is still current — cancellation alone is never relied on.
 */
class Generation {
    var current: Long = 0
        private set

    /** Starts a new request; every older generation becomes stale. */
    fun next(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}

/**
 * Latest-only runner for one operation family (GLB-040/041, ARCH-035): requests are conflated to
 * the newest, the in-flight one is cancelled, and a result is delivered to [onResult] only when
 * its generation is still current — so a slow or non-cooperative superseded operation can never
 * overwrite newer state. Not thread-safe: call [submit]/[invalidate] from the scope's (UI) thread;
 * [onResult] runs in [scope].
 *
 * Typing ten characters and backspacing them completes exactly one search — the last one.
 */
class LatestOnly<Req, Res>(
    private val scope: CoroutineScope,
    private val work: suspend (Req) -> Res,
    private val onResult: (request: Req, result: Result<Res>) -> Unit,
) {
    private val generation = Generation()
    private val channel = Channel<Pair<Long, Req>>(Channel.CONFLATED)
    private val job: Job = scope.launch {
        channel.receiveAsFlow().collectLatest { (gen, request) ->
            if (!generation.isCurrent(gen)) return@collectLatest
            val result = try {
                Result.success(work(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (generation.isCurrent(gen)) onResult(request, result)
        }
    }

    /** The generation of the newest submitted (or invalidated) request. */
    val currentGeneration: Long get() = generation.current

    /** Submits a request, superseding every earlier one. Returns its generation. */
    fun submit(request: Req): Long {
        val gen = generation.next()
        channel.trySend(gen to request)
        return gen
    }

    /**
     * Supersedes everything pending or in flight without starting new work — e.g. a blank query,
     * whose empty state is applied by the caller in the same frame (GLB-042).
     */
    fun invalidate(): Long = generation.next()

    fun close() {
        channel.close()
        job.cancel()
    }
}
