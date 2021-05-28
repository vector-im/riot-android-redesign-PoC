/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.securestorage

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.amshove.kluent.shouldBeEqualTo
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.matrix.android.sdk.InstrumentedTest
import org.matrix.android.sdk.internal.crypto.crosssigning.fromBase64
import org.matrix.android.sdk.internal.crypto.crosssigning.toBase64NoPadding
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class SecretStoringUtilsTest : InstrumentedTest {

    private val buildVersionSdkIntProvider = TestBuildVersionSdkIntProvider()
    private val secretStoringUtils = SecretStoringUtils(context(), buildVersionSdkIntProvider)

    companion object {
        const val TEST_STR = "This is something I want to store safely!"
    }

    @Test
    fun testStringNominalCaseApi21() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.LOLLIPOP
        // Encrypt
        val encrypted = secretStoringUtils.securelyStoreString(TEST_STR, alias)
        // Decrypt
        val decrypted = secretStoringUtils.loadSecureSecret(encrypted, alias)
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testStringNominalCaseApi23() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.M
        // Encrypt
        val encrypted = secretStoringUtils.securelyStoreString(TEST_STR, alias)
        // Decrypt
        val decrypted = secretStoringUtils.loadSecureSecret(encrypted, alias)
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testStringNominalCaseApi30() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.R
        // Encrypt
        val encrypted = secretStoringUtils.securelyStoreString(TEST_STR, alias)
        // Decrypt
        val decrypted = secretStoringUtils.loadSecureSecret(encrypted, alias)
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testStringMigration21_23() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.LOLLIPOP
        // Encrypt
        val encrypted = secretStoringUtils.securelyStoreString(TEST_STR, alias)

        // Simulate a system upgrade
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.M

        // Decrypt
        val decrypted = secretStoringUtils.loadSecureSecret(encrypted, alias)
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testObjectNominalCaseApi21() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.LOLLIPOP

        // Encrypt
        val encrypted = ByteArrayOutputStream().also { outputStream ->
            outputStream.use {
                secretStoringUtils.securelyStoreObject(TEST_STR, alias, it)
            }
        }
                .toByteArray()
                .toBase64NoPadding()
        // Decrypt
        val decrypted = encrypted.fromBase64().inputStream().use {
            secretStoringUtils.loadSecureSecret<String>(it, alias)
        }
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testObjectNominalCaseApi23() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.M

        // Encrypt
        val encrypted = ByteArrayOutputStream().also { outputStream ->
            outputStream.use {
                secretStoringUtils.securelyStoreObject(TEST_STR, alias, it)
            }
        }
                .toByteArray()
                .toBase64NoPadding()
        // Decrypt
        val decrypted = encrypted.fromBase64().inputStream().use {
            secretStoringUtils.loadSecureSecret<String>(it, alias)
        }
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testObjectNominalCaseApi30() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.R

        // Encrypt
        val encrypted = ByteArrayOutputStream().also { outputStream ->
            outputStream.use {
                secretStoringUtils.securelyStoreObject(TEST_STR, alias, it)
            }
        }
                .toByteArray()
                .toBase64NoPadding()
        // Decrypt
        val decrypted = encrypted.fromBase64().inputStream().use {
            secretStoringUtils.loadSecureSecret<String>(it, alias)
        }
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    @Test
    fun testObjectMigration21_23() {
        val alias = generateAlias()
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.LOLLIPOP

        // Encrypt
        val encrypted = ByteArrayOutputStream().also { outputStream ->
            outputStream.use {
                secretStoringUtils.securelyStoreObject(TEST_STR, alias, it)
            }
        }
                .toByteArray()
                .toBase64NoPadding()

        // Simulate a system upgrade
        buildVersionSdkIntProvider.value = Build.VERSION_CODES.M

        // Decrypt
        val decrypted = encrypted.fromBase64().inputStream().use {
            secretStoringUtils.loadSecureSecret<String>(it, alias)
        }
        decrypted shouldBeEqualTo TEST_STR
        secretStoringUtils.safeDeleteKey(alias)
    }

    private fun generateAlias() = UUID.randomUUID().toString()
}
