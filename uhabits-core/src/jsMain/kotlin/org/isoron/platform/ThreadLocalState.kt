package org.isoron.platform

actual class ThreadLocalState<T> actual constructor(private val initializer: () -> T) {
    private var state = initializer()
    actual fun get(): T = state
    actual fun clear() { state = initializer() }
}
