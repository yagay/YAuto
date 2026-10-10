package com.yagay.yauto.core.runtime

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** One owner for active execution tracking; removes empty buckets after completion. */
internal class ExecutionJobRegistry {
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    fun track(key: String, job: Job) {
        jobs.compute(key) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet<Job>()).apply { add(job) }
        }
    }

    fun untrack(key: String, job: Job) {
        jobs.computeIfPresent(key) { _, existing ->
            existing.remove(job)
            existing.takeIf { it.isNotEmpty() }
        }
    }

    fun isRunning(key: String): Boolean = jobs[key]?.any { it.isActive } == true

    fun cancel(key: String): Boolean {
        val active = jobs[key]?.toList().orEmpty().filter { it.isActive }
        active.forEach(Job::cancel)
        return active.isNotEmpty()
    }

    fun cancelAll() {
        jobs.keys.toList().forEach(::cancel)
    }
}
