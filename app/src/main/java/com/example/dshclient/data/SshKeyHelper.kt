package com.example.dshclient.data

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

object SshKeyHelper {

    init {
        try {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        } catch (_: Throwable) {}
    }

    private val AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)

    data class KeyPairResult(
        val privateKey: String,
        val publicKey: String
    )

    fun generateEd25519KeyPair(
        comment: String = "dsh-client"
    ): KeyPairResult {
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        try {
            keyPair.publicKeyComment = comment

            val pubOut = ByteArrayOutputStream()
            keyPair.writePublicKey(pubOut, comment)
            val publicKey = pubOut.toString(Charsets.UTF_8.name()).trim()

            val privateKey = formatOpenSshV1PrivateKey(keyPair)

            return KeyPairResult(
                privateKey = privateKey,
                publicKey = publicKey
            )
        } finally {
            keyPair.dispose()
        }
    }

    fun generateKeyPair(
        comment: String = "dsh-client"
    ): KeyPairResult {
        return generateEd25519KeyPair(comment)
    }

    private fun formatOpenSshV1PrivateKey(keyPair: KeyPair): String {
        val sshAgentBytes = keyPair.forSSHAgent()
        val pubBlob = keyPair.publicKeyBlob

        // Inner private key section
        val privSectionStream = ByteArrayOutputStream()
        val privDos = DataOutputStream(privSectionStream)
        val secureRandom = SecureRandom()
        val checkInt = secureRandom.nextInt()
        privDos.writeInt(checkInt)
        privDos.writeInt(checkInt)
        privDos.write(sshAgentBytes)

        // Padding to 8-byte boundary for cipher "none"
        val unpaddedLen = privSectionStream.size()
        val padLen = (8 - (unpaddedLen % 8)) % 8
        for (i in 1..padLen) {
            privDos.writeByte(i)
        }
        privDos.flush()
        val privateSection = privSectionStream.toByteArray()

        // Outer OpenSSH v1 container
        val containerStream = ByteArrayOutputStream()
        val dos = DataOutputStream(containerStream)

        // 1. Magic
        dos.write(AUTH_MAGIC)

        // 2. Cipher name
        val cipherNone = "none".toByteArray(Charsets.US_ASCII)
        dos.writeInt(cipherNone.size)
        dos.write(cipherNone)

        // 3. KDF name
        dos.writeInt(cipherNone.size)
        dos.write(cipherNone)

        // 4. KDF options (empty)
        dos.writeInt(0)

        // 5. Number of keys
        dos.writeInt(1)

        // 6. Public key blob
        dos.writeInt(pubBlob.size)
        dos.write(pubBlob)

        // 7. Private key section
        dos.writeInt(privateSection.size)
        dos.write(privateSection)
        dos.flush()

        val rawBytes = containerStream.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(rawBytes)
        val wrappedBase64 = base64.chunked(70).joinToString("\n")

        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrappedBase64\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
