package ai.emrg.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCredentialStoreTest {
    @Test
    fun acceptsSimpleScopedAliases() {
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("host-01"))
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("device.alpha_2"))
        assertTrue(AndroidKeystoreCredentialStore.isValidCredentialAlias("A"))
    }

    @Test
    fun rejectsEmptyLongOrUnsafeAliases() {
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias(""))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("a".repeat(65)))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("../host"))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("host:token"))
        assertFalse(AndroidKeystoreCredentialStore.isValidCredentialAlias("host with spaces"))
    }
}
