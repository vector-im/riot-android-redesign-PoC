/*
 * Copyright 2018 New Vector Ltd
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

package im.vector.app.features.login.terms

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import org.matrix.android.sdk.internal.auth.registration.LocalizedFlowDataLoginTerms
import javax.inject.Inject

class PolicyController @Inject constructor() : TypedEpoxyController<List<LocalizedFlowDataLoginTermsChecked>>() {

    var listener: PolicyControllerListener? = null

    var homeServer: String? = null

    override fun buildModels(data: List<LocalizedFlowDataLoginTermsChecked>) {
        val host = this
        data.forEach { entry ->
            policyItem {
                id(entry.localizedFlowDataLoginTerms.policyName)
                checked(entry.checked)
                title(entry.localizedFlowDataLoginTerms.localizedName)
                subtitle(host.homeServer)

                clickListener(View.OnClickListener { host.listener?.openPolicy(entry.localizedFlowDataLoginTerms) })
                checkChangeListener { _, isChecked ->
                    host.listener?.setChecked(entry.localizedFlowDataLoginTerms, isChecked)
                }
            }
        }
    }

    interface PolicyControllerListener {
        fun setChecked(localizedFlowDataLoginTerms: LocalizedFlowDataLoginTerms, isChecked: Boolean)
        fun openPolicy(localizedFlowDataLoginTerms: LocalizedFlowDataLoginTerms)
    }
}
