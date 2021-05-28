/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.summary

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import com.zhuinden.monarchy.Monarchy
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.Sort
import io.realm.kotlin.where
import org.matrix.android.sdk.api.query.ActiveSpaceFilter
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.session.room.ResultBoundaries
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import org.matrix.android.sdk.api.session.space.SpaceSummaryQueryParams
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.RoomSummaryMapper
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntityFields
import org.matrix.android.sdk.internal.database.query.findByAlias
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.query.process
import org.matrix.android.sdk.internal.util.fetchCopyMap
import javax.inject.Inject

internal class RoomSummaryDataSource @Inject constructor(@SessionDatabase private val monarchy: Monarchy,
                                                         private val roomSummaryMapper: RoomSummaryMapper) {

    fun getRoomSummary(roomIdOrAlias: String): RoomSummary? {
        return monarchy
                .fetchCopyMap({
                    if (roomIdOrAlias.startsWith("!")) {
                        // It's a roomId
                        RoomSummaryEntity.where(it, roomId = roomIdOrAlias).findFirst()
                    } else {
                        // Assume it's a room alias
                        RoomSummaryEntity.findByAlias(it, roomIdOrAlias)
                    }
                }, { entity, _ ->
                    roomSummaryMapper.map(entity)
                })
    }

    fun getRoomSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm -> RoomSummaryEntity.where(realm, roomId).isNotEmpty(RoomSummaryEntityFields.DISPLAY_NAME) },
                { roomSummaryMapper.map(it) }
        )
        return Transformations.map(liveData) { results ->
            results.firstOrNull().toOptional()
        }
    }

    fun getRoomSummaries(queryParams: RoomSummaryQueryParams): List<RoomSummary> {
        return monarchy.fetchAllMappedSync(
                { roomSummariesQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    fun getRoomSummariesLive(queryParams: RoomSummaryQueryParams): LiveData<List<RoomSummary>> {
        return monarchy.findAllMappedWithChanges(
                {
                    roomSummariesQuery(it, queryParams)
                            .sort(RoomSummaryEntityFields.LAST_ACTIVITY_TIME, Sort.DESCENDING)
                },
                { roomSummaryMapper.map(it) }
        )
    }

    fun getSpaceSummariesLive(queryParams: SpaceSummaryQueryParams): LiveData<List<RoomSummary>> {
        return getRoomSummariesLive(queryParams)
    }

    fun getSpaceSummary(roomIdOrAlias: String): RoomSummary? {
        return getRoomSummary(roomIdOrAlias)
                ?.takeIf { it.roomType == RoomType.SPACE }
    }

    fun getSpaceSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm ->
                    RoomSummaryEntity.where(realm, roomId)
                            .isNotEmpty(RoomSummaryEntityFields.DISPLAY_NAME)
                            .equalTo(RoomSummaryEntityFields.ROOM_TYPE, RoomType.SPACE)
                },
                {
                    roomSummaryMapper.map(it)
                }
        )
        return Transformations.map(liveData) { results ->
            results.firstOrNull().toOptional()
        }
    }

    fun getSpaceSummaries(spaceSummaryQueryParams: SpaceSummaryQueryParams): List<RoomSummary> {
        return getRoomSummaries(spaceSummaryQueryParams)
    }

    fun getRootSpaceSummaries(): List<RoomSummary> {
        return getRoomSummaries(spaceSummaryQueryParams {
            memberships = listOf(Membership.JOIN)
        })
                .let { allJoinedSpace ->
                    val allFlattenChildren = arrayListOf<RoomSummary>()
                    allJoinedSpace.forEach {
                        flattenSubSpace(it, emptyList(), allFlattenChildren, listOf(Membership.JOIN), false)
                    }
                    val knownNonOrphan = allFlattenChildren.map { it.roomId }.distinct()
                    // keep only root rooms
                    allJoinedSpace.filter { candidate ->
                        !knownNonOrphan.contains(candidate.roomId)
                    }
                }
    }

    fun getBreadcrumbs(queryParams: RoomSummaryQueryParams): List<RoomSummary> {
        return monarchy.fetchAllMappedSync(
                { breadcrumbsQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    fun getBreadcrumbsLive(queryParams: RoomSummaryQueryParams): LiveData<List<RoomSummary>> {
        return monarchy.findAllMappedWithChanges(
                { breadcrumbsQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    private fun breadcrumbsQuery(realm: Realm, queryParams: RoomSummaryQueryParams): RealmQuery<RoomSummaryEntity> {
        return roomSummariesQuery(realm, queryParams)
                .greaterThan(RoomSummaryEntityFields.BREADCRUMBS_INDEX, RoomSummary.NOT_IN_BREADCRUMBS)
                .sort(RoomSummaryEntityFields.BREADCRUMBS_INDEX)
    }

    fun getSortedPagedRoomSummariesLive(queryParams: RoomSummaryQueryParams,
                                        pagedListConfig: PagedList.Config,
                                        sortOrder: RoomSortOrder): LiveData<PagedList<RoomSummary>> {
        val realmDataSourceFactory = monarchy.createDataSourceFactory { realm ->
            roomSummariesQuery(realm, queryParams).process(sortOrder)
        }
        val dataSourceFactory = realmDataSourceFactory.map {
            roomSummaryMapper.map(it)
        }
        return monarchy.findAllPagedWithChanges(
                realmDataSourceFactory,
                LivePagedListBuilder(dataSourceFactory, pagedListConfig)
        )
    }

    fun getUpdatablePagedRoomSummariesLive(queryParams: RoomSummaryQueryParams,
                                           pagedListConfig: PagedList.Config,
                                           sortOrder: RoomSortOrder): UpdatableLivePageResult {
        val realmDataSourceFactory = monarchy.createDataSourceFactory { realm ->
            roomSummariesQuery(realm, queryParams).process(sortOrder)
        }
        val dataSourceFactory = realmDataSourceFactory.map {
            roomSummaryMapper.map(it)
        }

        val boundaries = MutableLiveData(ResultBoundaries())

        val mapped = monarchy.findAllPagedWithChanges(
                realmDataSourceFactory,
                LivePagedListBuilder(dataSourceFactory, pagedListConfig).also {
                    it.setBoundaryCallback(object : PagedList.BoundaryCallback<RoomSummary>() {
                        override fun onItemAtEndLoaded(itemAtEnd: RoomSummary) {
                            boundaries.postValue(boundaries.value?.copy(frontLoaded = true))
                        }

                        override fun onItemAtFrontLoaded(itemAtFront: RoomSummary) {
                            boundaries.postValue(boundaries.value?.copy(endLoaded = true))
                        }

                        override fun onZeroItemsLoaded() {
                            boundaries.postValue(boundaries.value?.copy(zeroItemLoaded = true))
                        }
                    })
                }
        )

        return object : UpdatableLivePageResult {
            override val livePagedList: LiveData<PagedList<RoomSummary>> = mapped

            override fun updateQuery(builder: (RoomSummaryQueryParams) -> RoomSummaryQueryParams) {
                realmDataSourceFactory.updateQuery {
                    roomSummariesQuery(it, builder.invoke(queryParams)).process(sortOrder)
                }
            }

            override val liveBoundaries: LiveData<ResultBoundaries>
                get() = boundaries
        }
    }

    fun getNotificationCountForRooms(queryParams: RoomSummaryQueryParams): RoomAggregateNotificationCount {
        var notificationCount: RoomAggregateNotificationCount? = null
        monarchy.doWithRealm { realm ->
            val roomSummariesQuery = roomSummariesQuery(realm, queryParams)
            val notifCount = roomSummariesQuery.sum(RoomSummaryEntityFields.NOTIFICATION_COUNT).toInt()
            val highlightCount = roomSummariesQuery.sum(RoomSummaryEntityFields.HIGHLIGHT_COUNT).toInt()
            notificationCount = RoomAggregateNotificationCount(
                    notifCount,
                    highlightCount
            )
        }
        return notificationCount!!
    }

    private fun roomSummariesQuery(realm: Realm, queryParams: RoomSummaryQueryParams): RealmQuery<RoomSummaryEntity> {
        val query = RoomSummaryEntity.where(realm)
        query.process(RoomSummaryEntityFields.ROOM_ID, queryParams.roomId)
        query.process(RoomSummaryEntityFields.DISPLAY_NAME, queryParams.displayName)
        query.process(RoomSummaryEntityFields.CANONICAL_ALIAS, queryParams.canonicalAlias)
        query.process(RoomSummaryEntityFields.MEMBERSHIP_STR, queryParams.memberships)
        query.equalTo(RoomSummaryEntityFields.IS_HIDDEN_FROM_USER, false)

        queryParams.roomCategoryFilter?.let {
            when (it) {
                RoomCategoryFilter.ONLY_DM -> query.equalTo(RoomSummaryEntityFields.IS_DIRECT, true)
                RoomCategoryFilter.ONLY_ROOMS -> query.equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
                RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS -> query.greaterThan(RoomSummaryEntityFields.NOTIFICATION_COUNT, 0)
                RoomCategoryFilter.ALL -> {
                    // nop
                }
            }
        }
        queryParams.roomTagQueryFilter?.let {
            it.isFavorite?.let { fav ->
                query.equalTo(RoomSummaryEntityFields.IS_FAVOURITE, fav)
            }
            it.isLowPriority?.let { lp ->
                query.equalTo(RoomSummaryEntityFields.IS_LOW_PRIORITY, lp)
            }
            it.isServerNotice?.let { sn ->
                query.equalTo(RoomSummaryEntityFields.IS_SERVER_NOTICE, sn)
            }
        }

        queryParams.excludeType?.forEach {
            query.notEqualTo(RoomSummaryEntityFields.ROOM_TYPE, it)
        }
        queryParams.includeType?.forEach {
            query.equalTo(RoomSummaryEntityFields.ROOM_TYPE, it)
        }
        when (queryParams.roomCategoryFilter) {
            RoomCategoryFilter.ONLY_DM -> query.equalTo(RoomSummaryEntityFields.IS_DIRECT, true)
            RoomCategoryFilter.ONLY_ROOMS -> query.equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
            RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS -> query.greaterThan(RoomSummaryEntityFields.NOTIFICATION_COUNT, 0)
            RoomCategoryFilter.ALL -> Unit // nop
        }

        // Timber.w("VAL: activeSpaceId : ${queryParams.activeSpaceId}")
        when (queryParams.activeSpaceFilter) {
            is ActiveSpaceFilter.ActiveSpace -> {
                // It's annoying but for now realm java does not support querying in primitive list :/
                // https://github.com/realm/realm-java/issues/5361
                if (queryParams.activeSpaceFilter.currentSpaceId == null) {
                    // orphan rooms
                    query.isNull(RoomSummaryEntityFields.FLATTEN_PARENT_IDS)
                } else {
                    query.contains(RoomSummaryEntityFields.FLATTEN_PARENT_IDS, queryParams.activeSpaceFilter.currentSpaceId)
                }
            }
            is ActiveSpaceFilter.ExcludeSpace -> {
                query.not().contains(RoomSummaryEntityFields.FLATTEN_PARENT_IDS, queryParams.activeSpaceFilter.spaceId)
            }
            else                              -> {
                // nop
            }
        }

        if (queryParams.activeGroupId != null) {
            query.contains(RoomSummaryEntityFields.GROUP_IDS, queryParams.activeGroupId!!)
        }
        return query
    }

    fun getAllRoomSummaryChildOf(spaceAliasOrId: String, memberShips: List<Membership>): List<RoomSummary> {
        val space = getSpaceSummary(spaceAliasOrId) ?: return emptyList()
        val result = ArrayList<RoomSummary>()
        flattenChild(space, emptyList(), result, memberShips)
        return result
    }

    fun getAllRoomSummaryChildOfLive(spaceId: String, memberShips: List<Membership>): LiveData<List<RoomSummary>> {
        // we want to listen to all spaces in hierarchy and on change compute back all childs
        // and switch map to listen those?
        val mediatorLiveData = HierarchyLiveDataHelper(spaceId, memberShips, this).liveData()

        return Transformations.switchMap(mediatorLiveData) { allIds ->
            monarchy.findAllMappedWithChanges(
                    {
                        it.where<RoomSummaryEntity>()
                                .`in`(RoomSummaryEntityFields.ROOM_ID, allIds.toTypedArray())
                                .`in`(RoomSummaryEntityFields.MEMBERSHIP_STR, memberShips.map { it.name }.toTypedArray())
                                .equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
                    },
                    {
                        roomSummaryMapper.map(it)
                    })
        }
    }

    fun getFlattenOrphanRooms(): List<RoomSummary> {
        return getRoomSummaries(
                roomSummaryQueryParams {
                    memberships = Membership.activeMemberships()
                    excludeType = listOf(RoomType.SPACE)
                    roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                }
        ).filter { isOrphan(it) }
    }

    fun getFlattenOrphanRoomsLive(): LiveData<List<RoomSummary>> {
        return Transformations.map(
                getRoomSummariesLive(roomSummaryQueryParams {
                    memberships = Membership.activeMemberships()
                    excludeType = listOf(RoomType.SPACE)
                    roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                })
        ) {
            it.filter { isOrphan(it) }
        }
    }

    private fun isOrphan(roomSummary: RoomSummary): Boolean {
        if (roomSummary.roomType == RoomType.SPACE && roomSummary.membership.isActive()) {
            return false
        }
        // all parents line should be orphan
        roomSummary.spaceParents?.forEach { info ->
            if (info.roomSummary != null && !info.roomSummary.membership.isLeft()) {
                if (!isOrphan(info.roomSummary)) {
                    return false
                }
            }
        }

        // it may not have a parent relation but could be a child of some other....
        for (spaceSummary in getSpaceSummaries(spaceSummaryQueryParams { memberships = Membership.activeMemberships() })) {
            if (spaceSummary.spaceChildren?.any { it.childRoomId == roomSummary.roomId } == true) {
                return false
            }
        }

        return true
    }

    fun flattenChild(current: RoomSummary, parenting: List<String>, output: MutableList<RoomSummary>, memberShips: List<Membership>) {
        current.spaceChildren?.sortedBy { it.order ?: it.name }?.forEach { childInfo ->
            if (childInfo.roomType == RoomType.SPACE) {
                // Add recursive
                if (!parenting.contains(childInfo.childRoomId)) { // avoid cycles!
                    getSpaceSummary(childInfo.childRoomId)?.let { subSpace ->
                        if (memberShips.isEmpty() || memberShips.contains(subSpace.membership)) {
                            flattenChild(subSpace, parenting + listOf(current.roomId), output, memberShips)
                        }
                    }
                }
            } else if (childInfo.isKnown) {
                getRoomSummary(childInfo.childRoomId)?.let {
                    if (memberShips.isEmpty() || memberShips.contains(it.membership)) {
                        if (!it.isDirect) {
                            output.add(it)
                        }
                    }
                }
            }
        }
    }

    fun flattenSubSpace(current: RoomSummary,
                        parenting: List<String>,
                        output: MutableList<RoomSummary>,
                        memberShips: List<Membership>,
                        includeCurrent: Boolean = true) {
        if (includeCurrent) {
            output.add(current)
        }
        current.spaceChildren?.sortedBy { it.order ?: it.name }?.forEach {
            if (it.roomType == RoomType.SPACE) {
                // Add recursive
                if (!parenting.contains(it.childRoomId)) { // avoid cycles!
                    getSpaceSummary(it.childRoomId)?.let { subSpace ->
                        if (memberShips.isEmpty() || memberShips.contains(subSpace.membership)) {
                            output.add(subSpace)
                            flattenSubSpace(subSpace, parenting + listOf(current.roomId), output, memberShips)
                        }
                    }
                }
            }
        }
    }
}
