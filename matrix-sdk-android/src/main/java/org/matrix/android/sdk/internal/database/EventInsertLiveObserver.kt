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

import com.zhuinden.monarchy.Monarchy
import io.realm.RealmConfiguration
import io.realm.RealmResults
import kotlinx.coroutines.launch
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertEntity
import org.matrix.android.sdk.internal.database.model.EventInsertEntityFields
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import timber.log.Timber
import javax.inject.Inject

internal class EventInsertLiveObserver @Inject constructor(
        @SessionDatabase realmConfiguration: RealmConfiguration,
        private val processors: Set<@JvmSuppressWildcards EventInsertLiveProcessor>,
) : RealmLiveEntityObserver<EventInsertEntity>(realmConfiguration) {

    override val query = Monarchy.Query<EventInsertEntity> {
        it.where(EventInsertEntity::class.java)
    }

    override fun onChange(results: RealmResults<EventInsertEntity>) {
        if (!results.isLoaded || results.isEmpty()) {
            return
        }
        val idsToDeleteAfterProcess = ArrayList<String>()
        val filteredEvents = ArrayList<EventInsertEntity>(results.size)
        Timber.v("EventInsertEntity updated with ${results.size} results in db")
        results.forEach {
            if (shouldProcess(it)) {
                // don't use copy from realm over there
                val copiedEvent = EventInsertEntity(
                        eventId = it.eventId,
                        eventType = it.eventType
                ).apply {
                    insertType = it.insertType
                }
                filteredEvents.add(copiedEvent)
            }
            idsToDeleteAfterProcess.add(it.eventId)
        }
        observerScope.launch {
            awaitTransaction(realmConfiguration) { realm ->
                Timber.v("##Transaction: There are ${filteredEvents.size} events to process ")
                filteredEvents.forEach { eventInsert ->
                    val eventId = eventInsert.eventId
                    val event = EventEntity.where(realm, eventId).findFirst()
                    if (event == null) {
                        Timber.v("Event $eventId not found")
                        return@forEach
                    }
                    val domainEvent = event.asDomain()
                    processors.filter {
                        it.shouldProcess(eventId, domainEvent.getClearType(), eventInsert.insertType)
                    }.forEach {
                        it.process(realm, domainEvent)
                    }
                }
                realm.where(EventInsertEntity::class.java)
                        .`in`(EventInsertEntityFields.EVENT_ID, idsToDeleteAfterProcess.toTypedArray())
                        .findAll()
                        .deleteAllFromRealm()
            }
            processors.forEach { it.onPostProcess() }
        }
    }

    private fun shouldProcess(eventInsertEntity: EventInsertEntity): Boolean {
        return processors.any {
            it.shouldProcess(eventInsertEntity.eventId, eventInsertEntity.eventType, eventInsertEntity.insertType)
        }
    }
}
