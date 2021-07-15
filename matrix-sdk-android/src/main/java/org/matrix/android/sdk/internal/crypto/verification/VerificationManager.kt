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

import android.os.Handler
import android.os.Looper
import dagger.Lazy
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.MASTER_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.SELF_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.USER_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.verification.CancelCode
import org.matrix.android.sdk.api.session.crypto.verification.PendingVerificationRequest
import org.matrix.android.sdk.api.session.crypto.verification.QrCodeVerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.SasVerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.ValidVerificationInfoReady
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTxState
import org.matrix.android.sdk.api.session.crypto.verification.safeValueOf
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageRelationContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationAcceptContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationCancelContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationDoneContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationKeyContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationMacContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationReadyContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationStartContent
import org.matrix.android.sdk.api.session.room.model.message.ValidVerificationDone
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.IncomingGossipingRequestManager
import org.matrix.android.sdk.internal.crypto.MyDeviceInfoHolder
import org.matrix.android.sdk.internal.crypto.OutgoingGossipingRequestManager
import org.matrix.android.sdk.internal.crypto.actions.SetDeviceVerificationAction
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.EncryptedEventContent
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationAccept
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationCancel
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationDone
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationKey
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationMac
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationReady
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationRequest
import org.matrix.android.sdk.internal.crypto.model.rest.KeyVerificationStart
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SCAN
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SHOW
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_RECIPROCATE
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_SAS
import org.matrix.android.sdk.internal.crypto.model.rest.toValue
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.verification.qrcode.DefaultQrCodeVerificationTransaction
import org.matrix.android.sdk.internal.crypto.verification.qrcode.QrCodeData
import org.matrix.android.sdk.internal.crypto.verification.qrcode.generateSharedSecretV2
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.internal.crypto.CryptoSyncInput
import org.matrix.android.sdk.internal.session.sync.model.SyncResponse
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.collections.set

