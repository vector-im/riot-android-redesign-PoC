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

package im.vector.app.core.preference

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RadioGroup
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import org.matrix.android.sdk.api.pushrules.Action
import org.matrix.android.sdk.api.pushrules.RuleIds
import org.matrix.android.sdk.api.pushrules.RuleSetKey
import org.matrix.android.sdk.api.pushrules.rest.PushRule
import org.matrix.android.sdk.api.pushrules.rest.PushRuleAndKind

class PushRulePreference : VectorPreference {

    /**
     * @return the selected push rule and its kind
     */
    var ruleAndKind: PushRuleAndKind? = null
        private set

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        layoutResource = R.layout.vector_preference_push_rule
    }

    /**
     * @return the bing rule status index
     */
    private val ruleStatusIndex: Int
        get() {
            val safeRule = ruleAndKind?.pushRule ?: return NOTIFICATION_OFF_INDEX

            if (safeRule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
                if (safeRule.shouldNotNotify()) {
                    return if (safeRule.enabled) {
                        NOTIFICATION_OFF_INDEX
                    } else {
                        NOTIFICATION_SILENT_INDEX
                    }
                } else if (safeRule.shouldNotify()) {
                    return NOTIFICATION_NOISY_INDEX
                }
            }

            if (safeRule.enabled) {
                return if (safeRule.shouldNotNotify()) {
                    NOTIFICATION_OFF_INDEX
                } else if (safeRule.getNotificationSound() != null) {
                    NOTIFICATION_NOISY_INDEX
                } else {
                    NOTIFICATION_SILENT_INDEX
                }
            }

            return NOTIFICATION_OFF_INDEX
        }

    /**
     * Update the push rule.
     *
     * @param pushRule
     */
    fun setPushRule(pushRuleAndKind: PushRuleAndKind?) {
        ruleAndKind = pushRuleAndKind
        refreshSummary()
    }

    /**
     * Refresh the summary
     */
    private fun refreshSummary() {
        summary = context.getString(when (ruleStatusIndex) {
            NOTIFICATION_OFF_INDEX    -> R.string.notification_off
            NOTIFICATION_SILENT_INDEX -> R.string.notification_silent
            else                      -> R.string.notification_noisy
        })
    }

    /**
     * Create a push rule with the updated required at index.
     *
     * @param index index
     * @return a push rule with the updated flags / null if there is no update
     */
    fun createNewRule(index: Int): PushRule? {
        val safeRule = ruleAndKind?.pushRule ?: return null
        val safeKind = ruleAndKind?.kind ?: return null

        return if (index != ruleStatusIndex) {
            if (safeRule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
                when (index) {
                    NOTIFICATION_OFF_INDEX    -> {
                        safeRule.copy(enabled = true)
                                .setNotify(false)
                                .removeNotificationSound()
                    }
                    NOTIFICATION_SILENT_INDEX -> {
                        safeRule.copy(enabled = false)
                                .setNotify(false)
                    }
                    NOTIFICATION_NOISY_INDEX  -> {
                        safeRule.copy(enabled = true)
                                .setNotify(true)
                                .setNotificationSound()
                    }
                    else                      -> safeRule
                }
            } else {
                if (NOTIFICATION_OFF_INDEX == index) {
                    if (safeKind == RuleSetKey.UNDERRIDE || safeRule.ruleId == RuleIds.RULE_ID_SUPPRESS_BOTS_NOTIFICATIONS) {
                        safeRule.setNotify(false)
                    } else {
                        safeRule.copy(enabled = false)
                    }
                } else {
                    val newRule = safeRule.copy(enabled = true)
                            .setNotify(true)
                            .setHighlight(safeKind != RuleSetKey.UNDERRIDE
                                    && safeRule.ruleId != RuleIds.RULE_ID_INVITE_ME
                                    && NOTIFICATION_NOISY_INDEX == index)

                    if (NOTIFICATION_NOISY_INDEX == index) {
                        newRule.setNotificationSound(
                                if (safeRule.ruleId == RuleIds.RULE_ID_CALL) {
                                    Action.ACTION_OBJECT_VALUE_VALUE_RING
                                } else {
                                    Action.ACTION_OBJECT_VALUE_VALUE_DEFAULT
                                }
                        )
                    } else {
                        newRule.removeNotificationSound()
                    }
                }
            }
        } else {
            safeRule
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.findViewById(android.R.id.summary)?.visibility = View.GONE
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)

        val radioGroup = holder.findViewById(R.id.bingPreferenceRadioGroup) as? RadioGroup
        radioGroup?.setOnCheckedChangeListener(null)

        when (ruleStatusIndex) {
            NOTIFICATION_OFF_INDEX    -> {
                radioGroup?.check(R.id.bingPreferenceRadioBingRuleOff)
            }
            NOTIFICATION_SILENT_INDEX -> {
                radioGroup?.check(R.id.bingPreferenceRadioBingRuleSilent)
            }
            else                      -> {
                radioGroup?.check(R.id.bingPreferenceRadioBingRuleNoisy)
            }
        }

        radioGroup?.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.bingPreferenceRadioBingRuleOff    -> {
                    onPreferenceChangeListener?.onPreferenceChange(this, NOTIFICATION_OFF_INDEX)
                }
                R.id.bingPreferenceRadioBingRuleSilent -> {
                    onPreferenceChangeListener?.onPreferenceChange(this, NOTIFICATION_SILENT_INDEX)
                }
                R.id.bingPreferenceRadioBingRuleNoisy  -> {
                    onPreferenceChangeListener?.onPreferenceChange(this, NOTIFICATION_NOISY_INDEX)
                }
            }
        }
    }

    companion object {

        // index in mRuleStatuses
        private const val NOTIFICATION_OFF_INDEX = 0
        private const val NOTIFICATION_SILENT_INDEX = 1
        private const val NOTIFICATION_NOISY_INDEX = 2
    }
}
