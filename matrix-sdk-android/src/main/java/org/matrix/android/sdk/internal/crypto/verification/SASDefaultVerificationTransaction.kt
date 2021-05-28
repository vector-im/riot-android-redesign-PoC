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
package org.matrix.android.sdk.internal.crypto.verification

import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.verification.CancelCode
import org.matrix.android.sdk.api.session.crypto.verification.EmojiRepresentation
import org.matrix.android.sdk.api.session.crypto.verification.SasMode
import org.matrix.android.sdk.api.session.crypto.verification.SasVerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTxState
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.crypto.IncomingGossipingRequestManager
import org.matrix.android.sdk.internal.crypto.OutgoingGossipingRequestManager
import org.matrix.android.sdk.internal.crypto.actions.SetDeviceVerificationAction
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.extensions.toUnsignedInt
import org.matrix.olm.OlmSAS
import org.matrix.olm.OlmUtility
import timber.log.Timber
import java.util.Locale

/**
 * Represents an ongoing short code interactive key verification between two devices.
 */
internal abstract class SASDefaultVerificationTransaction(
        setDeviceVerificationAction: SetDeviceVerificationAction,
        open val userId: String,
        open val deviceId: String?,
        private val cryptoStore: IMXCryptoStore,
        crossSigningService: CrossSigningService,
        outgoingGossipingRequestManager: OutgoingGossipingRequestManager,
        incomingGossipingRequestManager: IncomingGossipingRequestManager,
        private val deviceFingerprint: String,
        transactionId: String,
        otherUserId: String,
        otherDeviceId: String?,
        isIncoming: Boolean
) : DefaultVerificationTransaction(
        setDeviceVerificationAction,
        crossSigningService,
        outgoingGossipingRequestManager,
        incomingGossipingRequestManager,
        userId,
        transactionId,
        otherUserId,
        otherDeviceId,
        isIncoming),
        SasVerificationTransaction {

    companion object {
        const val SAS_MAC_SHA256_LONGKDF = "hmac-sha256"
        const val SAS_MAC_SHA256 = "hkdf-hmac-sha256"

        // Deprecated maybe removed later, use V2
        const val KEY_AGREEMENT_V1 = "curve25519"
        const val KEY_AGREEMENT_V2 = "curve25519-hkdf-sha256"

        // ordered by preferred order
        val KNOWN_AGREEMENT_PROTOCOLS = listOf(KEY_AGREEMENT_V2, KEY_AGREEMENT_V1)

        // ordered by preferred order
        val KNOWN_HASHES = listOf("sha256")

        // ordered by preferred order
        val KNOWN_MACS = listOf(SAS_MAC_SHA256, SAS_MAC_SHA256_LONGKDF)

        // older devices have limited support of emoji but SDK offers images for the 64 verification emojis
        // so always send that we support EMOJI
        val KNOWN_SHORT_CODES = listOf(SasMode.EMOJI, SasMode.DECIMAL)
    }

    override var state: VerificationTxState = VerificationTxState.None
        set(newState) {
            field = newState

            listeners.forEach {
                try {
                    it.transactionUpdated(this)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }

            if (newState is VerificationTxState.TerminalTxState) {
                releaseSAS()
            }
        }

    private var olmSas: OlmSAS? = null

    // Visible for test
    var startReq: ValidVerificationInfoStart.SasVerificationInfoStart? = null

    // Visible for test
    var accepted: ValidVerificationInfoAccept? = null
    protected var otherKey: String? = null
    protected var shortCodeBytes: ByteArray? = null

    protected var myMac: ValidVerificationInfoMac? = null
    protected var theirMac: ValidVerificationInfoMac? = null

    protected fun getSAS(): OlmSAS {
        if (olmSas == null) olmSas = OlmSAS()
        return olmSas!!
    }

    // To override finalize(), all you need to do is simply declare it, without using the override keyword:
    protected fun finalize() {
        releaseSAS()
    }

    private fun releaseSAS() {
        // finalization logic
        olmSas?.releaseSas()
        olmSas = null
    }

    /**
     * To be called by the client when the user has verified that
     * both short codes do match
     */
    override fun userHasVerifiedShortCode() {
        Timber.v("## SAS short code verified by user for id:$transactionId")
        if (state != VerificationTxState.ShortCodeReady) {
            // ignore and cancel?
            Timber.e("## Accepted short code from invalid state $state")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        state = VerificationTxState.ShortCodeAccepted
        // Alice and Bob’ devices calculate the HMAC of their own device keys and a comma-separated,
        // sorted list of the key IDs that they wish the other user to verify,
        // the shared secret as the input keying material, no salt, and with the input parameter set to the concatenation of:
        // - the string “MATRIX_KEY_VERIFICATION_MAC”,
        // - the Matrix ID of the user whose key is being MAC-ed,
        // - the device ID of the device sending the MAC,
        // - the Matrix ID of the other user,
        // - the device ID of the device receiving the MAC,
        // - the transaction ID, and
        // - the key ID of the key being MAC-ed, or the string “KEY_IDS” if the item being MAC-ed is the list of key IDs.
        val baseInfo = "MATRIX_KEY_VERIFICATION_MAC$userId$deviceId$otherUserId$otherDeviceId$transactionId"

        //  Previously, with SAS verification, the m.key.verification.mac message only contained the user's device key.
        //  It should now contain both the device key and the MSK.
        //  So when Alice and Bob verify with SAS, the verification will verify the MSK.

        val keyMap = HashMap<String, String>()

        val keyId = "ed25519:$deviceId"
        val macString = macUsingAgreedMethod(deviceFingerprint, baseInfo + keyId)

        if (macString.isNullOrBlank()) {
            // Should not happen
            Timber.e("## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        keyMap[keyId] = macString

        cryptoStore.getMyCrossSigningInfo()?.takeIf { it.isTrusted() }
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?.let { masterPublicKey ->
                    val crossSigningKeyId = "ed25519:$masterPublicKey"
                    macUsingAgreedMethod(masterPublicKey, baseInfo + crossSigningKeyId)?.let { mskMacString ->
                        keyMap[crossSigningKeyId] = mskMacString
                    }
                }

        val keyStrings = macUsingAgreedMethod(keyMap.keys.sorted().joinToString(","), baseInfo + "KEY_IDS")

        if (macString.isNullOrBlank() || keyStrings.isNullOrBlank()) {
            // Should not happen
            Timber.e("## SAS verification [$transactionId] failed to send KeyMac, empty key hashes.")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        val macMsg = transport.createMac(transactionId, keyMap, keyStrings)
        myMac = macMsg.asValidObject()
        state = VerificationTxState.SendingMac
        sendToOther(EventType.KEY_VERIFICATION_MAC, macMsg, VerificationTxState.MacSent, CancelCode.User) {
            if (state == VerificationTxState.SendingMac) {
                // It is possible that we receive the next event before this one :/, in this case we should keep state
                state = VerificationTxState.MacSent
            }
        }

        // Do I already have their Mac?
        theirMac?.let { verifyMacs(it) }
        // if not wait for it
    }

    override fun shortCodeDoesNotMatch() {
        Timber.v("## SAS short code do not match for id:$transactionId")
        cancel(CancelCode.MismatchedSas)
    }

    override fun isToDeviceTransport(): Boolean {
        return transport is VerificationTransportToDevice
    }

    abstract fun onVerificationStart(startReq: ValidVerificationInfoStart.SasVerificationInfoStart)

    abstract fun onVerificationAccept(accept: ValidVerificationInfoAccept)

    abstract fun onKeyVerificationKey(vKey: ValidVerificationInfoKey)

    abstract fun onKeyVerificationMac(vMac: ValidVerificationInfoMac)

    protected fun verifyMacs(theirMacSafe: ValidVerificationInfoMac) {
        Timber.v("## SAS verifying macs for id:$transactionId")
        state = VerificationTxState.Verifying

        // Keys have been downloaded earlier in process
        val otherUserKnownDevices = cryptoStore.getUserDevices(otherUserId)

        // Bob’s device calculates the HMAC (as above) of its copies of Alice’s keys given in the message (as identified by their key ID),
        // as well as the HMAC of the comma-separated, sorted list of the key IDs given in the message.
        // Bob’s device compares these with the HMAC values given in the m.key.verification.mac message.
        // If everything matches, then consider Alice’s device keys as verified.
        val baseInfo = "MATRIX_KEY_VERIFICATION_MAC$otherUserId$otherDeviceId$userId$deviceId$transactionId"

        val commaSeparatedListOfKeyIds = theirMacSafe.mac.keys.sorted().joinToString(",")

        val keyStrings = macUsingAgreedMethod(commaSeparatedListOfKeyIds, baseInfo + "KEY_IDS")
        if (theirMacSafe.keys != keyStrings) {
            // WRONG!
            cancel(CancelCode.MismatchedKeys)
            return
        }

        val verifiedDevices = ArrayList<String>()

        // cannot be empty because it has been validated
        theirMacSafe.mac.keys.forEach {
            val keyIDNoPrefix = it.removePrefix("ed25519:")
            val otherDeviceKey = otherUserKnownDevices?.get(keyIDNoPrefix)?.fingerprint()
            if (otherDeviceKey == null) {
                Timber.w("## SAS Verification: Could not find device $keyIDNoPrefix to verify")
                // just ignore and continue
                return@forEach
            }
            val mac = macUsingAgreedMethod(otherDeviceKey, baseInfo + it)
            if (mac != theirMacSafe.mac[it]) {
                // WRONG!
                Timber.e("## SAS Verification: mac mismatch for $otherDeviceKey with id $keyIDNoPrefix")
                cancel(CancelCode.MismatchedKeys)
                return
            }
            verifiedDevices.add(keyIDNoPrefix)
        }

        var otherMasterKeyIsVerified = false
        val otherMasterKey = cryptoStore.getCrossSigningInfo(otherUserId)?.masterKey()
        val otherCrossSigningMasterKeyPublic = otherMasterKey?.unpaddedBase64PublicKey
        if (otherCrossSigningMasterKeyPublic != null) {
            // Did the user signed his master key
            theirMacSafe.mac.keys.forEach {
                val keyIDNoPrefix = it.removePrefix("ed25519:")
                if (keyIDNoPrefix == otherCrossSigningMasterKeyPublic) {
                    // Check the signature
                    val mac = macUsingAgreedMethod(otherCrossSigningMasterKeyPublic, baseInfo + it)
                    if (mac != theirMacSafe.mac[it]) {
                        // WRONG!
                        Timber.e("## SAS Verification: mac mismatch for MasterKey with id $keyIDNoPrefix")
                        cancel(CancelCode.MismatchedKeys)
                        return
                    } else {
                        otherMasterKeyIsVerified = true
                    }
                }
            }
        }

        // if none of the keys could be verified, then error because the app
        // should be informed about that
        if (verifiedDevices.isEmpty() && !otherMasterKeyIsVerified) {
            Timber.e("## SAS Verification: No devices verified")
            cancel(CancelCode.MismatchedKeys)
            return
        }

        trust(otherMasterKeyIsVerified,
                verifiedDevices,
                eventuallyMarkMyMasterKeyAsTrusted = otherMasterKey?.trustLevel?.isVerified() == false)
    }

    override fun cancel() {
        cancel(CancelCode.User)
    }

    override fun cancel(code: CancelCode) {
        state = VerificationTxState.Cancelled(code, true)
        transport.cancelTransaction(transactionId, otherUserId, otherDeviceId ?: "", code)
    }

    protected fun <T> sendToOther(type: String,
                                  keyToDevice: VerificationInfo<T>,
                                  nextState: VerificationTxState,
                                  onErrorReason: CancelCode,
                                  onDone: (() -> Unit)?) {
        transport.sendToOther(type, keyToDevice, nextState, onErrorReason, onDone)
    }

    fun getShortCodeRepresentation(shortAuthenticationStringMode: String): String? {
        if (shortCodeBytes == null) {
            return null
        }
        when (shortAuthenticationStringMode) {
            SasMode.DECIMAL -> {
                if (shortCodeBytes!!.size < 5) return null
                return getDecimalCodeRepresentation(shortCodeBytes!!)
            }
            SasMode.EMOJI   -> {
                if (shortCodeBytes!!.size < 6) return null
                return getEmojiCodeRepresentation(shortCodeBytes!!).joinToString(" ") { it.emoji }
            }
            else            -> return null
        }
    }

    override fun supportsEmoji(): Boolean {
        return accepted?.shortAuthenticationStrings?.contains(SasMode.EMOJI).orFalse()
    }

    override fun supportsDecimal(): Boolean {
        return accepted?.shortAuthenticationStrings?.contains(SasMode.DECIMAL).orFalse()
    }

    protected fun hashUsingAgreedHashMethod(toHash: String): String? {
        if ("sha256" == accepted?.hash?.lowercase(Locale.ROOT)) {
            val olmUtil = OlmUtility()
            val hashBytes = olmUtil.sha256(toHash)
            olmUtil.releaseUtility()
            return hashBytes
        }
        return null
    }

    private fun macUsingAgreedMethod(message: String, info: String): String? {
        return when (accepted?.messageAuthenticationCode?.lowercase(Locale.ROOT)) {
            SAS_MAC_SHA256_LONGKDF -> getSAS().calculateMacLongKdf(message, info)
            SAS_MAC_SHA256         -> getSAS().calculateMac(message, info)
            else                   -> null
        }
    }

    override fun getDecimalCodeRepresentation(): String {
        return getDecimalCodeRepresentation(shortCodeBytes!!)
    }

    /**
     * decimal: generate five bytes by using HKDF.
     * Take the first 13 bits and convert it to a decimal number (which will be a number between 0 and 8191 inclusive),
     * and add 1000 (resulting in a number between 1000 and 9191 inclusive).
     * Do the same with the second 13 bits, and the third 13 bits, giving three 4-digit numbers.
     * In other words, if the five bytes are B0, B1, B2, B3, and B4, then the first number is (B0 << 5 | B1 >> 3) + 1000,
     * the second number is ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000, and the third number is ((B3 & 0x3f) << 7 | B4 >> 1) + 1000.
     * (This method of converting 13 bits at a time is used to avoid requiring 32-bit clients to do big-number arithmetic,
     * and adding 1000 to the number avoids having clients to worry about properly zero-padding the number when displaying to the user.)
     * The three 4-digit numbers are displayed to the user either with dashes (or another appropriate separator) separating the three numbers,
     * or with the three numbers on separate lines.
     */
    fun getDecimalCodeRepresentation(byteArray: ByteArray): String {
        val b0 = byteArray[0].toUnsignedInt() // need unsigned byte
        val b1 = byteArray[1].toUnsignedInt() // need unsigned byte
        val b2 = byteArray[2].toUnsignedInt() // need unsigned byte
        val b3 = byteArray[3].toUnsignedInt() // need unsigned byte
        val b4 = byteArray[4].toUnsignedInt() // need unsigned byte
        // (B0 << 5 | B1 >> 3) + 1000
        val first = (b0.shl(5) or b1.shr(3)) + 1000
        // ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000
        val second = ((b1 and 0x7).shl(10) or b2.shl(2) or b3.shr(6)) + 1000
        // ((B3 & 0x3f) << 7 | B4 >> 1) + 1000
        val third = ((b3 and 0x3f).shl(7) or b4.shr(1)) + 1000
        return "$first $second $third"
    }

    override fun getEmojiCodeRepresentation(): List<EmojiRepresentation> {
        return getEmojiCodeRepresentation(shortCodeBytes!!)
    }

    /**
     * emoji: generate six bytes by using HKDF.
     * Split the first 42 bits into 7 groups of 6 bits, as one would do when creating a base64 encoding.
     * For each group of 6 bits, look up the emoji from Appendix A corresponding
     * to that number 7 emoji are selected from a list of 64 emoji (see Appendix A)
     */
    private fun getEmojiCodeRepresentation(byteArray: ByteArray): List<EmojiRepresentation> {
        val b0 = byteArray[0].toUnsignedInt()
        val b1 = byteArray[1].toUnsignedInt()
        val b2 = byteArray[2].toUnsignedInt()
        val b3 = byteArray[3].toUnsignedInt()
        val b4 = byteArray[4].toUnsignedInt()
        val b5 = byteArray[5].toUnsignedInt()
        return listOf(
                getEmojiForCode((b0 and 0xFC).shr(2)),
                getEmojiForCode((b0 and 0x3).shl(4) or (b1 and 0xF0).shr(4)),
                getEmojiForCode((b1 and 0xF).shl(2) or (b2 and 0xC0).shr(6)),
                getEmojiForCode((b2 and 0x3F)),
                getEmojiForCode((b3 and 0xFC).shr(2)),
                getEmojiForCode((b3 and 0x3).shl(4) or (b4 and 0xF0).shr(4)),
                getEmojiForCode((b4 and 0xF).shl(2) or (b5 and 0xC0).shr(6))
        )
    }
}
