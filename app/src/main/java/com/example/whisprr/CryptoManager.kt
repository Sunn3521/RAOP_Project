package com.example.whisprr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val ALIAS = "whisprr_rsa_key"
    private const val AES_ALGORITHM = "AES/CBC/PKCS7Padding"
    private const val RSA_ALGORITHM = "RSA/ECB/PKCS1Padding"

    /** Hidden bot participant – ensures there's always a valid encryption target */
    const val BOT_UID = "__whisprr_bot__"
    const val BOT_PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsQv4EPeiHb47PFlJZ7rL5/+LAHhtkrRq/nscvALQyZ5YZAm/lHs2l+CC856MaYemBaH5IqNdQgDUcbgtti/Srgf/Pqi838AsvYneB/nSlMpXOu3kXE1z6/NhycnH7u8KuhJywwsPLKEwMg3Vrt0p3N+9FRWBynZjjCAlftpYKXmGBne2yJfds32HVZIKPxF2qA3WKpxW1wmR1m24REMTYpAObMd7OVv4VW94kXWNedTh+KN4OJAaTOK5BjGTygT0MF5U3yjnIkCNBBlc5eILwmpAJO5KYyS7bKiKEYbrEFCzSLar0NKpXk6iWgYhmqu5W0/6gxKpcXVA7EbUd2bJQwIDAQAB"

    // --------------- RSA ---------------

    /** Generate RSA key pair in Android KeyStore (called once on register) */
    fun generateRSAKeyPair(): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
            )
            kpg.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                    .build()
            )
            kpg.generateKeyPair()
        }

        val publicKey = keyStore.getCertificate(ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /** Check if RSA key pair exists in KeyStore */
    fun hasRSAKeyPair(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.containsAlias(ALIAS)
    }

    /** Force regenerate RSA key pair (delete old one and create new) */
    fun forceRegenerateRSAKeyPair(): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        // Delete existing key if present
        if (keyStore.containsAlias(ALIAS)) {
            keyStore.deleteEntry(ALIAS)
        }

        // Generate new key pair
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                .build()
        )
        kpg.generateKeyPair()

        val publicKey = keyStore.getCertificate(ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /** Decrypt an AES key that was encrypted with our RSA Public Key */
    fun rsaDecrypt(encryptedBase64: String): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            throw IllegalStateException("RSA key pair not found in KeyStore")
        }
        val privateKey = keyStore.getKey(ALIAS, null)
        val cipher = Cipher.getInstance(RSA_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        return cipher.doFinal(encryptedBytes)
    }

    /** Encrypt an AES key using a recipient's RSA Public Key (from Firestore) */
    fun rsaEncrypt(data: ByteArray, recipientPublicKeyBase64: String): String {
        val pubKeyBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pubKeyBytes))
        val cipher = Cipher.getInstance(RSA_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    // --------------- AES ---------------

    /** Generate a fresh random AES-256 key */
    fun generateAESKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    /**
     * Encrypt plaintext using AES-CBC.
     * Returns Pair(encryptedBase64, ivBase64)
     */
    fun aesEncrypt(plaintext: String, secretKey: SecretKey): Pair<String, String> {
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Pair(
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    /**
     * Decrypt AES-CBC encrypted message.
     */
    fun aesDecrypt(encryptedBase64: String, ivBase64: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val iv = IvParameterSpec(Base64.decode(ivBase64, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
        val decrypted = cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
        return String(decrypted, Charsets.UTF_8)
    }
}
