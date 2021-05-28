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

package org.matrix.android.sdk.internal.database

import io.realm.DynamicRealm
import io.realm.FieldAttribute
import io.realm.RealmMigration
import org.matrix.android.sdk.api.session.room.model.VersioningState
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntityFields
import org.matrix.android.sdk.internal.database.model.EditAggregatedSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.EditionOfEventFields
import org.matrix.android.sdk.internal.database.model.EventEntityFields
import org.matrix.android.sdk.internal.database.model.HomeServerCapabilitiesEntityFields
import org.matrix.android.sdk.internal.database.model.PendingThreePidEntityFields
import org.matrix.android.sdk.internal.database.model.PreviewUrlCacheEntityFields
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntityFields
import org.matrix.android.sdk.internal.database.model.RoomEntityFields
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.RoomTagEntityFields
import org.matrix.android.sdk.internal.database.model.SpaceChildSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.SpaceParentSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.TimelineEventEntityFields
import org.matrix.android.sdk.internal.di.MoshiProvider
import timber.log.Timber
import javax.inject.Inject

class RealmSessionStoreMigration @Inject constructor() : RealmMigration {

    companion object {
        const val SESSION_STORE_SCHEMA_VERSION = 14L
    }

    override fun migrate(realm: DynamicRealm, oldVersion: Long, newVersion: Long) {
        Timber.v("Migrating Realm Session from $oldVersion to $newVersion")

        if (oldVersion <= 0) migrateTo1(realm)
        if (oldVersion <= 1) migrateTo2(realm)
        if (oldVersion <= 2) migrateTo3(realm)
        if (oldVersion <= 3) migrateTo4(realm)
        if (oldVersion <= 4) migrateTo5(realm)
        if (oldVersion <= 5) migrateTo6(realm)
        if (oldVersion <= 6) migrateTo7(realm)
        if (oldVersion <= 7) migrateTo8(realm)
        if (oldVersion <= 8) migrateTo9(realm)
        if (oldVersion <= 9) migrateTo10(realm)
        if (oldVersion <= 10) migrateTo11(realm)
        if (oldVersion <= 11) migrateTo12(realm)
        if (oldVersion <= 12) migrateTo13(realm)
        if (oldVersion <= 13) migrateTo14(realm)
    }

    private fun migrateTo1(realm: DynamicRealm) {
        Timber.d("Step 0 -> 1")
        // Add hasFailedSending in RoomSummary and a small warning icon on room list

        realm.schema.get("RoomSummaryEntity")
                ?.addField(RoomSummaryEntityFields.HAS_FAILED_SENDING, Boolean::class.java)
                ?.transform { obj ->
                    obj.setBoolean(RoomSummaryEntityFields.HAS_FAILED_SENDING, false)
                }
    }

    private fun migrateTo2(realm: DynamicRealm) {
        Timber.d("Step 1 -> 2")
        realm.schema.get("HomeServerCapabilitiesEntity")
                ?.addField("adminE2EByDefault", Boolean::class.java)
                ?.transform { obj ->
                    obj.setBoolean("adminE2EByDefault", true)
                }
    }

    private fun migrateTo3(realm: DynamicRealm) {
        Timber.d("Step 2 -> 3")
        realm.schema.get("HomeServerCapabilitiesEntity")
                ?.addField("preferredJitsiDomain", String::class.java)
                ?.transform { obj ->
                    // Schedule a refresh of the capabilities
                    obj.setLong(HomeServerCapabilitiesEntityFields.LAST_UPDATED_TIMESTAMP, 0)
                }
    }

    private fun migrateTo4(realm: DynamicRealm) {
        Timber.d("Step 3 -> 4")
        realm.schema.create("PendingThreePidEntity")
                .addField(PendingThreePidEntityFields.CLIENT_SECRET, String::class.java)
                .setRequired(PendingThreePidEntityFields.CLIENT_SECRET, true)
                .addField(PendingThreePidEntityFields.EMAIL, String::class.java)
                .addField(PendingThreePidEntityFields.MSISDN, String::class.java)
                .addField(PendingThreePidEntityFields.SEND_ATTEMPT, Int::class.java)
                .addField(PendingThreePidEntityFields.SID, String::class.java)
                .setRequired(PendingThreePidEntityFields.SID, true)
                .addField(PendingThreePidEntityFields.SUBMIT_URL, String::class.java)
    }

