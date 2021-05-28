/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("DEPRECATION")

package org.matrix.android.sdk.internal.session.securestorage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import org.matrix.android.sdk.internal.util.system.BuildVersionSdkIntProvider
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.lang.IllegalArgumentException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.security.auth.x500.X500Principal

/**
 * Offers simple methods to securely store secrets in an Android Application.
 * The encryption keys are randomly generated and securely managed by the key store, thus your secrets
 * are safe. You only need to remember a key alias to perform encrypt/decrypt operations.
 *
 * <b>Android M++</b>
 * On android M+, the keystore can generates and store AES keys via API. But below API M this functionality
 * is not available.
 *
 * <b>Android [K-M[</b>
 * For android >=L and <M, we use the keystore to generate and store a private/public key pair. Then for each secret, a
 * random secret key in generated to perform encryption.
 * This secret key is encrypted with the public RSA key and stored with the encrypted secret.
 * In order to decrypt the encrypted secret key will be retrieved then decrypted with the RSA private key.
 *
 * Sample usage:
 * <code>
 *     val secret = "The answer is 42"
 *     val KEncrypted = SecretStoringUtils.securelyStoreString(secret, "myAlias")
 *     //This can be stored anywhere e.g. encoded in b64 and stored in preference for example
 *
 *     //to get back the secret, just call
 *     val kDecrypted = SecretStoringUtils.loadSecureSecret(KEncrypted, "myAlias")
 * </code>
 *
 * You can also just use this utility to store a secret key, and use any encryption algorithm that you want.
 *
 * Important: Keys stored in the keystore can be wiped out (depends of the OS version, like for example if you
 * add a pin or change the schema); So you might and with a useless pile of bytes.
 */
