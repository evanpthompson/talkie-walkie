package com.talkiewalkie.channel

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe half-duplex transmit lock.
 * Exactly one named holder at a time; compareAndSet semantics throughout.
 */
class HalfDuplexLock {
    private val holder = AtomicReference<String?>(null)

    /** Returns true only if the lock was uncontested and is now held by [name]. */
    fun acquire(name: String): Boolean = holder.compareAndSet(null, name)

    /** Returns true only if [name] was the current holder and the lock is now free. */
    fun release(name: String): Boolean = holder.compareAndSet(name, null)

    val current: String? get() = holder.get()
    val isHeld: Boolean  get() = holder.get() != null
}