    private fun migrateTo5(realm: DynamicRealm) {
        Timber.d("Step 4 -> 5")
        realm.schema.get("HomeServerCapabilitiesEntity")
                ?.removeField("adminE2EByDefault")
                ?.removeField("preferredJitsiDomain")
    }

    private fun migrateTo6(realm: DynamicRealm) {
        Timber.d("Step 5 -> 6")
        realm.schema.create("PreviewUrlCacheEntity")
                .addField(PreviewUrlCacheEntityFields.URL, String::class.java)
                .setRequired(PreviewUrlCacheEntityFields.URL, true)
                .addPrimaryKey(PreviewUrlCacheEntityFields.URL)
                .addField(PreviewUrlCacheEntityFields.URL_FROM_SERVER, String::class.java)
                .addField(PreviewUrlCacheEntityFields.SITE_NAME, String::class.java)
                .addField(PreviewUrlCacheEntityFields.TITLE, String::class.java)
                .addField(PreviewUrlCacheEntityFields.DESCRIPTION, String::class.java)
                .addField(PreviewUrlCacheEntityFields.MXC_URL, String::class.java)
                .addField(PreviewUrlCacheEntityFields.LAST_UPDATED_TIMESTAMP, Long::class.java)
    }

    private fun migrateTo7(realm: DynamicRealm) {
        Timber.d("Step 6 -> 7")
        realm.schema.get("RoomEntity")
                ?.addField(RoomEntityFields.MEMBERS_LOAD_STATUS_STR, String::class.java)
                ?.transform { obj ->
                    if (obj.getBoolean("areAllMembersLoaded")) {
                        obj.setString("membersLoadStatusStr", RoomMembersLoadStatusType.LOADED.name)
                    } else {
                        obj.setString("membersLoadStatusStr", RoomMembersLoadStatusType.NONE.name)
                    }
                }
                ?.removeField("areAllMembersLoaded")
    }

    private fun migrateTo8(realm: DynamicRealm) {
        Timber.d("Step 7 -> 8")

        val editionOfEventSchema = realm.schema.create("EditionOfEvent")
                .addField(EditionOfEventFields.CONTENT, String::class.java)
                .addField(EditionOfEventFields.EVENT_ID, String::class.java)
                .setRequired(EditionOfEventFields.EVENT_ID, true)
                .addField(EditionOfEventFields.SENDER_ID, String::class.java)
                .setRequired(EditionOfEventFields.SENDER_ID, true)
                .addField(EditionOfEventFields.TIMESTAMP, Long::class.java)
                .addField(EditionOfEventFields.IS_LOCAL_ECHO, Boolean::class.java)

        realm.schema.get("EditAggregatedSummaryEntity")
                ?.removeField("aggregatedContent")
                ?.removeField("sourceEvents")
                ?.removeField("lastEditTs")
                ?.removeField("sourceLocalEchoEvents")
                ?.addRealmListField(EditAggregatedSummaryEntityFields.EDITIONS.`$`, editionOfEventSchema)

        // This has to be done once a parent use the model as a child
        // See https://github.com/realm/realm-java/issues/7402
        editionOfEventSchema.isEmbedded = true
    }

