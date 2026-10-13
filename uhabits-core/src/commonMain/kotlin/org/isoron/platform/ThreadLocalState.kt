package org.isoron.platform

/** State confined to the synchronous caller thread, like a platform database session. */
expect class ThreadLocalState<T>(initializer: () -> T) {
    fun get(): T
    fun clear()
}
