package dev.arcdrift.intellij.psi

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

/**
 * Executes [block] while strictly rethrowing IntelliJ [ProcessCanceledException]
 * and [CancellationException] so IDE read-action/inspection cancellation is never swallowed.
 */
internal inline fun <T> runPsiSafely(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (pce: ProcessCanceledException) {
        throw pce
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
