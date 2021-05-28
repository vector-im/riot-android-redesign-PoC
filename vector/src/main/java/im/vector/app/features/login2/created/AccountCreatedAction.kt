/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.login2.created

import android.net.Uri
import im.vector.app.core.platform.VectorViewModelAction

sealed class AccountCreatedAction : VectorViewModelAction {
    data class SetDisplayName(val displayName: String) : AccountCreatedAction()
    data class SetAvatar(val avatarUri: Uri, val filename: String) : AccountCreatedAction()
}
