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
import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.session.accountdata.AccountDataService
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.sync.UserAccountDataSyncHandler
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.util.awaitCallback
import javax.inject.Inject

internal class UserAccountDataService @Inject constructor(
        @SessionDatabase private val monarchy: Monarchy,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val userAccountDataSyncHandler: UserAccountDataSyncHandler,
        private val accountDataDataSource: UserAccountDataDataSource,
        private val taskExecutor: TaskExecutor
) : AccountDataService {

    override fun getAccountDataEvent(type: String): AccountDataEvent? {
        return accountDataDataSource.getAccountDataEvent(type)
    }

    override fun getLiveAccountDataEvent(type: String): LiveData<Optional<AccountDataEvent>> {
        return accountDataDataSource.getLiveAccountDataEvent(type)
    }

    override fun getAccountDataEvents(types: Set<String>): List<AccountDataEvent> {
        return accountDataDataSource.getAccountDataEvents(types)
    }

    override fun getLiveAccountDataEvents(types: Set<String>): LiveData<List<AccountDataEvent>> {
        return accountDataDataSource.getLiveAccountDataEvents(types)
    }

    override suspend fun updateAccountData(type: String, content: Content) {
        val params = UpdateUserAccountDataTask.AnyParams(type = type, any = content)
        awaitCallback<Unit> { callback ->
            updateUserAccountDataTask.configureWith(params) {
                this.retryCount = 5 // TODO: Need to refactor retrying out into a helper method.
                this.callback = callback
            }
                    .executeBy(taskExecutor)
        }
        // TODO Move that to the task (but it created a circular dependencies...)
        monarchy.runTransactionSync { realm ->
            userAccountDataSyncHandler.handleGenericAccountData(realm, type, content)
        }
    }
}
