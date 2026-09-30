package au.ingo.betterattend.util

import kotlin.coroutines.cancellation.CancellationException

/** Like [runCatching], but lets coroutine cancellation propagate instead of reporting it as a failure. */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