internal class SecretStoringUtils @Inject constructor(
        private val context: Context,
        private val buildVersionSdkIntProvider: BuildVersionSdkIntProvider
) {

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val RSA_MODE = "RSA/ECB/PKCS1Padding"

        private const val FORMAT_API_M: Byte = 0
        private const val FORMAT_1: Byte = 1
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    private val secureRandom = SecureRandom()

    fun safeDeleteKey(keyAlias: String) {
        try {
            keyStore.deleteEntry(keyAlias)
        } catch (e: KeyStoreException) {
            Timber.e(e)
        }
    }

    /**
     * Encrypt the given secret using the android Keystore.
     * On android >= M, will directly use the keystore to generate a symmetric key
     * On android >= Lollipop and <M, as symmetric key gen is not available, will use an symmetric key generated
     * in the keystore to encrypted a random symmetric key. The encrypted symmetric key is returned
     * in the bytearray (in can be stored anywhere, it is encrypted)
     *
     * The secret is encrypted using the following method: AES/GCM/NoPadding
     */
    @SuppressLint("NewApi")
    @Throws(Exception::class)
    fun securelyStoreString(secret: String, keyAlias: String): ByteArray {
        return when {
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.M -> encryptStringM(secret, keyAlias)
            else                                                      -> encryptString(secret, keyAlias)
        }
    }

    /**
     * Decrypt a secret that was encrypted by #securelyStoreString()
     */
    @SuppressLint("NewApi")
    @Throws(Exception::class)
    fun loadSecureSecret(encrypted: ByteArray, keyAlias: String): String {
        encrypted.inputStream().use { inputStream ->
            // First get the format
            return when (val format = inputStream.read().toByte()) {
                FORMAT_API_M -> decryptStringM(inputStream, keyAlias)
                FORMAT_1     -> decryptString(inputStream, keyAlias)
                else         -> throw IllegalArgumentException("Unknown format $format")
            }
        }
    }

    @SuppressLint("NewApi")
    fun securelyStoreObject(any: Any, keyAlias: String, output: OutputStream) {
        when {
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.M -> saveSecureObjectM(keyAlias, output, any)
            else                                                      -> saveSecureObject(keyAlias, output, any)
        }
    }

    @SuppressLint("NewApi")
    fun <T> loadSecureSecret(inputStream: InputStream, keyAlias: String): T? {
        // First get the format
        return when (val format = inputStream.read().toByte()) {
            FORMAT_API_M -> loadSecureObjectM(keyAlias, inputStream)
            FORMAT_1     -> loadSecureObject(keyAlias, inputStream)
            else         -> throw IllegalArgumentException("Unknown format $format")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrGenerateSymmetricKeyForAliasM(alias: String): SecretKey {
        val secretKeyEntry = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey
        if (secretKeyEntry == null) {
            // we generate it
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keyGenSpec = KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128)
                    .build()
            generator.init(keyGenSpec)
            return generator.generateKey()
        }
        return secretKeyEntry
    }

    /*
    Symmetric Key Generation is only available in M, so before M the idea is to:
        - Generate a pair of RSA keys;
        - Generate a random AES key;
        - Encrypt the AES key using the RSA public key;
        - Store the encrypted AES
     Generate a key pair for encryption
     */
    private fun getOrGenerateKeyPairForAlias(alias: String): KeyStore.PrivateKeyEntry {
        val privateKeyEntry = (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)

        if (privateKeyEntry != null) return privateKeyEntry

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        end.add(Calendar.YEAR, 30)

        val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(alias)
                .setSubject(X500Principal("CN=$alias"))
                .setSerialNumber(BigInteger.TEN)
                // .setEncryptionRequired() requires that the phone has a pin/schema
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
        KeyPairGenerator.getInstance("RSA" /*KeyProperties.KEY_ALGORITHM_RSA*/, ANDROID_KEY_STORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return (keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptStringM(text: String, keyAlias: String): ByteArray {
        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        // we happen the iv to the final result
        val encryptedBytes: ByteArray = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return formatMMake(iv, encryptedBytes)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decryptStringM(inputStream: InputStream, keyAlias: String): String {
        val (iv, encryptedText) = formatMExtract(inputStream)

        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(encryptedText), Charsets.UTF_8)
    }

    private fun encryptString(text: String, keyAlias: String): ByteArray {
        // we generate a random symmetric key
        val key = ByteArray(16)
        secureRandom.nextBytes(key)
        val sKey = SecretKeySpec(key, "AES")

        // we encrypt this key thanks to the key store
        val encryptedKey = rsaEncrypt(keyAlias, key)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, sKey)
        val iv = cipher.iv
        val encryptedBytes: ByteArray = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        return format1Make(encryptedKey, iv, encryptedBytes)
    }

    private fun decryptString(inputStream: InputStream, keyAlias: String): String {
        val (encryptedKey, iv, encrypted) = format1Extract(inputStream)

        // we need to decrypt the key
        val sKeyBytes = rsaDecrypt(keyAlias, ByteArrayInputStream(encryptedKey))
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKeyBytes, "AES"), spec)

        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(IOException::class)
    private fun saveSecureObjectM(keyAlias: String, output: OutputStream, writeObject: Any) {
        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey/*, spec*/)
        val iv = cipher.iv

        val bos1 = ByteArrayOutputStream()
        ObjectOutputStream(bos1).use {
            it.writeObject(writeObject)
        }
        // Have to do it like that if i encapsulate the output stream, the cipher could fail saying reuse IV
        val doFinal = cipher.doFinal(bos1.toByteArray())
        output.write(FORMAT_API_M.toInt())
        output.write(iv.size)
        output.write(iv)
        output.write(doFinal)
    }

    private fun saveSecureObject(keyAlias: String, output: OutputStream, writeObject: Any) {
        // we generate a random symmetric key
        val key = ByteArray(16)
        secureRandom.nextBytes(key)
        val sKey = SecretKeySpec(key, "AES")

        // we encrypt this key thanks to the key store
        val encryptedKey = rsaEncrypt(keyAlias, key)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, sKey)
        val iv = cipher.iv

        val bos1 = ByteArrayOutputStream()
        val cos = CipherOutputStream(bos1, cipher)
        ObjectOutputStream(cos).use {
            it.writeObject(writeObject)
        }

        output.write(FORMAT_1.toInt())
        output.write((encryptedKey.size and 0xFF00).shr(8))
        output.write(encryptedKey.size and 0x00FF)
        output.write(encryptedKey)
        output.write(iv.size)
        output.write(iv)
        output.write(bos1.toByteArray())
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(IOException::class)
    private fun <T> loadSecureObjectM(keyAlias: String, inputStream: InputStream): T? {
        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val ivSize = inputStream.read()
        val iv = ByteArray(ivSize)
        inputStream.read(iv, 0, ivSize)
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        CipherInputStream(inputStream, cipher).use { cipherInputStream ->
            ObjectInputStream(cipherInputStream).use {
                val readObject = it.readObject()
                @Suppress("UNCHECKED_CAST")
                return readObject as? T
            }
        }
    }

    @Throws(IOException::class)
    private fun <T> loadSecureObject(keyAlias: String, inputStream: InputStream): T? {
        val (encryptedKey, iv, encrypted) = format1Extract(inputStream)

        // we need to decrypt the key
        val sKeyBytes = rsaDecrypt(keyAlias, ByteArrayInputStream(encryptedKey))
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKeyBytes, "AES"), spec)

        val encIS = ByteArrayInputStream(encrypted)

        CipherInputStream(encIS, cipher).use { cipherInputStream ->
            ObjectInputStream(cipherInputStream).use {
                val readObject = it.readObject()
                @Suppress("UNCHECKED_CAST")
                return readObject as? T
            }
        }
    }

    @Throws(Exception::class)
    private fun rsaEncrypt(alias: String, secret: ByteArray): ByteArray {
        val privateKeyEntry = getOrGenerateKeyPairForAlias(alias)
        // Encrypt the text
        val inputCipher = Cipher.getInstance(RSA_MODE)
        inputCipher.init(Cipher.ENCRYPT_MODE, privateKeyEntry.certificate.publicKey)

        val outputStream = ByteArrayOutputStream()
        CipherOutputStream(outputStream, inputCipher).use {
            it.write(secret)
        }

        return outputStream.toByteArray()
    }

    @Throws(Exception::class)
    private fun rsaDecrypt(alias: String, encrypted: InputStream): ByteArray {
        val privateKeyEntry = getOrGenerateKeyPairForAlias(alias)
        val output = Cipher.getInstance(RSA_MODE)
        output.init(Cipher.DECRYPT_MODE, privateKeyEntry.privateKey)

        return CipherInputStream(encrypted, output).use { it.readBytes() }
    }

    private fun formatMExtract(bis: InputStream): Pair<ByteArray, ByteArray> {
        val ivSize = bis.read()
        val iv = ByteArray(ivSize)
        bis.read(iv, 0, ivSize)

        val encrypted = bis.readBytes()
        return Pair(iv, encrypted)
    }

    private fun formatMMake(iv: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(2 + iv.size + data.size)
        bos.write(FORMAT_API_M.toInt())
        bos.write(iv.size)
        bos.write(iv)
        bos.write(data)
        return bos.toByteArray()
    }

    private fun format1Extract(bis: InputStream): Triple<ByteArray, ByteArray, ByteArray> {
        val keySizeBig = bis.read()
        val keySizeLow = bis.read()
        val encryptedKeySize = keySizeBig.shl(8) + keySizeLow
        val encryptedKey = ByteArray(encryptedKeySize)
        bis.read(encryptedKey)

        val ivSize = bis.read()
        val iv = ByteArray(ivSize)
        bis.read(iv)

        val encrypted = bis.readBytes()
        return Triple(encryptedKey, iv, encrypted)
    }

    private fun format1Make(encryptedKey: ByteArray, iv: ByteArray, encryptedBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(4 + encryptedKey.size + iv.size + encryptedBytes.size)
        bos.write(FORMAT_1.toInt())
        bos.write((encryptedKey.size and 0xFF00).shr(8))
        bos.write(encryptedKey.size and 0x00FF)
        bos.write(encryptedKey)
        bos.write(iv.size)
        bos.write(iv)
        bos.write(encryptedBytes)

        return bos.toByteArray()
    }
}
