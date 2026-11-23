package ai.emrg.companion.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DeviceRequestSafetyTest {
    @Test
    fun successfulRequestPreservesItsValue() = runBlocking {
        assertEquals(
            DeviceRequestResult.Success("devices"),
            runDeviceRequest { "devices" },
        )
    }

    @Test
    fun ordinaryTransportFailureBecomesSafeFailureState() = runBlocking {
        val outcome = runDeviceRequest<String> {
            throw IOException("private transport detail")
        }

        assertSame(DeviceRequestResult.Failed, outcome)
        assertEquals(
            "Could not complete the device request. Check the host connection and try again.",
            DEVICE_REQUEST_FAILURE_MESSAGE,
        )
    }

    @Test
    fun coroutineCancellationIsRethrown() = runBlocking {
        val cancellation = CancellationException("screen disposed")
        try {
            runDeviceRequest<String> { throw cancellation }
            fail("cancellation must not be converted into a request failure")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