@SessionScope
internal class VerificationManager @Inject constructor(
        @UserId private val userId: String,
        @DeviceId private val deviceId: String?,
        private val cryptoStore: IMXCryptoStore,
        private val outgoingGossipingRequestManager: OutgoingGossipingRequestManager,
        private val incomingGossipingRequestManager: IncomingGossipingRequestManager,
        private val myDeviceInfoHolder: Lazy<MyDeviceInfoHolder>,
        private val deviceListManager: DeviceListManager,
        private val setDeviceVerificationAction: SetDeviceVerificationAction,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val verificationTransportRoomMessageFactory: VerificationTransportRoomMessageFactory,
        private val verificationTransportToDeviceFactory: VerificationTransportToDeviceFactory,
        private val crossSigningService: CrossSigningService,
        private val cryptoCoroutineScope: CoroutineScope,
        private val taskExecutor: TaskExecutor
) : VerificationService,
        CryptoSyncInput,
        VerificationManagerInput,
        DefaultVerificationTransaction.Listener {

    private val uiHandler = Handler(Looper.getMainLooper())

    // map [sender : [transaction]]
    private val txMap = HashMap<String, HashMap<String, DefaultVerificationTransaction>>()

    // we need to keep track of finished transaction
    // It will be used for gossiping (to send request after request is completed and 'done' by other)
    private val pastTransactions = HashMap<String, HashMap<String, DefaultVerificationTransaction>>()

    /**
     * Map [sender: [PendingVerificationRequest]]
     * For now we keep all requests (even terminated ones) during the lifetime of the app.
     */
    private val pendingRequests = HashMap<String, MutableList<PendingVerificationRequest>>()

    // Event received from the sync
    override fun onToDeviceEvent(event: Event) {
        Timber.d("## SAS onToDeviceEvent ${event.getClearType()}")
        cryptoCoroutineScope.launch(coroutineDispatchers.dmVerif) {
            when (event.getClearType()) {
                EventType.KEY_VERIFICATION_START         -> {
                    onStartRequestReceived(event)
                }
                EventType.KEY_VERIFICATION_CANCEL        -> {
                    onCancelReceived(event)
                }
                EventType.KEY_VERIFICATION_ACCEPT        -> {
                    onAcceptReceived(event)
                }
                EventType.KEY_VERIFICATION_KEY           -> {
                    onKeyReceived(event)
                }
                EventType.KEY_VERIFICATION_MAC           -> {
                    onMacReceived(event)
                }
                EventType.KEY_VERIFICATION_READY         -> {
                    onReadyReceived(event)
                }
                EventType.KEY_VERIFICATION_DONE          -> {
                    onDoneReceived(event)
                }
                MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                    onRequestReceived(event)
                }
                else                                     -> {
                    // ignore
                }
            }
        }
    }

    override fun onSyncCompleted(syncResponse: SyncResponse) {
        // No-op
    }

    override fun onRoomEvent(event: Event) {
        cryptoCoroutineScope.launch(coroutineDispatchers.dmVerif) {
            when (event.getClearType()) {
                EventType.KEY_VERIFICATION_START  -> {
                    onRoomStartRequestReceived(event)
                }
                EventType.KEY_VERIFICATION_CANCEL -> {
                    // MultiSessions | ignore events if i didn't sent the start from this device, or accepted from this device
                    onRoomCancelReceived(event)
                }
                EventType.KEY_VERIFICATION_ACCEPT -> {
                    onRoomAcceptReceived(event)
                }
                EventType.KEY_VERIFICATION_KEY    -> {
                    onRoomKeyRequestReceived(event)
                }
                EventType.KEY_VERIFICATION_MAC    -> {
                    onRoomMacReceived(event)
                }
                EventType.KEY_VERIFICATION_READY  -> {
                    onRoomReadyReceived(event)
                }
                EventType.KEY_VERIFICATION_DONE   -> {
                    onRoomDoneReceived(event)
                }
                EventType.MESSAGE                 -> {
                    if (MessageType.MSGTYPE_VERIFICATION_REQUEST == event.getClearContent().toModel<MessageContent>()?.msgType) {
                        onRoomRequestReceived(event)
                    }
                }
                else                              -> {
                    // ignore
                }
            }
        }
    }

    private var listeners = ArrayList<VerificationService.Listener>()

    override fun addListener(listener: VerificationService.Listener) {
        uiHandler.post {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    override fun removeListener(listener: VerificationService.Listener) {
        uiHandler.post {
            listeners.remove(listener)
        }
    }

    private fun dispatchTxAdded(tx: VerificationTransaction) {
        uiHandler.post {
            listeners.forEach {
                try {
                    it.transactionCreated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    private fun dispatchTxUpdated(tx: VerificationTransaction) {
        uiHandler.post {
            listeners.forEach {
                try {
                    it.transactionUpdated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    private fun dispatchRequestAdded(tx: PendingVerificationRequest) {
        Timber.v("## SAS dispatchRequestAdded txId:${tx.transactionId}")
        uiHandler.post {
            listeners.forEach {
                try {
                    it.verificationRequestCreated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    private fun dispatchRequestUpdated(tx: PendingVerificationRequest) {
        uiHandler.post {
            listeners.forEach {
                try {
                    it.verificationRequestUpdated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    override fun markedLocallyAsManuallyVerified(userId: String, deviceID: String) {
        setDeviceVerificationAction.handle(DeviceTrustLevel(false, true),
                userId,
                deviceID)

        listeners.forEach {
            try {
                it.markedAsManuallyVerified(userId, deviceID)
            } catch (e: Throwable) {
                Timber.e(e, "## Error while notifying listeners")
            }
        }
    }

    override fun onRoomRequestHandledByOtherDevice(event: Event) {
        val requestInfo = event.content.toModel<MessageRelationContent>()
                ?: return
        val requestId = requestInfo.relatesTo?.eventId ?: return
        getExistingVerificationRequestInRoom(event.roomId ?: "", requestId)?.let {
            updatePendingRequest(
                    it.copy(
                            handledByOtherSession = true
                    )
            )
        }
    }

    private fun onRequestReceived(event: Event) {
        val validRequestInfo = event.getClearContent().toModel<KeyVerificationRequest>()?.asValidObject()

        if (validRequestInfo == null) {
            // ignore
            Timber.e("## SAS Received invalid key request")
            return
        }
        val senderId = event.senderId ?: return

        // We don't want to block here
        val otherDeviceId = validRequestInfo.fromDevice

        Timber.v("## SAS onRequestReceived from $senderId and device $otherDeviceId, txId:${validRequestInfo.transactionId}")

        cryptoCoroutineScope.launch {
            if (checkKeysAreDownloaded(senderId, otherDeviceId) == null) {
                Timber.e("## Verification device $otherDeviceId is not known")
            }
        }
        Timber.v("## SAS onRequestReceived .. checkKeysAreDownloaded launched")

        // Remember this request
        val requestsForUser = pendingRequests.getOrPut(senderId) { mutableListOf() }

        val pendingVerificationRequest = PendingVerificationRequest(
                ageLocalTs = event.ageLocalTs ?: System.currentTimeMillis(),
                isIncoming = true,
                otherUserId = senderId, // requestInfo.toUserId,
                roomId = null,
                transactionId = validRequestInfo.transactionId,
                localId = validRequestInfo.transactionId,
                requestInfo = validRequestInfo
        )
        requestsForUser.add(pendingVerificationRequest)
        dispatchRequestAdded(pendingVerificationRequest)
    }

    override suspend fun onRoomRequestReceived(event: Event) {
        Timber.v("## SAS Verification request from ${event.senderId} in room ${event.roomId}")
        val requestInfo = event.getClearContent().toModel<MessageVerificationRequestContent>() ?: return
        val validRequestInfo = requestInfo
                // copy the EventId to the transactionId
                .copy(transactionId = event.eventId)
                .asValidObject() ?: return

        val senderId = event.senderId ?: return

        if (requestInfo.toUserId != userId) {
            // I should ignore this, it's not for me
            Timber.w("## SAS Verification ignoring request from ${event.senderId}, not sent to me")
            return
        }

        // We don't want to block here
        taskExecutor.executorScope.launch {
            if (checkKeysAreDownloaded(senderId, validRequestInfo.fromDevice) == null) {
                Timber.e("## SAS Verification device ${validRequestInfo.fromDevice} is not known")
            }
        }

        // Remember this request
        val requestsForUser = pendingRequests.getOrPut(senderId) { mutableListOf() }

        val pendingVerificationRequest = PendingVerificationRequest(
                ageLocalTs = event.ageLocalTs ?: System.currentTimeMillis(),
                isIncoming = true,
                otherUserId = senderId, // requestInfo.toUserId,
                roomId = event.roomId,
                transactionId = event.eventId,
                localId = event.eventId!!,
                requestInfo = validRequestInfo
        )
        requestsForUser.add(pendingVerificationRequest)
        dispatchRequestAdded(pendingVerificationRequest)

        /*
        * After the m.key.verification.ready event is sent, either party can send an m.key.verification.start event
        * to begin the verification.
        * If both parties send an m.key.verification.start event, and they both specify the same verification method,
        * then the event sent by the user whose user ID is the smallest is used, and the other m.key.verification.start
        * event is ignored.
        * In the case of a single user verifying two of their devices, the device ID is compared instead.
        * If both parties send an m.key.verification.start event, but they specify different verification methods,
        * the verification should be cancelled with a code of m.unexpected_message.
         */
    }

    override fun onPotentiallyInterestingEventRoomFailToDecrypt(event: Event) {
        // When Should/Can we cancel??
        val relationContent = event.content.toModel<EncryptedEventContent>()?.relatesTo
        if (relationContent?.type == RelationType.REFERENCE) {
            val relatedId = relationContent.eventId ?: return
            // at least if request was sent by me, I can safely cancel without interfering
            pendingRequests[event.senderId]?.firstOrNull {
                it.transactionId == relatedId && !it.isIncoming
            }?.let { pr ->
                verificationTransportRoomMessageFactory.createTransport(event.roomId ?: "", null)
                        .cancelTransaction(
                                relatedId,
                                event.senderId ?: "",
                                event.getSenderKey() ?: "",
                                CancelCode.InvalidMessage
                        )
                updatePendingRequest(pr.copy(cancelConclusion = CancelCode.InvalidMessage))
            }
        }
    }

    private suspend fun onRoomStartRequestReceived(event: Event) {
        val startReq = event.getClearContent().toModel<MessageVerificationStartContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )

        val validStartReq = startReq?.asValidObject()

        val otherUserId = event.senderId
        if (validStartReq == null) {
            Timber.e("## received invalid verification request")
            if (startReq?.transactionId != null) {
                verificationTransportRoomMessageFactory.createTransport(event.roomId ?: "", null)
                        .cancelTransaction(
                                startReq.transactionId ?: "",
                                otherUserId!!,
                                startReq.fromDevice ?: event.getSenderKey()!!,
                                CancelCode.UnknownMethod
                        )
            }
            return
        }

        handleStart(otherUserId, validStartReq) {
            it.transport = verificationTransportRoomMessageFactory.createTransport(event.roomId ?: "", it)
        }?.let {
            verificationTransportRoomMessageFactory.createTransport(event.roomId ?: "", null)
                    .cancelTransaction(
                            validStartReq.transactionId,
                            otherUserId!!,
                            validStartReq.fromDevice,
                            it
                    )
        }
    }

    private suspend fun onStartRequestReceived(event: Event) {
        Timber.e("## SAS received Start request ${event.eventId}")
        val startReq = event.getClearContent().toModel<KeyVerificationStart>()
        val validStartReq = startReq?.asValidObject()
        Timber.v("## SAS received Start request $startReq")

        val otherUserId = event.senderId!!
        if (validStartReq == null) {
            Timber.e("## SAS received invalid verification request")
            if (startReq?.transactionId != null) {
                verificationTransportToDeviceFactory.createTransport(null).cancelTransaction(
                        startReq.transactionId,
                        otherUserId,
                        startReq.fromDevice ?: event.getSenderKey()!!,
                        CancelCode.UnknownMethod
                )
            }
            return
        }
        // Download device keys prior to everything
        handleStart(otherUserId, validStartReq) {
            it.transport = verificationTransportToDeviceFactory.createTransport(it)
        }?.let {
            verificationTransportToDeviceFactory.createTransport(null).cancelTransaction(
                    validStartReq.transactionId,
                    otherUserId,
                    validStartReq.fromDevice,
                    it
            )
        }
    }

    /**
     * Return a CancelCode to make the caller cancel the verification. Else return null
     */
    private suspend fun handleStart(otherUserId: String?,
                                    startReq: ValidVerificationInfoStart,
                                    txConfigure: (DefaultVerificationTransaction) -> Unit): CancelCode? {
        Timber.d("## SAS onStartRequestReceived $startReq")
        if (otherUserId?.let { checkKeysAreDownloaded(it, startReq.fromDevice) } != null) {
            val tid = startReq.transactionId
            var existing = getExistingTransaction(otherUserId, tid)

            // After the m.key.verification.ready event is sent, either party can send an
            // m.key.verification.start event to begin the verification. If both parties
            // send an m.key.verification.start event, and they both specify the same
            // verification method, then the event sent by the user whose user ID is the
            // smallest is used, and the other m.key.verification.start event is ignored.
            // In the case of a single user verifying two of their devices, the device ID is
            // compared instead .
            if (existing is DefaultOutgoingSASDefaultVerificationTransaction) {
                val readyRequest = getExistingVerificationRequest(otherUserId, tid)
                if (readyRequest?.isReady == true) {
                    if (isOtherPrioritary(otherUserId, existing.otherDeviceId ?: "")) {
                        Timber.d("## SAS concurrent start isOtherPrioritary, clear")
                        // The other is prioritary!
                        // I should replace my outgoing with an incoming
                        removeTransaction(otherUserId, tid)
                        existing = null
                    } else {
                        Timber.d("## SAS concurrent start i am prioritary, ignore")
                        // i am prioritary, ignore this start event!
                        return null
                    }
                }
            }

            when (startReq) {
                is ValidVerificationInfoStart.SasVerificationInfoStart         -> {
                    when (existing) {
                        is SasVerificationTransaction    -> {
                            // should cancel both!
                            Timber.v("## SAS onStartRequestReceived - Request exist with same id ${startReq.transactionId}")
                            existing.cancel(CancelCode.UnexpectedMessage)
                            // Already cancelled, so return null
                            return null
                        }
                        is QrCodeVerificationTransaction -> {
                            // Nothing to do?
                        }
                        null                             -> {
                            getExistingTransactionsForUser(otherUserId)
                                    ?.filterIsInstance(SasVerificationTransaction::class.java)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.also {
                                        // Multiple keyshares between two devices:
                                        // any two devices may only have at most one key verification in flight at a time.
                                        Timber.v("## SAS onStartRequestReceived - Already a transaction with this user ${startReq.transactionId}")
                                    }
                                    ?.forEach {
                                        it.cancel(CancelCode.UnexpectedMessage)
                                    }
                                    ?.also {
                                        return CancelCode.UnexpectedMessage
                                    }
                        }
                    }

                    // Ok we can create a SAS transaction
                    Timber.v("## SAS onStartRequestReceived - request accepted ${startReq.transactionId}")
                    // If there is a corresponding request, we can auto accept
                    // as we are the one requesting in first place (or we accepted the request)
                    // I need to check if the pending request was related to this device also
                    val autoAccept = getExistingVerificationRequests(otherUserId).any {
                        it.transactionId == startReq.transactionId
                                && (it.requestInfo?.fromDevice == this.deviceId || it.readyInfo?.fromDevice == this.deviceId)
                    }
                    val tx = DefaultIncomingSASDefaultVerificationTransaction(
//                            this,
                            setDeviceVerificationAction,
                            userId,
                            deviceId,
                            cryptoStore,
                            crossSigningService,
                            outgoingGossipingRequestManager,
                            incomingGossipingRequestManager,
                            myDeviceInfoHolder.get().myDevice.fingerprint()!!,
                            startReq.transactionId,
                            otherUserId,
                            autoAccept).also { txConfigure(it) }
                    addTransaction(tx)
                    tx.onVerificationStart(startReq)
                    return null
                }
                is ValidVerificationInfoStart.ReciprocateVerificationInfoStart -> {
                    // Other user has scanned my QR code
                    if (existing is DefaultQrCodeVerificationTransaction) {
                        existing.onStartReceived(startReq)
                        return null
                    } else {
                        Timber.w("## SAS onStartRequestReceived - unexpected message ${startReq.transactionId} / $existing")
                        return CancelCode.UnexpectedMessage
                    }
                }
            }
        } else {
            return CancelCode.UnexpectedMessage
        }
    }

    private fun isOtherPrioritary(otherUserId: String, otherDeviceId: String): Boolean {
        if (userId < otherUserId) {
            return false
        } else if (userId > otherUserId) {
            return true
        } else {
            return otherDeviceId < deviceId ?: ""
        }
    }

    // TODO Refacto: It could just return a boolean
    private suspend fun checkKeysAreDownloaded(otherUserId: String,
                                               otherDeviceId: String): MXUsersDevicesMap<CryptoDeviceInfo>? {
        return try {
            var keys = deviceListManager.downloadKeys(listOf(otherUserId), false)
            if (keys.getUserDeviceIds(otherUserId)?.contains(otherDeviceId) == true) {
                return keys
            } else {
                // force download
                keys = deviceListManager.downloadKeys(listOf(otherUserId), true)
                return keys.takeIf { keys.getUserDeviceIds(otherUserId)?.contains(otherDeviceId) == true }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun onRoomCancelReceived(event: Event) {
        val cancelReq = event.getClearContent().toModel<MessageVerificationCancelContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )

        val validCancelReq = cancelReq?.asValidObject()

        if (validCancelReq == null) {
            // ignore
            Timber.e("## SAS Received invalid cancel request")
            // TODO should we cancel?
            return
        }
        getExistingVerificationRequest(event.senderId ?: "", validCancelReq.transactionId)?.let {
            updatePendingRequest(it.copy(cancelConclusion = safeValueOf(validCancelReq.code)))
            // Should we remove it from the list?
        }
        handleOnCancel(event.senderId!!, validCancelReq)
    }

    private fun onCancelReceived(event: Event) {
        Timber.v("## SAS onCancelReceived")
        val cancelReq = event.getClearContent().toModel<KeyVerificationCancel>()?.asValidObject()

        if (cancelReq == null) {
            // ignore
            Timber.e("## SAS Received invalid cancel request")
            return
        }
        val otherUserId = event.senderId!!

        handleOnCancel(otherUserId, cancelReq)
    }

    private fun handleOnCancel(otherUserId: String, cancelReq: ValidVerificationInfoCancel) {
        Timber.v("## SAS onCancelReceived otherUser: $otherUserId reason: ${cancelReq.reason}")

        val existingTransaction = getExistingTransaction(otherUserId, cancelReq.transactionId)
        val existingRequest = getExistingVerificationRequest(otherUserId, cancelReq.transactionId)

        if (existingRequest != null) {
            // Mark this request as cancelled
            updatePendingRequest(existingRequest.copy(
                    cancelConclusion = safeValueOf(cancelReq.code)
            ))
        }

        existingTransaction?.state = VerificationTxState.Cancelled(safeValueOf(cancelReq.code), false)
    }

    private fun onRoomAcceptReceived(event: Event) {
        Timber.d("##  SAS Received Accept via DM $event")
        val accept = event.getClearContent().toModel<MessageVerificationAcceptContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )
                ?: return

        val validAccept = accept.asValidObject() ?: return

        handleAccept(validAccept, event.senderId!!)
    }

    private fun onAcceptReceived(event: Event) {
        Timber.d("##  SAS Received Accept $event")
        val acceptReq = event.getClearContent().toModel<KeyVerificationAccept>()?.asValidObject() ?: return
        handleAccept(acceptReq, event.senderId!!)
    }

    private fun handleAccept(acceptReq: ValidVerificationInfoAccept, senderId: String) {
        val otherUserId = senderId
        val existing = getExistingTransaction(otherUserId, acceptReq.transactionId)
        if (existing == null) {
            Timber.e("## SAS Received invalid accept request")
            return
        }

        if (existing is SASDefaultVerificationTransaction) {
            existing.onVerificationAccept(acceptReq)
        } else {
            // not other types now
        }
    }

    private fun onRoomKeyRequestReceived(event: Event) {
        val keyReq = event.getClearContent().toModel<MessageVerificationKeyContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )
                ?.asValidObject()
        if (keyReq == null) {
            // ignore
            Timber.e("## SAS Received invalid key request")
            // TODO should we cancel?
            return
        }
        handleKeyReceived(event, keyReq)
    }

    private fun onKeyReceived(event: Event) {
        val keyReq = event.getClearContent().toModel<KeyVerificationKey>()?.asValidObject()

        if (keyReq == null) {
            // ignore
            Timber.e("## SAS Received invalid key request")
            return
        }
        handleKeyReceived(event, keyReq)
    }

    private fun handleKeyReceived(event: Event, keyReq: ValidVerificationInfoKey) {
        Timber.d("##  SAS Received Key from ${event.senderId} with info $keyReq")
        val otherUserId = event.senderId!!
        val existing = getExistingTransaction(otherUserId, keyReq.transactionId)
        if (existing == null) {
            Timber.e("##  SAS Received invalid key request")
            return
        }
        if (existing is SASDefaultVerificationTransaction) {
            existing.onKeyVerificationKey(keyReq)
        } else {
            // not other types now
        }
    }

    private fun onRoomMacReceived(event: Event) {
        val macReq = event.getClearContent().toModel<MessageVerificationMacContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )
                ?.asValidObject()
        if (macReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid mac request")
            // TODO should we cancel?
            return
        }
        handleMacReceived(event.senderId, macReq)
    }

    private suspend fun onRoomReadyReceived(event: Event) {
        val readyReq = event.getClearContent().toModel<MessageVerificationReadyContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )
                ?.asValidObject()
        if (readyReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid ready request")
            // TODO should we cancel?
            return
        }
        if (checkKeysAreDownloaded(event.senderId, readyReq.fromDevice) == null) {
            Timber.e("## SAS Verification device ${readyReq.fromDevice} is not known")
            // TODO cancel?
            return
        }

        handleReadyReceived(event.senderId, readyReq) {
            verificationTransportRoomMessageFactory.createTransport(event.roomId!!, it)
        }
    }

    private suspend fun onReadyReceived(event: Event) {
        val readyReq = event.getClearContent().toModel<KeyVerificationReady>()?.asValidObject()
        Timber.v("## SAS onReadyReceived $readyReq")

        if (readyReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid ready request")
            // TODO should we cancel?
            return
        }
        if (checkKeysAreDownloaded(event.senderId, readyReq.fromDevice) == null) {
            Timber.e("## SAS Verification device ${readyReq.fromDevice} is not known")
            // TODO cancel?
            return
        }

        handleReadyReceived(event.senderId, readyReq) {
            verificationTransportToDeviceFactory.createTransport(it)
        }
    }

    private fun onDoneReceived(event: Event) {
        Timber.v("## onDoneReceived")
        val doneReq = event.getClearContent().toModel<KeyVerificationDone>()?.asValidObject()
        if (doneReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid done request")
            return
        }

        handleDoneReceived(event.senderId, doneReq)

        if (event.senderId == userId) {
            // We only send gossiping request when the other sent us a done
            // We can ask without checking too much thinks (like trust), because we will check validity of secret on reception
            getExistingTransaction(userId, doneReq.transactionId)
                    ?: getOldTransaction(userId, doneReq.transactionId)
                            ?.let { vt ->
                                val otherDeviceId = vt.otherDeviceId
                                if (!crossSigningService.canCrossSign()) {
                                    outgoingGossipingRequestManager.sendSecretShareRequest(MASTER_KEY_SSSS_NAME, mapOf(userId to listOf(otherDeviceId
                                            ?: "*")))
                                    outgoingGossipingRequestManager.sendSecretShareRequest(SELF_SIGNING_KEY_SSSS_NAME, mapOf(userId to listOf(otherDeviceId
                                            ?: "*")))
                                    outgoingGossipingRequestManager.sendSecretShareRequest(USER_SIGNING_KEY_SSSS_NAME, mapOf(userId to listOf(otherDeviceId
                                            ?: "*")))
                                }
                                outgoingGossipingRequestManager.sendSecretShareRequest(KEYBACKUP_SECRET_SSSS_NAME, mapOf(userId to listOf(otherDeviceId
                                        ?: "*")))
                            }
        }
    }

    private fun handleDoneReceived(senderId: String, doneReq: ValidVerificationDone) {
        Timber.v("## SAS Done received $doneReq")
        val existing = getExistingTransaction(senderId, doneReq.transactionId)
        if (existing == null) {
            Timber.e("## SAS Received invalid Done request")
            return
        }
        if (existing is DefaultQrCodeVerificationTransaction) {
            existing.onDoneReceived()
        } else {
            // SAS do not care for now?
        }

        // Now transactions are updated, let's also update Requests
        val existingRequest = getExistingVerificationRequests(senderId).find { it.transactionId == doneReq.transactionId }
        if (existingRequest == null) {
            Timber.e("## SAS Received Done for unknown request txId:${doneReq.transactionId}")
            return
        }
        updatePendingRequest(existingRequest.copy(isSuccessful = true))
    }

    private fun onRoomDoneReceived(event: Event) {
        val doneReq = event.getClearContent().toModel<MessageVerificationDoneContent>()
                ?.copy(
                        // relates_to is in clear in encrypted payload
                        relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
                )
                ?.asValidObject()

        if (doneReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid Done request")
            // TODO should we cancel?
            return
        }

        handleDoneReceived(event.senderId, doneReq)
    }

    private fun onMacReceived(event: Event) {
        val macReq = event.getClearContent().toModel<KeyVerificationMac>()?.asValidObject()

        if (macReq == null || event.senderId == null) {
            // ignore
            Timber.e("## SAS Received invalid mac request")
            return
        }
        handleMacReceived(event.senderId, macReq)
    }

    private fun handleMacReceived(senderId: String, macReq: ValidVerificationInfoMac) {
        Timber.v("## SAS Received $macReq")
        val existing = getExistingTransaction(senderId, macReq.transactionId)
        if (existing == null) {
            Timber.e("## SAS Received invalid Mac request")
            return
        }
        if (existing is SASDefaultVerificationTransaction) {
            existing.onKeyVerificationMac(macReq)
        } else {
            // not other types known for now
        }
    }

    private fun handleReadyReceived(senderId: String,
                                    readyReq: ValidVerificationInfoReady,
                                    transportCreator: (DefaultVerificationTransaction) -> VerificationTransport) {
        val existingRequest = getExistingVerificationRequests(senderId).find { it.transactionId == readyReq.transactionId }
        if (existingRequest == null) {
            Timber.e("## SAS Received Ready for unknown request txId:${readyReq.transactionId} fromDevice ${readyReq.fromDevice}")
            return
        }

        val qrCodeData = readyReq.methods
                // Check if other user is able to scan QR code
                .takeIf { it.contains(VERIFICATION_METHOD_QR_CODE_SCAN) }
                ?.let {
                    createQrCodeData(existingRequest.transactionId, existingRequest.otherUserId, readyReq.fromDevice)
                }

        if (readyReq.methods.contains(VERIFICATION_METHOD_RECIPROCATE)) {
            // Create the pending transaction
            val tx = DefaultQrCodeVerificationTransaction(
                    setDeviceVerificationAction = setDeviceVerificationAction,
                    transactionId = readyReq.transactionId,
                    otherUserId = senderId,
                    otherDeviceId = readyReq.fromDevice,
                    crossSigningService = crossSigningService,
                    outgoingGossipingRequestManager = outgoingGossipingRequestManager,
                    incomingGossipingRequestManager = incomingGossipingRequestManager,
                    cryptoStore = cryptoStore,
                    qrCodeData = qrCodeData,
                    userId = userId,
                    deviceId = deviceId ?: "",
                    isIncoming = false)

            tx.transport = transportCreator.invoke(tx)

            addTransaction(tx)
        }

        updatePendingRequest(existingRequest.copy(
                readyInfo = readyReq
        ))
    }

    private fun createQrCodeData(requestId: String?, otherUserId: String, otherDeviceId: String?): QrCodeData? {
        requestId ?: run {
            Timber.w("## Unknown requestId")
            return null
        }

        return when {
            userId != otherUserId                        ->
                createQrCodeDataForDistinctUser(requestId, otherUserId)
            crossSigningService.isCrossSigningVerified() ->
                // This is a self verification and I am the old device (Osborne2)
                createQrCodeDataForVerifiedDevice(requestId, otherDeviceId)
            else                                         ->
                // This is a self verification and I am the new device (Dynabook)
                createQrCodeDataForUnVerifiedDevice(requestId)
        }
    }

    private fun createQrCodeDataForDistinctUser(requestId: String, otherUserId: String): QrCodeData.VerifyingAnotherUser? {
        val myMasterKey = crossSigningService.getMyCrossSigningKeys()
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?: run {
                    Timber.w("## Unable to get my master key")
                    return null
                }

        val otherUserMasterKey = crossSigningService.getUserCrossSigningKeys(otherUserId)
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?: run {
                    Timber.w("## Unable to get other user master key")
                    return null
                }

        return QrCodeData.VerifyingAnotherUser(
                transactionId = requestId,
                userMasterCrossSigningPublicKey = myMasterKey,
                otherUserMasterCrossSigningPublicKey = otherUserMasterKey,
                sharedSecret = generateSharedSecretV2()
        )
    }

    // Create a QR code to display on the old device (Osborne2)
    private fun createQrCodeDataForVerifiedDevice(requestId: String, otherDeviceId: String?): QrCodeData.SelfVerifyingMasterKeyTrusted? {
        val myMasterKey = crossSigningService.getMyCrossSigningKeys()
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?: run {
                    Timber.w("## Unable to get my master key")
                    return null
                }

        val otherDeviceKey = otherDeviceId
                ?.let {
                    cryptoStore.getUserDevice(userId, otherDeviceId)?.fingerprint()
                }
                ?: run {
                    Timber.w("## Unable to get other device data")
                    return null
                }

        return QrCodeData.SelfVerifyingMasterKeyTrusted(
                transactionId = requestId,
                userMasterCrossSigningPublicKey = myMasterKey,
                otherDeviceKey = otherDeviceKey,
                sharedSecret = generateSharedSecretV2()
        )
    }

    // Create a QR code to display on the new device (Dynabook)
    private fun createQrCodeDataForUnVerifiedDevice(requestId: String): QrCodeData.SelfVerifyingMasterKeyNotTrusted? {
        val myMasterKey = crossSigningService.getMyCrossSigningKeys()
                ?.masterKey()
                ?.unpaddedBase64PublicKey
                ?: run {
                    Timber.w("## Unable to get my master key")
                    return null
                }

        val myDeviceKey = myDeviceInfoHolder.get().myDevice.fingerprint()
                ?: run {
                    Timber.w("## Unable to get my fingerprint")
                    return null
                }

        return QrCodeData.SelfVerifyingMasterKeyNotTrusted(
                transactionId = requestId,
                deviceKey = myDeviceKey,
                userMasterCrossSigningPublicKey = myMasterKey,
                sharedSecret = generateSharedSecretV2()
        )
    }

//    private fun handleDoneReceived(senderId: String, doneInfo: ValidVerificationDone) {
//        val existingRequest = getExistingVerificationRequest(senderId)?.find { it.transactionId == doneInfo.transactionId }
//        if (existingRequest == null) {
//            Timber.e("## SAS Received Done for unknown request txId:${doneInfo.transactionId}")
//            return
//        }
//        updatePendingRequest(existingRequest.copy(isSuccessful = true))
//    }

    // TODO All this methods should be delegated to a TransactionStore
    override fun getExistingTransaction(otherUserId: String, tid: String): VerificationTransaction? {
        synchronized(lock = txMap) {
            return txMap[otherUserId]?.get(tid)
        }
    }

    override fun getExistingVerificationRequests(otherUserId: String): List<PendingVerificationRequest> {
        synchronized(lock = pendingRequests) {
            return pendingRequests[otherUserId].orEmpty()
        }
    }

    override fun getExistingVerificationRequest(otherUserId: String, tid: String?): PendingVerificationRequest? {
        synchronized(lock = pendingRequests) {
            return tid?.let { tid -> pendingRequests[otherUserId]?.firstOrNull { it.transactionId == tid } }
        }
    }

    override fun getExistingVerificationRequestInRoom(roomId: String, tid: String?): PendingVerificationRequest? {
        synchronized(lock = pendingRequests) {
            return tid?.let { tid ->
                pendingRequests.flatMap { entry ->
                    entry.value.filter { it.roomId == roomId && it.transactionId == tid }
                }.firstOrNull()
            }
        }
    }

    private fun getExistingTransactionsForUser(otherUser: String): Collection<VerificationTransaction>? {
        synchronized(txMap) {
            return txMap[otherUser]?.values
        }
    }

    private fun removeTransaction(otherUser: String, tid: String) {
        synchronized(txMap) {
            txMap[otherUser]?.remove(tid)?.also {
                it.removeListener(this)
            }
        }?.let {
            rememberOldTransaction(it)
        }
    }

    private fun addTransaction(tx: DefaultVerificationTransaction) {
        synchronized(txMap) {
            val txInnerMap = txMap.getOrPut(tx.otherUserId) { HashMap() }
            txInnerMap[tx.transactionId] = tx
            dispatchTxAdded(tx)
            tx.addListener(this)
        }
    }

    private fun rememberOldTransaction(tx: DefaultVerificationTransaction) {
        synchronized(pastTransactions) {
            pastTransactions.getOrPut(tx.otherUserId) { HashMap() }[tx.transactionId] = tx
        }
    }

    private fun getOldTransaction(userId: String, tid: String?): DefaultVerificationTransaction? {
        return tid?.let {
            synchronized(pastTransactions) {
                pastTransactions[userId]?.get(it)
            }
        }
    }

    override fun beginKeyVerification(method: VerificationMethod, otherUserId: String, otherDeviceId: String, transactionId: String?): String? {
        val txID = transactionId?.takeIf { it.isNotEmpty() } ?: createUniqueIDForTransaction(otherUserId, otherDeviceId)
        // should check if already one (and cancel it)
        if (method == VerificationMethod.SAS) {
            val tx = DefaultOutgoingSASDefaultVerificationTransaction(
                    setDeviceVerificationAction,
                    userId,
                    deviceId,
                    cryptoStore,
                    crossSigningService,
                    outgoingGossipingRequestManager,
                    incomingGossipingRequestManager,
                    myDeviceInfoHolder.get().myDevice.fingerprint()!!,
                    txID,
                    otherUserId,
                    otherDeviceId)
            tx.transport = verificationTransportToDeviceFactory.createTransport(tx)
            addTransaction(tx)

            tx.start()
            return txID
        } else {
            throw IllegalArgumentException("Unknown verification method")
        }
    }

    override fun requestKeyVerificationInDMs(methods: List<VerificationMethod>, otherUserId: String, roomId: String, localId: String?)
            : PendingVerificationRequest {
        Timber.i("## SAS Requesting verification to user: $otherUserId in room $roomId")

        val requestsForUser = pendingRequests.getOrPut(otherUserId) { mutableListOf() }

        val transport = verificationTransportRoomMessageFactory.createTransport(roomId, null)

        // Cancel existing pending requests?
        requestsForUser.toList().forEach { existingRequest ->
            existingRequest.transactionId?.let { tid ->
                if (!existingRequest.isFinished) {
                    Timber.d("## SAS, cancelling pending requests to start a new one")
                    updatePendingRequest(existingRequest.copy(cancelConclusion = CancelCode.User))
                    transport.cancelTransaction(tid, existingRequest.otherUserId, "", CancelCode.User)
                }
            }
        }

        val validLocalId = localId ?: LocalEcho.createLocalEchoId()

        val verificationRequest = PendingVerificationRequest(
                ageLocalTs = System.currentTimeMillis(),
                isIncoming = false,
                roomId = roomId,
                localId = validLocalId,
                otherUserId = otherUserId
        )

        // We can SCAN or SHOW QR codes only if cross-signing is verified
        val methodValues = if (crossSigningService.isCrossSigningVerified()) {
            // Add reciprocate method if application declares it can scan or show QR codes
            // Not sure if it ok to do that (?)
            val reciprocateMethod = methods
                    .firstOrNull { it == VerificationMethod.QR_CODE_SCAN || it == VerificationMethod.QR_CODE_SHOW }
                    ?.let { listOf(VERIFICATION_METHOD_RECIPROCATE) }.orEmpty()
            methods.map { it.toValue() } + reciprocateMethod
        } else {
            // Filter out SCAN and SHOW qr code method
            methods
                    .filter { it != VerificationMethod.QR_CODE_SHOW && it != VerificationMethod.QR_CODE_SCAN }
                    .map { it.toValue() }
        }
                .distinct()

        transport.sendVerificationRequest(methodValues, validLocalId, otherUserId, roomId, null) { syncedId, info ->
            // We need to update with the syncedID
            updatePendingRequest(verificationRequest.copy(
                    transactionId = syncedId,
                    // localId stays different
                    requestInfo = info
            ))
        }

        requestsForUser.add(verificationRequest)
        dispatchRequestAdded(verificationRequest)

        return verificationRequest
    }

    override fun cancelVerificationRequest(request: PendingVerificationRequest) {
        if (request.roomId != null) {
            val transport = verificationTransportRoomMessageFactory.createTransport(request.roomId, null)
            transport.cancelTransaction(request.transactionId ?: "", request.otherUserId, null, CancelCode.User)
        } else {
            val transport = verificationTransportToDeviceFactory.createTransport(null)
            request.targetDevices?.forEach { deviceId ->
                transport.cancelTransaction(request.transactionId ?: "", request.otherUserId, deviceId, CancelCode.User)
            }
        }
    }

    override fun requestKeyVerification(methods: List<VerificationMethod>, otherUserId: String, otherDevices: List<String>?): PendingVerificationRequest {
        // TODO refactor this with the DM one
        Timber.i("## Requesting verification to user: $otherUserId with device list $otherDevices")

        val targetDevices = otherDevices ?: cryptoStore.getUserDevices(otherUserId)
                ?.values?.map { it.deviceId }.orEmpty()

        val requestsForUser = pendingRequests.getOrPut(otherUserId) { mutableListOf() }

        val transport = verificationTransportToDeviceFactory.createTransport(null)

        // Cancel existing pending requests?
        requestsForUser.toList().forEach { existingRequest ->
            existingRequest.transactionId?.let { tid ->
                if (!existingRequest.isFinished) {
                    Timber.d("## SAS, cancelling pending requests to start a new one")
                    updatePendingRequest(existingRequest.copy(cancelConclusion = CancelCode.User))
                    existingRequest.targetDevices?.forEach {
                        transport.cancelTransaction(tid, existingRequest.otherUserId, it, CancelCode.User)
                    }
                }
            }
        }

        val localId = LocalEcho.createLocalEchoId()

        val verificationRequest = PendingVerificationRequest(
                transactionId = localId,
                ageLocalTs = System.currentTimeMillis(),
                isIncoming = false,
                roomId = null,
                localId = localId,
                otherUserId = otherUserId,
                targetDevices = targetDevices
        )

        // We can SCAN or SHOW QR codes only if cross-signing is enabled
        val methodValues = if (crossSigningService.isCrossSigningInitialized()) {
            // Add reciprocate method if application declares it can scan or show QR codes
            // Not sure if it ok to do that (?)
            val reciprocateMethod = methods
                    .firstOrNull { it == VerificationMethod.QR_CODE_SCAN || it == VerificationMethod.QR_CODE_SHOW }
                    ?.let { listOf(VERIFICATION_METHOD_RECIPROCATE) }.orEmpty()
            methods.map { it.toValue() } + reciprocateMethod
        } else {
            // Filter out SCAN and SHOW qr code method
            methods
                    .filter { it != VerificationMethod.QR_CODE_SHOW && it != VerificationMethod.QR_CODE_SCAN }
                    .map { it.toValue() }
        }
                .distinct()

        transport.sendVerificationRequest(methodValues, localId, otherUserId, null, targetDevices) { _, info ->
            // Nothing special to do in to device mode
            updatePendingRequest(verificationRequest.copy(
                    // localId stays different
                    requestInfo = info
            ))
        }

        requestsForUser.add(verificationRequest)
        dispatchRequestAdded(verificationRequest)

        return verificationRequest
    }

    override fun declineVerificationRequestInDMs(otherUserId: String, transactionId: String, roomId: String) {
        verificationTransportRoomMessageFactory.createTransport(roomId, null)
                .cancelTransaction(transactionId, otherUserId, null, CancelCode.User)

        getExistingVerificationRequest(otherUserId, transactionId)?.let {
            updatePendingRequest(it.copy(
                    cancelConclusion = CancelCode.User
            ))
        }
    }

    private fun updatePendingRequest(updated: PendingVerificationRequest) {
        val requestsForUser = pendingRequests.getOrPut(updated.otherUserId) { mutableListOf() }
        val index = requestsForUser.indexOfFirst {
            it.transactionId == updated.transactionId
                    || it.transactionId == null && it.localId == updated.localId
        }
        if (index != -1) {
            requestsForUser.removeAt(index)
        }
        requestsForUser.add(updated)
        dispatchRequestUpdated(updated)
    }

    override fun beginKeyVerificationInDMs(method: VerificationMethod,
                                           transactionId: String,
                                           roomId: String,
                                           otherUserId: String,
                                           otherDeviceId: String): String {
        if (method == VerificationMethod.SAS) {
            val tx = DefaultOutgoingSASDefaultVerificationTransaction(
                    setDeviceVerificationAction,
                    userId,
                    deviceId,
                    cryptoStore,
                    crossSigningService,
                    outgoingGossipingRequestManager,
                    incomingGossipingRequestManager,
                    myDeviceInfoHolder.get().myDevice.fingerprint()!!,
                    transactionId,
                    otherUserId,
                    otherDeviceId)
            tx.transport = verificationTransportRoomMessageFactory.createTransport(roomId, tx)
            addTransaction(tx)

            tx.start()
            return transactionId
        } else {
            throw IllegalArgumentException("Unknown verification method")
        }
    }

    override fun readyPendingVerificationInDMs(methods: List<VerificationMethod>,
                                               otherUserId: String,
                                               roomId: String,
                                               transactionId: String): Boolean {
        Timber.v("## SAS readyPendingVerificationInDMs $otherUserId room:$roomId tx:$transactionId")
        // Let's find the related request
        val existingRequest = getExistingVerificationRequest(otherUserId, transactionId)
        if (existingRequest != null) {
            // we need to send a ready event, with matching methods
            val transport = verificationTransportRoomMessageFactory.createTransport(roomId, null)
            val computedMethods = computeReadyMethods(
                    transactionId,
                    otherUserId,
                    existingRequest.requestInfo?.fromDevice ?: "",
                    existingRequest.requestInfo?.methods,
                    methods) {
                verificationTransportRoomMessageFactory.createTransport(roomId, it)
            }
            if (methods.isNullOrEmpty()) {
                Timber.i("Cannot ready this request, no common methods found txId:$transactionId")
                // TODO buttons should not be shown in  this case?
                return false
            }
            // TODO this is not yet related to a transaction, maybe we should use another method like for cancel?
            val readyMsg = transport.createReady(transactionId, deviceId ?: "", computedMethods)
            transport.sendToOther(EventType.KEY_VERIFICATION_READY,
                    readyMsg,
                    VerificationTxState.None,
                    CancelCode.User,
                    null // TODO handle error?
            )
            updatePendingRequest(existingRequest.copy(readyInfo = readyMsg.asValidObject()))
            return true
        } else {
            Timber.e("## SAS readyPendingVerificationInDMs Verification not found")
            // :/ should not be possible... unless live observer very slow
            return false
        }
    }

    override fun readyPendingVerification(methods: List<VerificationMethod>,
                                          otherUserId: String,
                                          transactionId: String): Boolean {
        Timber.v("## SAS readyPendingVerification $otherUserId tx:$transactionId")
        // Let's find the related request
        val existingRequest = getExistingVerificationRequest(otherUserId, transactionId)
        if (existingRequest != null) {
            // we need to send a ready event, with matching methods
            val transport = verificationTransportToDeviceFactory.createTransport(null)
            val computedMethods = computeReadyMethods(
                    transactionId,
                    otherUserId,
                    existingRequest.requestInfo?.fromDevice ?: "",
                    existingRequest.requestInfo?.methods,
                    methods) {
                verificationTransportToDeviceFactory.createTransport(it)
            }
            if (methods.isNullOrEmpty()) {
                Timber.i("Cannot ready this request, no common methods found txId:$transactionId")
                // TODO buttons should not be shown in this case?
                return false
            }
            // TODO this is not yet related to a transaction, maybe we should use another method like for cancel?
            val readyMsg = transport.createReady(transactionId, deviceId ?: "", computedMethods)
            transport.sendVerificationReady(
                    readyMsg,
                    otherUserId,
                    existingRequest.requestInfo?.fromDevice ?: "",
                    null // TODO handle error?
            )
            updatePendingRequest(existingRequest.copy(readyInfo = readyMsg.asValidObject()))
            return true
        } else {
            Timber.e("## SAS readyPendingVerification Verification not found")
            // :/ should not be possible... unless live observer very slow
            return false
        }
    }

    private fun computeReadyMethods(
            transactionId: String,
            otherUserId: String,
            otherDeviceId: String,
            otherUserMethods: List<String>?,
            methods: List<VerificationMethod>,
            transportCreator: (DefaultVerificationTransaction) -> VerificationTransport): List<String> {
        if (otherUserMethods.isNullOrEmpty()) {
            return emptyList()
        }

        val result = mutableSetOf<String>()

        if (VERIFICATION_METHOD_SAS in otherUserMethods && VerificationMethod.SAS in methods) {
            // Other can do SAS and so do I
            result.add(VERIFICATION_METHOD_SAS)
        }

        if (VERIFICATION_METHOD_QR_CODE_SCAN in otherUserMethods || VERIFICATION_METHOD_QR_CODE_SHOW in otherUserMethods) {
            // Other user wants to verify using QR code. Cross-signing has to be setup
            val qrCodeData = createQrCodeData(transactionId, otherUserId, otherDeviceId)

            if (qrCodeData != null) {
                if (VERIFICATION_METHOD_QR_CODE_SCAN in otherUserMethods && VerificationMethod.QR_CODE_SHOW in methods) {
                    // Other can Scan and I can show QR code
                    result.add(VERIFICATION_METHOD_QR_CODE_SHOW)
                    result.add(VERIFICATION_METHOD_RECIPROCATE)
                }
                if (VERIFICATION_METHOD_QR_CODE_SHOW in otherUserMethods && VerificationMethod.QR_CODE_SCAN in methods) {
                    // Other can show and I can scan QR code
                    result.add(VERIFICATION_METHOD_QR_CODE_SCAN)
                    result.add(VERIFICATION_METHOD_RECIPROCATE)
                }
            }

            if (VERIFICATION_METHOD_RECIPROCATE in result) {
                // Create the pending transaction
                val tx = DefaultQrCodeVerificationTransaction(
                        setDeviceVerificationAction = setDeviceVerificationAction,
                        transactionId = transactionId,
                        otherUserId = otherUserId,
                        otherDeviceId = otherDeviceId,
                        crossSigningService = crossSigningService,
                        outgoingGossipingRequestManager = outgoingGossipingRequestManager,
                        incomingGossipingRequestManager = incomingGossipingRequestManager,
                        cryptoStore = cryptoStore,
                        qrCodeData = qrCodeData,
                        userId = userId,
                        deviceId = deviceId ?: "",
                        isIncoming = false)

                tx.transport = transportCreator.invoke(tx)

                addTransaction(tx)
            }
        }

        return result.toList()
    }

    /**
     * This string must be unique for the pair of users performing verification for the duration that the transaction is valid
     */
    private fun createUniqueIDForTransaction(otherUserId: String, otherDeviceID: String): String {
        return buildString {
            append(userId).append("|")
            append(deviceId).append("|")
            append(otherUserId).append("|")
            append(otherDeviceID).append("|")
            append(UUID.randomUUID().toString())
        }
    }

    override fun transactionUpdated(tx: VerificationTransaction) {
        dispatchTxUpdated(tx)
        if (tx.state is VerificationTxState.TerminalTxState) {
            // remove
            this.removeTransaction(tx.otherUserId, tx.transactionId)
        }
    }
}
