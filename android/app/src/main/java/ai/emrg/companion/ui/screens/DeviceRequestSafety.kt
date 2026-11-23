package ai.emrg.companion.ui.screens

import kotlinx.coroutines.CancellationException

internal const val DEVICE_REQUEST_FAILURE_MESSAGE =
    "Could not complete the device request. Check the host connection and try again."

internal sealed class DeviceRequestResult<out T> {
    data class Success<T>(val value: T) : DeviceRequestResult<T>()
    object Failed : DeviceRequestResult<Nothing>()
}

/**
 * Convert ordinary transport/request failures into a safe UI state while
 * preserving coroutine cancellation so leaving the screen never gets
 * mistaken for a failed host request.
 */
internal suspend fun <T> runDeviceRequest(
    request: suspend () -> T,
): DeviceRequestResult<T> = try {
    DeviceRequestResult.Success(request())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DeviceRequestResult.Failed
}
