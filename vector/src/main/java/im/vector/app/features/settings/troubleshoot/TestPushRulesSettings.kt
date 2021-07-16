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
package im.vector.app.features.settings.troubleshoot

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.notifications.toNotificationAction
import org.matrix.android.sdk.api.pushrules.RuleIds
import org.matrix.android.sdk.api.pushrules.getActions
import javax.inject.Inject

class TestPushRulesSettings @Inject constructor(private val activeSessionHolder: ActiveSessionHolder,
                                                private val stringProvider: StringProvider)
    : TroubleshootTest(R.string.settings_troubleshoot_test_bing_settings_title) {

    private val testedRules =
            listOf(RuleIds.RULE_ID_CONTAIN_DISPLAY_NAME,
                    RuleIds.RULE_ID_CONTAIN_USER_NAME,
                    RuleIds.RULE_ID_ONE_TO_ONE_ROOM,
                    RuleIds.RULE_ID_ALL_OTHER_MESSAGES_ROOMS)

    override fun perform(activityResultLauncher: ActivityResultLauncher<Intent>) {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        val pushRules = session.getPushRules().getAllRules()
        var oneOrMoreRuleIsOff = false
        var oneOrMoreRuleAreSilent = false
        testedRules.forEach { ruleId ->
            pushRules.find { it.ruleId == ruleId }?.let { rule ->
                val actions = rule.getActions()
                val notifAction = actions.toNotificationAction()
                if (!rule.enabled || !notifAction.shouldNotify) {
                    // off
                    oneOrMoreRuleIsOff = true
                } else if (notifAction.soundName == null) {
                    // silent
                    oneOrMoreRuleAreSilent = true
                } else {
                    // noisy
                }
            }
        }

        if (oneOrMoreRuleIsOff) {
            description = stringProvider.getString(R.string.settings_troubleshoot_test_bing_settings_failed)
            // TODO
//                quickFix = object : TroubleshootQuickFix(R.string.settings_troubleshoot_test_bing_settings_quickfix) {
//                    override fun doFix() {
//                        val activity = fragment.activity
//                        if (activity is VectorSettingsFragmentInteractionListener) {
//                            activity.requestHighlightPreferenceKeyOnResume(VectorPreferences.SETTINGS_NOTIFICATION_ADVANCED_PREFERENCE_KEY)
//                        }
//                        activity?.supportFragmentManager?.popBackStack()
//                    }
//                }
            status = TestStatus.FAILED
        } else {
            description = if (oneOrMoreRuleAreSilent) {
                stringProvider.getString(R.string.settings_troubleshoot_test_bing_settings_success_with_warn)
            } else {
                null
            }
            status = TestStatus.SUCCESS
        }
    }
}
