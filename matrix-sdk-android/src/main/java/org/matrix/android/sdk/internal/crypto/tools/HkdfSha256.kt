/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 * Copyright (C) 2015 Square, Inc.
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
package org.matrix.android.sdk.internal.crypto.tools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * HMAC-based Extract-and-Expand Key Derivation Function (HkdfSha256)
 * [RFC-5869] https://tools.ietf.org/html/rfc5869
 */
object HkdfSha256 {

    fun deriveSecret(inputKeyMaterial: ByteArray, salt: ByteArray?, info: ByteArray, outputLength: Int): ByteArray {
        return expand(extract(salt, inputKeyMaterial), info, outputLength)
    }

    /**
     * HkdfSha256-Extract(salt, IKM) -> PRK
     *
     * @param salt  optional salt value (a non-secret random value);
     * if not provided, it is set to a string of HashLen (size in octets) zeros.
     * @param ikm input keying material
     */
    private fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = initMac(salt ?: ByteArray(HASH_LEN) { 0.toByte() })
        return mac.doFinal(ikm)
    }

    /**
     * HkdfSha256-Expand(PRK, info, L) -> OKM
     *
     * @param prk a pseudorandom key of at least HashLen bytes (usually, the output from the extract step)
     * @param info optional context and application specific information (can be empty)
     * @param outputLength length of output keying material in bytes (<= 255*HashLen)
     * @return OKM output keying material
     */
    private fun expand(prk: ByteArray, info: ByteArray = ByteArray(0), outputLength: Int): ByteArray {
        require(outputLength <= 255 * HASH_LEN) { "outputLength must be less than or equal to 255*HashLen" }

        /*
          The output OKM is calculated as follows:
          Notation | -> When the message is composed of several elements we use concatenation (denoted |) in the second argument;


           N = ceil(L/HashLen)
           T = T(1) | T(2) | T(3) | ... | T(N)
           OKM = first L octets of T

           where:
           T(0) = empty string (zero length)
           T(1) = HMAC-Hash(PRK, T(0) | info | 0x01)
           T(2) = HMAC-Hash(PRK, T(1) | info | 0x02)
           T(3) = HMAC-Hash(PRK, T(2) | info | 0x03)
           ...
        */
        val n = ceil(outputLength.toDouble() / HASH_LEN.toDouble()).toInt()

        var stepHash = ByteArray(0) // T(0) empty string (zero length)

        val generatedBytes = ByteArrayOutputStream() // ByteBuffer.allocate(Math.multiplyExact(n, HASH_LEN))
        val mac = initMac(prk)
        for (roundNum in 1..n) {
            mac.reset()
            val t = ByteBuffer.allocate(stepHash.size + info.size + 1).apply {
                put(stepHash)
                put(info)
                put(roundNum.toByte())
            }
            stepHash = mac.doFinal(t.array())
            generatedBytes.write(stepHash)
        }

        return generatedBytes.toByteArray().sliceArray(0 until outputLength)
    }

    private fun initMac(secret: ByteArray): Mac {
        val mac = Mac.getInstance(HASH_ALG)
        mac.init(SecretKeySpec(secret, HASH_ALG))
        return mac
    }

    private const val HASH_LEN = 32
    private const val HASH_ALG = "HmacSHA256"
}
