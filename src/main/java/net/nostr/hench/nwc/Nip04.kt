package net.nostr.hench.nwc

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Nip04 {
    private val secureRandom = SecureRandom()

    fun encrypt(senderSecretKeyHex: String, recipientPubkeyHex: String, plainText: String): String {
        val iv = ByteArray(16).also(secureRandom::nextBytes)
        return encrypt(senderSecretKeyHex, recipientPubkeyHex, plainText, iv)
    }

    fun encrypt(senderSecretKeyHex: String, recipientPubkeyHex: String, plainText: String, iv: ByteArray): String {
        require(iv.size == 16) { "NIP-04 IV must be 16 bytes" }
        val cipherText = cipher(Cipher.ENCRYPT_MODE, senderSecretKeyHex, recipientPubkeyHex, iv)
            .doFinal(plainText.toByteArray(Charsets.UTF_8))
        return base64(cipherText) + "?iv=" + base64(iv)
    }

    fun decrypt(recipientSecretKeyHex: String, senderPubkeyHex: String, encryptedContent: String): String {
        val cipherTextBase64 = encryptedContent.substringBefore("?iv=")
        val ivBase64 = encryptedContent.substringAfter("?iv=", missingDelimiterValue = "")
        require(cipherTextBase64.isNotBlank() && ivBase64.isNotBlank()) { "invalid NIP-04 payload" }
        val normalizedCipherText = cipherTextBase64.replace(' ', '+')
        val normalizedIv = urlDecode(ivBase64).replace(' ', '+')
        val iv = Base64.getDecoder().decode(normalizedIv)
        val cipherText = Base64.getDecoder().decode(normalizedCipherText)
        val plain = cipher(Cipher.DECRYPT_MODE, recipientSecretKeyHex, senderPubkeyHex, iv).doFinal(cipherText)
        return plain.toString(Charsets.UTF_8)
    }

    private fun cipher(mode: Int, secretKeyHex: String, peerPubkeyHex: String, iv: ByteArray): Cipher {
        val sharedSecret = Bip340Schnorr.sharedSecretX(secretKeyHex.hexToBytes(), peerPubkeyHex.hexToBytes())
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun urlDecode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
