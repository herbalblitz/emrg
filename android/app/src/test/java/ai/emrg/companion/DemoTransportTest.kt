package ai.emrg.companion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoTransportTest {
    @Test
    fun sendReturnsExplicitDemoOnlyResponse() = runTest {
        val transport = DemoTransport()

        val response = transport.send("hello")

        assertEquals(
            "Demo response received. Host connection and real agent execution are not yet enabled.",
            response
        )
    }
}