    private fun migrateTo9(realm: DynamicRealm) {
        Timber.d("Step 8 -> 9")

        realm.schema.get("RoomSummaryEntity")
                ?.addField(RoomSummaryEntityFields.LAST_ACTIVITY_TIME, Long::class.java, FieldAttribute.INDEXED)
                ?.setNullable(RoomSummaryEntityFields.LAST_ACTIVITY_TIME, true)
                ?.addIndex(RoomSummaryEntityFields.MEMBERSHIP_STR)
                ?.addIndex(RoomSummaryEntityFields.IS_DIRECT)
                ?.addIndex(RoomSummaryEntityFields.VERSIONING_STATE_STR)

                ?.addField(RoomSummaryEntityFields.IS_FAVOURITE, Boolean::class.java)
                ?.addIndex(RoomSummaryEntityFields.IS_FAVOURITE)
                ?.addField(RoomSummaryEntityFields.IS_LOW_PRIORITY, Boolean::class.java)
                ?.addIndex(RoomSummaryEntityFields.IS_LOW_PRIORITY)
                ?.addField(RoomSummaryEntityFields.IS_SERVER_NOTICE, Boolean::class.java)
                ?.addIndex(RoomSummaryEntityFields.IS_SERVER_NOTICE)

                ?.transform { obj ->
                    val isFavorite = obj.getList(RoomSummaryEntityFields.TAGS.`$`).any {
                        it.getString(RoomTagEntityFields.TAG_NAME) == RoomTag.ROOM_TAG_FAVOURITE
                    }
                    obj.setBoolean(RoomSummaryEntityFields.IS_FAVOURITE, isFavorite)

                    val isLowPriority = obj.getList(RoomSummaryEntityFields.TAGS.`$`).any {
                        it.getString(RoomTagEntityFields.TAG_NAME) == RoomTag.ROOM_TAG_LOW_PRIORITY
                    }

                    obj.setBoolean(RoomSummaryEntityFields.IS_LOW_PRIORITY, isLowPriority)

//                    XXX migrate last message origin server ts
                    obj.getObject(RoomSummaryEntityFields.LATEST_PREVIEWABLE_EVENT.`$`)
                            ?.getObject(TimelineEventEntityFields.ROOT.`$`)
                            ?.getLong(EventEntityFields.ORIGIN_SERVER_TS)?.let {
                                obj.setLong(RoomSummaryEntityFields.LAST_ACTIVITY_TIME, it)
                            }
                }
    }

    private fun migrateTo10(realm: DynamicRealm) {
        Timber.d("Step 9 -> 10")
        realm.schema.create("SpaceChildSummaryEntity")
                ?.addField(SpaceChildSummaryEntityFields.ORDER, String::class.java)
                ?.addField(SpaceChildSummaryEntityFields.CHILD_ROOM_ID, String::class.java)
                ?.addField(SpaceChildSummaryEntityFields.AUTO_JOIN, Boolean::class.java)
                ?.setNullable(SpaceChildSummaryEntityFields.AUTO_JOIN, true)
                ?.addRealmObjectField(SpaceChildSummaryEntityFields.CHILD_SUMMARY_ENTITY.`$`, realm.schema.get("RoomSummaryEntity")!!)
                ?.addRealmListField(SpaceChildSummaryEntityFields.VIA_SERVERS.`$`, String::class.java)

        realm.schema.create("SpaceParentSummaryEntity")
                ?.addField(SpaceParentSummaryEntityFields.PARENT_ROOM_ID, String::class.java)
                ?.addField(SpaceParentSummaryEntityFields.CANONICAL, Boolean::class.java)
                ?.setNullable(SpaceParentSummaryEntityFields.CANONICAL, true)
                ?.addRealmObjectField(SpaceParentSummaryEntityFields.PARENT_SUMMARY_ENTITY.`$`, realm.schema.get("RoomSummaryEntity")!!)
                ?.addRealmListField(SpaceParentSummaryEntityFields.VIA_SERVERS.`$`, String::class.java)

        val creationContentAdapter = MoshiProvider.providesMoshi().adapter(RoomCreateContent::class.java)
        realm.schema.get("RoomSummaryEntity")
                ?.addField(RoomSummaryEntityFields.ROOM_TYPE, String::class.java)
                ?.addField(RoomSummaryEntityFields.FLATTEN_PARENT_IDS, String::class.java)
                ?.addField(RoomSummaryEntityFields.GROUP_IDS, String::class.java)
                ?.transform { obj ->

                    val creationEvent = realm.where("CurrentStateEventEntity")
                            .equalTo(CurrentStateEventEntityFields.ROOM_ID, obj.getString(RoomSummaryEntityFields.ROOM_ID))
                            .equalTo(CurrentStateEventEntityFields.TYPE, EventType.STATE_ROOM_CREATE)
                            .findFirst()

                    val roomType = creationEvent?.getObject(CurrentStateEventEntityFields.ROOT.`$`)
                            ?.getString(EventEntityFields.CONTENT)?.let {
                                creationContentAdapter.fromJson(it)?.type
                            }

                    obj.setString(RoomSummaryEntityFields.ROOM_TYPE, roomType)
                }
                ?.addRealmListField(RoomSummaryEntityFields.PARENTS.`$`, realm.schema.get("SpaceParentSummaryEntity")!!)
                ?.addRealmListField(RoomSummaryEntityFields.CHILDREN.`$`, realm.schema.get("SpaceChildSummaryEntity")!!)
    }

