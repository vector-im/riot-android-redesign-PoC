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

package org.matrix.android.sdk.rx

import androidx.paging.PagedList
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.Function3
import kotlinx.coroutines.rx2.rxSingle
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.MASTER_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.crypto.crosssigning.SELF_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.USER_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.group.GroupSummaryQueryParams
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.pushers.Pusher
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.space.SpaceSummaryQueryParams
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import org.matrix.android.sdk.internal.crypto.store.PrivateKeysInfo
import org.matrix.android.sdk.internal.session.room.alias.RoomAliasDescription

class RxSession(private val session: Session) {

    fun liveRoomSummaries(queryParams: RoomSummaryQueryParams): Observable<List<RoomSummary>> {
        return session.getRoomSummariesLive(queryParams).asObservable()
                .startWithCallable {
                    session.getRoomSummaries(queryParams)
                }
    }

    fun liveGroupSummaries(queryParams: GroupSummaryQueryParams): Observable<List<GroupSummary>> {
        return session.getGroupSummariesLive(queryParams).asObservable()
                .startWithCallable {
                    session.getGroupSummaries(queryParams)
                }
    }

    fun liveSpaceSummaries(queryParams: SpaceSummaryQueryParams): Observable<List<RoomSummary>> {
        return session.spaceService().getSpaceSummariesLive(queryParams).asObservable()
                .startWithCallable {
                    session.spaceService().getSpaceSummaries(queryParams)
                }
    }

    fun liveBreadcrumbs(queryParams: RoomSummaryQueryParams): Observable<List<RoomSummary>> {
        return session.getBreadcrumbsLive(queryParams).asObservable()
                .startWithCallable {
                    session.getBreadcrumbs(queryParams)
                }
    }

    fun liveMyDevicesInfo(): Observable<List<DeviceInfo>> {
        return session.cryptoService().getLiveMyDevicesInfo().asObservable()
                .startWithCallable {
                    session.cryptoService().getMyDevicesInfo()
                }
    }

    fun liveSyncState(): Observable<SyncState> {
        return session.getSyncStateLive().asObservable()
    }

    fun livePushers(): Observable<List<Pusher>> {
        return session.getPushersLive().asObservable()
    }

    fun liveUser(userId: String): Observable<Optional<User>> {
        return session.getUserLive(userId).asObservable()
                .startWithCallable {
                    session.getUser(userId).toOptional()
                }
    }

    fun liveRoomMember(userId: String, roomId: String): Observable<Optional<RoomMemberSummary>> {
        return session.getRoomMemberLive(userId, roomId).asObservable()
                .startWithCallable {
                    session.getRoomMember(userId, roomId).toOptional()
                }
    }

    fun liveUsers(): Observable<List<User>> {
        return session.getUsersLive().asObservable()
    }

    fun liveIgnoredUsers(): Observable<List<User>> {
        return session.getIgnoredUsersLive().asObservable()
    }

    fun livePagedUsers(filter: String? = null, excludedUserIds: Set<String>? = null): Observable<PagedList<User>> {
        return session.getPagedUsersLive(filter, excludedUserIds).asObservable()
    }

    fun liveThreePIds(refreshData: Boolean): Observable<List<ThreePid>> {
        return session.getThreePidsLive(refreshData).asObservable()
                .startWithCallable { session.getThreePids() }
    }

    fun livePendingThreePIds(): Observable<List<ThreePid>> {
        return session.getPendingThreePidsLive().asObservable()
                .startWithCallable { session.getPendingThreePids() }
    }

    fun createRoom(roomParams: CreateRoomParams): Single<String> = rxSingle {
        session.createRoom(roomParams)
    }

    fun searchUsersDirectory(search: String,
                             limit: Int,
                             excludedUserIds: Set<String>): Single<List<User>> = rxSingle {
        session.searchUsersDirectory(search, limit, excludedUserIds)
    }

    fun joinRoom(roomIdOrAlias: String,
                 reason: String? = null,
                 viaServers: List<String> = emptyList()): Single<Unit> = rxSingle {
        session.joinRoom(roomIdOrAlias, reason, viaServers)
    }

    fun getRoomIdByAlias(roomAlias: String,
                         searchOnServer: Boolean): Single<Optional<RoomAliasDescription>> = rxSingle {
        session.getRoomIdByAlias(roomAlias, searchOnServer)
    }

