package dev.pocketbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsStoreTest {

    @Test
    fun testGeneratePasswordLengthAndEntropy() {
        val pass = CredentialsStore.generatePassword()

        // Brief 5.4: Mandatory random password (>= 24 chars) generated at install
        assertTrue("Password length must be >= 24", pass.length >= 24)
        assertEquals(32, pass.length)

        // All characters must come from the defined unambiguous alphabet
        for (char in pass) {
            assertTrue("Char '$char' must be in ALPHABET", CredentialsStore.ALPHABET.contains(char))
        }

        // Two generated passwords must be distinct
        val pass2 = CredentialsStore.generatePassword()
        assertNotEquals(pass, pass2)
    }

    @Test
    fun testDefaults() {
        assertEquals("opencode", CredentialsStore.USERNAME_DEFAULT)
        assertTrue(CredentialsStore.ALPHABET.length > 60)
    }
}
