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

package org.matrix.android.sdk.internal.session.room.send

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeInRange
import org.junit.Test

class WaveFormSanitizerTest {

    private val waveFormSanitizer = WaveFormSanitizer()

    @Test
    fun sanitizeNull() {
        waveFormSanitizer.sanitize(null) shouldBe null
    }

    @Test
    fun sanitizeEmpty() {
        waveFormSanitizer.sanitize(emptyList()) shouldBe null
    }

    @Test
    fun sanitizeSingleton() {
        val result = waveFormSanitizer.sanitize(listOf(1))!!
        result.size shouldBe 30
        checkResult(result)
    }

    @Test
    fun sanitize29() {
        val list = generateSequence { 1 }.take(29).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitize30() {
        val list = generateSequence { 1 }.take(30).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        result.size shouldBe 30
        checkResult(result)
    }

    @Test
    fun sanitize31() {
        val list = generateSequence { 1 }.take(31).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitize119() {
        val list = generateSequence { 1 }.take(119).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitize120() {
        val list = generateSequence { 1 }.take(120).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        result.size shouldBe 120
        checkResult(result)
    }

    @Test
    fun sanitize121() {
        val list = generateSequence { 1 }.take(121).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitize1024() {
        val list = generateSequence { 1 }.take(1024).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitizeNegative() {
        val list = generateSequence { -1 }.take(30).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitizeMaxValue() {
        val list = generateSequence { 1025 }.take(30).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    @Test
    fun sanitizeNegativeMaxValue() {
        val list = generateSequence { -1025 }.take(30).toList()
        val result = waveFormSanitizer.sanitize(list)!!
        checkResult(result)
    }

    private fun checkResult(result: List<Int>) {
        result.forEach {
            it shouldBeInRange 0..1024
        }

        result.size shouldBeInRange 30..120
    }
}