    fun getProfileInfo(userId: String): Single<JsonDict> = rxSingle {
        session.getProfile(userId)
    }

    fun liveUserCryptoDevices(userId: String): Observable<List<CryptoDeviceInfo>> {
        return session.cryptoService().getLiveCryptoDeviceInfo(userId).asObservable().startWithCallable {
            session.cryptoService().getCryptoDeviceInfo(userId)
        }
    }

    fun liveCrossSigningInfo(userId: String): Observable<Optional<MXCrossSigningInfo>> {
        return session.cryptoService().crossSigningService().getLiveCrossSigningKeys(userId).asObservable()
                .startWithCallable {
                    session.cryptoService().crossSigningService().getUserCrossSigningKeys(userId).toOptional()
                }
    }

    fun liveCrossSigningPrivateKeys(): Observable<Optional<PrivateKeysInfo>> {
        return session.cryptoService().crossSigningService().getLiveCrossSigningPrivateKeys().asObservable()
                .startWithCallable {
                    session.cryptoService().crossSigningService().getCrossSigningPrivateKeys().toOptional()
                }
    }

    fun liveUserAccountData(types: Set<String>): Observable<List<AccountDataEvent>> {
        return session.userAccountDataService().getLiveAccountDataEvents(types).asObservable()
                .startWithCallable {
                    session.userAccountDataService().getAccountDataEvents(types)
                }
    }

    fun liveRoomWidgets(
            roomId: String,
            widgetId: QueryStringValue,
            widgetTypes: Set<String>? = null,
            excludedTypes: Set<String>? = null
    ): Observable<List<Widget>> {
        return session.widgetService().getRoomWidgetsLive(roomId, widgetId, widgetTypes, excludedTypes).asObservable()
                .startWithCallable {
                    session.widgetService().getRoomWidgets(roomId, widgetId, widgetTypes, excludedTypes)
                }
    }

    fun liveRoomChangeMembershipState(): Observable<Map<String, ChangeMembershipState>> {
        return session.getChangeMembershipsLive().asObservable()
    }

    fun liveSecretSynchronisationInfo(): Observable<SecretsSynchronisationInfo> {
        return Observable.combineLatest<List<AccountDataEvent>, Optional<MXCrossSigningInfo>, Optional<PrivateKeysInfo>, SecretsSynchronisationInfo>(
                liveUserAccountData(setOf(MASTER_KEY_SSSS_NAME, USER_SIGNING_KEY_SSSS_NAME, SELF_SIGNING_KEY_SSSS_NAME, KEYBACKUP_SECRET_SSSS_NAME)),
                liveCrossSigningInfo(session.myUserId),
                liveCrossSigningPrivateKeys(),
                Function3 { _, crossSigningInfo, pInfo ->
                    // first check if 4S is already setup
                    val is4SSetup = session.sharedSecretStorageService.isRecoverySetup()
                    val isCrossSigningEnabled = crossSigningInfo.getOrNull() != null
                    val isCrossSigningTrusted = crossSigningInfo.getOrNull()?.isTrusted() == true
                    val allPrivateKeysKnown = pInfo.getOrNull()?.allKnown().orFalse()

                    val keysBackupService = session.cryptoService().keysBackupService()
                    val currentBackupVersion = keysBackupService.currentBackupVersion
                    val megolmBackupAvailable = currentBackupVersion != null
                    val savedBackupKey = keysBackupService.getKeyBackupRecoveryKeyInfo()

                    val megolmKeyKnown = savedBackupKey?.version == currentBackupVersion
                    SecretsSynchronisationInfo(
                            isBackupSetup = is4SSetup,
                            isCrossSigningEnabled = isCrossSigningEnabled,
                            isCrossSigningTrusted = isCrossSigningTrusted,
                            allPrivateKeysKnown = allPrivateKeysKnown,
                            megolmBackupAvailable = megolmBackupAvailable,
                            megolmSecretKnown = megolmKeyKnown,
                            isMegolmKeyIn4S = session.sharedSecretStorageService.isMegolmKeyInBackup()
                    )
                }
        )
                .distinctUntilChanged()
    }
}

fun Session.rx(): RxSession {
    return RxSession(this)
}
