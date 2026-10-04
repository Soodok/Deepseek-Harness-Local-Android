package app.dsh.mobile.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

class LocalDataBackupTest {

    @Test
    fun encryptedPayloadRoundTripsWithPassphraseDerivedKey() {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val plaintext = "DSH profiles, sessions, credentials and workspace".toByteArray()
        val password = "safe test passphrase".toCharArray()
        val encrypted = LocalDataBackup.cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(plaintext)

        val restored = LocalDataBackup.cipher(Cipher.DECRYPT_MODE, password, salt, iv).doFinal(encrypted)

        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun rejectsWrongPassphraseAndTamperedCiphertext() {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val password = "safe test passphrase".toCharArray()
        val encrypted = LocalDataBackup.cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
            .doFinal("private data".toByteArray())
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            LocalDataBackup.cipher(Cipher.DECRYPT_MODE, password, salt, iv).doFinal(encrypted)
        }
        assertThrows(AEADBadTagException::class.java) {
            LocalDataBackup.cipher(Cipher.DECRYPT_MODE, "different passphrase".toCharArray(), salt, iv)
                .doFinal(encrypted)
        }
    }

    @Test
    fun onlyAllowsWorkspaceAndDshHomeArchiveEntries() {
        assertEquals("dsh-home/profiles/default.yml", LocalDataBackup.validateEntry("dsh-home/profiles/default.yml"))
        assertThrows(java.io.IOException::class.java) {
            LocalDataBackup.validateEntry("workspaces/../../outside")
        }
        assertThrows(java.io.IOException::class.java) {
            LocalDataBackup.validateEntry("/dsh-home/secrets.json")
        }
        assertThrows(java.io.IOException::class.java) {
            LocalDataBackup.validateEntry("engine/runtime.zip")
        }
    }
}
