package org.isoron.platform

actual class ThreadLocalState<T> actual constructor(initializer: () -> T) {
    private val state = ThreadLocal.withInitial(initializer)
    actual fun get(): T = state.get()
    actual fun clear() = state.remove()
}
