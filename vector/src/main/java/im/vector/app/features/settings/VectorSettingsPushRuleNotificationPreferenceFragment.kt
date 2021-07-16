/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import im.vector.app.core.preference.VectorCheckboxPreference
import im.vector.app.core.utils.toast
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.pushrules.Action
import org.matrix.android.sdk.api.pushrules.RuleIds
import org.matrix.android.sdk.api.pushrules.RuleSetKey
import org.matrix.android.sdk.api.pushrules.rest.PushRule
import org.matrix.android.sdk.api.pushrules.rest.PushRuleAndKind

abstract class VectorSettingsPushRuleNotificationPreferenceFragment
    : VectorSettingsBaseFragment() {

    /**
     * @return the bing rule status boolean
     */
    fun ruleStatusIndexFor(ruleAndKind: PushRuleAndKind): Boolean {
        val rule = ruleAndKind.pushRule
        if (rule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
            return rule.shouldNotify() || rule.shouldNotNotify() && !rule.enabled
        }
        return rule.enabled && !rule.shouldNotNotify()
    }

    /**
     * Create a push rule with the updated checkbox status.
     *
     * @param status boolean checkbox status
     * @return a push rule with the updated flags
     */
    fun createNewRule(ruleAndKind: PushRuleAndKind, status: Boolean): PushRule {
        val safeRule = ruleAndKind.pushRule
        val safeKind = ruleAndKind.kind
        val ruleStatusIndex = ruleStatusIndexFor(ruleAndKind)

        return if (status != ruleStatusIndex) {
            if (safeRule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
                if (status) {
                    safeRule.copy(enabled = true)
                            .setNotify(true)
                            .setNotificationSound()
                } else {
                    safeRule.copy(enabled = true)
                            .setNotify(false)
                            .removeNotificationSound()
                }
            } else {
                if (status) {
                    safeRule.copy(enabled = true)
                            .setNotify(true)
                            .setHighlight(safeKind != RuleSetKey.UNDERRIDE
                                    && safeRule.ruleId != RuleIds.RULE_ID_INVITE_ME)
                            .setNotificationSound(
                                    if (safeRule.ruleId == RuleIds.RULE_ID_CALL) {
                                        Action.ACTION_OBJECT_VALUE_VALUE_RING
                                    } else {
                                        Action.ACTION_OBJECT_VALUE_VALUE_DEFAULT
                                    }
                            )
                } else {
                    if (safeKind == RuleSetKey.UNDERRIDE || safeRule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
                        safeRule.setNotify(false)
                    } else {
                        safeRule.copy(enabled = false)
                    }
                }
            }
        } else {
            safeRule
        }
    }

    override fun bindPref() {
        for (preferenceKey in prefKeyToPushRuleId.keys) {
            val preference = findPreference<VectorCheckboxPreference>(preferenceKey)!!
            val ruleAndKind: PushRuleAndKind? = session.getPushRules().findDefaultRule(prefKeyToPushRuleId[preferenceKey])
            if (ruleAndKind == null) {
                // The rule is not defined, hide the preference
                preference.isVisible = false
            } else {
                var oldRuleAndKind: PushRuleAndKind = ruleAndKind
                preference.isVisible = true
                preference.isChecked = ruleStatusIndexFor(ruleAndKind)
                preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val newRule = createNewRule(ruleAndKind, newValue as Boolean)
                    displayLoadingView()

                    lifecycleScope.launch {
                        val result = runCatching {
                            session.updatePushRuleActions(
                                    oldRuleAndKind.kind,
                                    oldRuleAndKind.pushRule,
                                    newRule
                            )
                        }
                        if (!isAdded) {
                            return@launch
                        }
                        hideLoadingView()
                        result.onSuccess {
                            oldRuleAndKind = oldRuleAndKind.copy(pushRule = newRule)
                            preference.isChecked = newValue
                        }
                        result.onFailure { failure ->
                            // Restore the previous value
                            refreshDisplay()
                            activity?.toast(errorFormatter.toHumanReadable(failure))
                        }
                    }

                    false
                }
            }
        }
    }

    private fun refreshDisplay() {
        listView?.adapter?.notifyDataSetChanged()
    }

    abstract val prefKeyToPushRuleId: Map<String, String>
}
