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

package org.matrix.android.sdk.internal.crypto.verification.qrcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.matrix.android.sdk.InstrumentedTest

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class QrCodeTest : InstrumentedTest {

    private val qrCode1 = QrCodeData.VerifyingAnotherUser(
            transactionId = "MaTransaction",
            userMasterCrossSigningPublicKey = "ktEwcUP6su1xh+GuE+CYkQ3H6W/DIl+ybHFdaEOrolU",
            otherUserMasterCrossSigningPublicKey = "TXluZKTZLvSRWOTPlOqLq534bA+/K4zLFKSu9cGLQaU",
            sharedSecret = "MTIzNDU2Nzg"
    )

    private val value1 = "MATRIX\u0002\u0000\u0000\u000DMaTransaction\u0092Ñ0qCú²íq\u0087á®\u0013à\u0098\u0091\u000DÇéoÃ\"_²lq]hC«¢UMynd¤Ù.ô\u0091XäÏ\u0094ê\u008B«\u009Døl\u000F¿+\u008CË\u0014¤®õÁ\u008BA¥12345678"

    private val qrCode2 = QrCodeData.SelfVerifyingMasterKeyTrusted(
            transactionId = "MaTransaction",
            userMasterCrossSigningPublicKey = "ktEwcUP6su1xh+GuE+CYkQ3H6W/DIl+ybHFdaEOrolU",
            otherDeviceKey = "TXluZKTZLvSRWOTPlOqLq534bA+/K4zLFKSu9cGLQaU",
            sharedSecret = "MTIzNDU2Nzg"
    )

    private val value2 = "MATRIX\u0002\u0001\u0000\u000DMaTransaction\u0092Ñ0qCú²íq\u0087á®\u0013à\u0098\u0091\u000DÇéoÃ\"_²lq]hC«¢UMynd¤Ù.ô\u0091XäÏ\u0094ê\u008B«\u009Døl\u000F¿+\u008CË\u0014¤®õÁ\u008BA¥12345678"

    private val qrCode3 = QrCodeData.SelfVerifyingMasterKeyNotTrusted(
            transactionId = "MaTransaction",
            deviceKey = "TXluZKTZLvSRWOTPlOqLq534bA+/K4zLFKSu9cGLQaU",
            userMasterCrossSigningPublicKey = "ktEwcUP6su1xh+GuE+CYkQ3H6W/DIl+ybHFdaEOrolU",
            sharedSecret = "MTIzNDU2Nzg"
    )

    private val value3 = "MATRIX\u0002\u0002\u0000\u000DMaTransactionMynd¤Ù.ô\u0091XäÏ\u0094ê\u008B«\u009Døl\u000F¿+\u008CË\u0014¤®õÁ\u008BA¥\u0092Ñ0qCú²íq\u0087á®\u0013à\u0098\u0091\u000DÇéoÃ\"_²lq]hC«¢U12345678"

    private val sharedSecretByteArray = "12345678".toByteArray(Charsets.ISO_8859_1)

    private val tlx_byteArray = hexToByteArray("4d 79 6e 64 a4 d9 2e f4 91 58 e4 cf 94 ea 8b ab 9d f8 6c 0f bf 2b 8c cb 14 a4 ae f5 c1 8b 41 a5")

    private val kte_byteArray = hexToByteArray("92 d1 30 71 43 fa b2 ed 71 87 e1 ae 13 e0 98 91 0d c7 e9 6f c3 22 5f b2 6c 71 5d 68 43 ab a2 55")

    @Test
    fun testEncoding1() {
        qrCode1.toEncodedString() shouldBeEqualTo value1
    }

    @Test
    fun testEncoding2() {
        qrCode2.toEncodedString() shouldBeEqualTo value2
    }

    @Test
    fun testEncoding3() {
        qrCode3.toEncodedString() shouldBeEqualTo value3
    }

    @Test
    fun testSymmetry1() {
        qrCode1.toEncodedString().toQrCodeData() shouldBeEqualTo qrCode1
    }

    @Test
    fun testSymmetry2() {
        qrCode2.toEncodedString().toQrCodeData() shouldBeEqualTo qrCode2
    }

    @Test
    fun testSymmetry3() {
        qrCode3.toEncodedString().toQrCodeData() shouldBeEqualTo qrCode3
    }

    @Test
    fun testCase1() {
        val url = qrCode1.toEncodedString()

        val byteArray = url.toByteArray(Charsets.ISO_8859_1)
        checkHeader(byteArray)

        // Mode
        byteArray[7] shouldBeEqualTo 0

        checkSizeAndTransaction(byteArray)

        compareArray(byteArray.copyOfRange(23, 23 + 32), kte_byteArray)
        compareArray(byteArray.copyOfRange(23 + 32, 23 + 64), tlx_byteArray)

        compareArray(byteArray.copyOfRange(23 + 64, byteArray.size), sharedSecretByteArray)
    }

    @Test
    fun testCase2() {
        val url = qrCode2.toEncodedString()

        val byteArray = url.toByteArray(Charsets.ISO_8859_1)
        checkHeader(byteArray)

        // Mode
        byteArray[7] shouldBeEqualTo 1

        checkSizeAndTransaction(byteArray)
        compareArray(byteArray.copyOfRange(23, 23 + 32), kte_byteArray)
        compareArray(byteArray.copyOfRange(23 + 32, 23 + 64), tlx_byteArray)

        compareArray(byteArray.copyOfRange(23 + 64, byteArray.size), sharedSecretByteArray)
    }

    @Test
    fun testCase3() {
        val url = qrCode3.toEncodedString()

        val byteArray = url.toByteArray(Charsets.ISO_8859_1)
        checkHeader(byteArray)

        // Mode
        byteArray[7] shouldBeEqualTo 2

        checkSizeAndTransaction(byteArray)
        compareArray(byteArray.copyOfRange(23, 23 + 32), tlx_byteArray)
        compareArray(byteArray.copyOfRange(23 + 32, 23 + 64), kte_byteArray)

        compareArray(byteArray.copyOfRange(23 + 64, byteArray.size), sharedSecretByteArray)
    }

    @Test
    fun testLongTransactionId() {
        // Size on two bytes (2_000 = 0x07D0)
        val longTransactionId = "PatternId_".repeat(200)

        val qrCode = qrCode1.copy(transactionId = longTransactionId)

        val result = qrCode.toEncodedString()
        val expected = value1.replace("\u0000\u000DMaTransaction", "\u0007\u00D0$longTransactionId")

        result shouldBeEqualTo expected

        // Reverse operation
        expected.toQrCodeData() shouldBeEqualTo qrCode
    }

    @Test
    fun testAnyTransactionId() {
        for (qty in 0 until 0x1FFF step 200) {
            val longTransactionId = "a".repeat(qty)

            val qrCode = qrCode1.copy(transactionId = longTransactionId)

            // Symmetric operation
            qrCode.toEncodedString().toQrCodeData() shouldBeEqualTo qrCode
        }
    }

    // Error cases
    @Test
    fun testErrorHeader() {
        value1.replace("MATRIX", "MOTRIX").toQrCodeData().shouldBeNull()
        value1.replace("MATRIX", "MATRI").toQrCodeData().shouldBeNull()
        value1.replace("MATRIX", "").toQrCodeData().shouldBeNull()
    }

    @Test
    fun testErrorVersion() {
        value1.replace("MATRIX\u0002", "MATRIX\u0000").toQrCodeData().shouldBeNull()
        value1.replace("MATRIX\u0002", "MATRIX\u0001").toQrCodeData().shouldBeNull()
        value1.replace("MATRIX\u0002", "MATRIX\u0003").toQrCodeData().shouldBeNull()
        value1.replace("MATRIX\u0002", "MATRIX").toQrCodeData().shouldBeNull()
    }

    @Test
    fun testErrorSecretTooShort() {
        value1.replace("12345678", "1234567").toQrCodeData().shouldBeNull()
    }

    @Test
    fun testErrorNoTransactionNoKeyNoSecret() {
        // But keep transaction length
        "MATRIX\u0002\u0000\u0000\u000D".toQrCodeData().shouldBeNull()
    }

    @Test
    fun testErrorNoKeyNoSecret() {
        "MATRIX\u0002\u0000\u0000\u000DMaTransaction".toQrCodeData().shouldBeNull()
    }

    @Test
    fun testErrorTransactionLengthTooShort() {
        // In this case, the secret will be longer, so this is not an error, but it will lead to keys mismatch
        value1.replace("\u000DMaTransaction", "\u000CMaTransaction").toQrCodeData().shouldNotBeNull()
    }

    @Test
    fun testErrorTransactionLengthTooBig() {
        value1.replace("\u000DMaTransaction", "\u000EMaTransaction").toQrCodeData().shouldBeNull()
    }

    private fun compareArray(actual: ByteArray, expected: ByteArray) {
        actual.size shouldBeEqualTo expected.size

        for (i in actual.indices) {
            actual[i] shouldBeEqualTo expected[i]
        }
    }

    private fun checkHeader(byteArray: ByteArray) {
        // MATRIX
        byteArray[0] shouldBeEqualTo 'M'.code.toByte()
        byteArray[1] shouldBeEqualTo 'A'.code.toByte()
        byteArray[2] shouldBeEqualTo 'T'.code.toByte()
        byteArray[3] shouldBeEqualTo 'R'.code.toByte()
        byteArray[4] shouldBeEqualTo 'I'.code.toByte()
        byteArray[5] shouldBeEqualTo 'X'.code.toByte()

        // Version
        byteArray[6] shouldBeEqualTo 2
    }

    private fun checkSizeAndTransaction(byteArray: ByteArray) {
        // Size
        byteArray[8] shouldBeEqualTo 0
        byteArray[9] shouldBeEqualTo 13

        // Transaction
        byteArray.copyOfRange(10, 10 + "MaTransaction".length).toString(Charsets.ISO_8859_1) shouldBeEqualTo "MaTransaction"
    }
}