    private fun migrateTo11(realm: DynamicRealm) {
        Timber.d("Step 10 -> 11")
        realm.schema.get("EventEntity")
                ?.addField(EventEntityFields.SEND_STATE_DETAILS, String::class.java)
    }

    private fun migrateTo12(realm: DynamicRealm) {
        Timber.d("Step 11 -> 12")

        val joinRulesContentAdapter = MoshiProvider.providesMoshi().adapter(RoomJoinRulesContent::class.java)
        realm.schema.get("RoomSummaryEntity")
                ?.addField(RoomSummaryEntityFields.JOIN_RULES_STR, String::class.java)
                ?.transform { obj ->
                    val joinRulesEvent = realm.where("CurrentStateEventEntity")
                            .equalTo(CurrentStateEventEntityFields.ROOM_ID, obj.getString(RoomSummaryEntityFields.ROOM_ID))
                            .equalTo(CurrentStateEventEntityFields.TYPE, EventType.STATE_ROOM_JOIN_RULES)
                            .findFirst()

                    val roomJoinRules = joinRulesEvent?.getObject(CurrentStateEventEntityFields.ROOT.`$`)
                            ?.getString(EventEntityFields.CONTENT)?.let {
                                joinRulesContentAdapter.fromJson(it)?.joinRules
                            }

                    obj.setString(RoomSummaryEntityFields.JOIN_RULES_STR, roomJoinRules?.name)
                }

        realm.schema.get("SpaceChildSummaryEntity")
                ?.addField(SpaceChildSummaryEntityFields.SUGGESTED, Boolean::class.java)
                ?.setNullable(SpaceChildSummaryEntityFields.SUGGESTED, true)
    }

    private fun migrateTo13(realm: DynamicRealm) {
        Timber.d("Step 12 -> 13")
        // Fix issue with the nightly build. Eventually play again the migration which has been included in migrateTo12()
        realm.schema.get("SpaceChildSummaryEntity")
                ?.takeIf { !it.hasField(SpaceChildSummaryEntityFields.SUGGESTED) }
                ?.addField(SpaceChildSummaryEntityFields.SUGGESTED, Boolean::class.java)
                ?.setNullable(SpaceChildSummaryEntityFields.SUGGESTED, true)
    }

    private fun migrateTo14(realm: DynamicRealm) {
        Timber.d("Step 13 -> 14")
        val roomAccountDataSchema = realm.schema.create("RoomAccountDataEntity")
                .addField(RoomAccountDataEntityFields.CONTENT_STR, String::class.java)
                .addField(RoomAccountDataEntityFields.TYPE, String::class.java,  FieldAttribute.INDEXED)

        realm.schema.get("RoomEntity")
                ?.addRealmListField(RoomEntityFields.ACCOUNT_DATA.`$`, roomAccountDataSchema)

        realm.schema.get("RoomSummaryEntity")
                ?.addField(RoomSummaryEntityFields.IS_HIDDEN_FROM_USER, Boolean::class.java, FieldAttribute.INDEXED)
                ?.transform {
                    val isHiddenFromUser = it.getString(RoomSummaryEntityFields.VERSIONING_STATE_STR) == VersioningState.UPGRADED_ROOM_JOINED.name
                    it.setBoolean(RoomSummaryEntityFields.IS_HIDDEN_FROM_USER, isHiddenFromUser)
                }

        roomAccountDataSchema.isEmbedded = true
    }
}
