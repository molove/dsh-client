package com.example.dshclient.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyHelperTest {

    @Test
    fun generateEd25519KeyPair_generatesValidPublicKeyFormat() {
        val comment = "dsh-client-test"
        val keyPair = SshKeyHelper.generateEd25519KeyPair(comment = comment)

        assertNotNull(keyPair)
        assertNotNull(keyPair.publicKey)
        assertNotNull(keyPair.privateKey)

        // Verifying public key format: ssh-ed25519 AAAAC3... [comment]
        val parts = keyPair.publicKey.trim().split(" ")
        assertEquals(3, parts.size)
        assertEquals("ssh-ed25519", parts[0])
        assertTrue("Public key blob must start with AAAAC3", parts[1].startsWith("AAAAC3"))
        assertEquals(comment, parts[2])
    }

    @Test
    fun generateEd25519KeyPair_generatesValidPrivateKey() {
        val keyPair = SshKeyHelper.generateEd25519KeyPair()

        assertTrue(
            "Private key should be OpenSSH formatted",
            keyPair.privateKey.contains("BEGIN OPENSSH PRIVATE KEY")
        )
    }

    @Test
    fun generateEd25519KeyPair_withCustomComment() {
        val keyPair = SshKeyHelper.generateEd25519KeyPair(
            comment = "test@example.com"
        )

        assertTrue(keyPair.publicKey.startsWith("ssh-ed25519 AAAAC3"))
        assertTrue(keyPair.publicKey.endsWith("test@example.com"))
        assertTrue(keyPair.privateKey.contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    @Test
    fun generateKeyPair_delegatesToEd25519() {
        val keyPair = SshKeyHelper.generateKeyPair()

        assertTrue(keyPair.publicKey.startsWith("ssh-ed25519 AAAAC3"))
        assertTrue(keyPair.privateKey.contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    @Test
    fun generatedPrivateKey_canBeLoadedByJSch() {
        val keyPair = SshKeyHelper.generateEd25519KeyPair(comment = "verify-load")
        val jsch = com.jcraft.jsch.JSch()
        val loaded = com.jcraft.jsch.KeyPair.load(
            jsch,
            keyPair.privateKey.toByteArray(Charsets.UTF_8),
            null
        )
        assertNotNull(loaded)
        assertEquals(com.jcraft.jsch.KeyPair.ED25519, loaded.keyType)
    }
}
