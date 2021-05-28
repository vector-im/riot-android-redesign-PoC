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

package org.matrix.android.sdk.internal.session.user.accountdata

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import io.realm.Realm
import io.realm.RealmQuery
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.RealmSessionProvider
import org.matrix.android.sdk.internal.database.mapper.AccountDataMapper
import org.matrix.android.sdk.internal.database.model.UserAccountDataEntity
import org.matrix.android.sdk.internal.database.model.UserAccountDataEntityFields
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class UserAccountDataDataSource @Inject constructor(@SessionDatabase private val monarchy: Monarchy,
                                                             private val realmSessionProvider: RealmSessionProvider,
                                                             private val accountDataMapper: AccountDataMapper) {

    fun getAccountDataEvent(type: String): AccountDataEvent? {
        return getAccountDataEvents(setOf(type)).firstOrNull()
    }

    fun getLiveAccountDataEvent(type: String): LiveData<Optional<AccountDataEvent>> {
        return Transformations.map(getLiveAccountDataEvents(setOf(type))) {
            it.firstOrNull()?.toOptional()
        }
    }

    fun getAccountDataEvents(types: Set<String>): List<AccountDataEvent> {
        return realmSessionProvider.withRealm {
            accountDataEventsQuery(it, types).findAll().map(accountDataMapper::map)
        }
    }

    fun getLiveAccountDataEvents(types: Set<String>): LiveData<List<AccountDataEvent>> {
        return monarchy.findAllMappedWithChanges(
                { accountDataEventsQuery(it, types) },
                accountDataMapper::map
        )
    }

    private fun accountDataEventsQuery(realm: Realm, types: Set<String>): RealmQuery<UserAccountDataEntity> {
        val query = realm.where(UserAccountDataEntity::class.java)
        if (types.isNotEmpty()) {
            query.`in`(UserAccountDataEntityFields.TYPE, types.toTypedArray())
        }
        return query
    }
}
