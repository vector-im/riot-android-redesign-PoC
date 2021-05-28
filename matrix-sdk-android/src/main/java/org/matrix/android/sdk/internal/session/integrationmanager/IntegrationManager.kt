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

package org.matrix.android.sdk.internal.session.integrationmanager

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerConfig
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.widgets.model.WidgetContent
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.internal.database.model.WellknownIntegrationManagerConfigEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.extensions.observeNotNull
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.internal.session.user.accountdata.UserAccountDataDataSource
import org.matrix.android.sdk.internal.session.user.accountdata.UpdateUserAccountDataTask
import org.matrix.android.sdk.internal.session.widgets.helper.WidgetFactory
import org.matrix.android.sdk.internal.session.widgets.helper.extractWidgetSequence
import timber.log.Timber
import javax.inject.Inject

/**
 * The integration manager allows to
 *  - Get the Integration Manager that a user has explicitly set for its account (via account data)
 *  - Get the recommended/preferred Integration Manager list as defined by the HomeServer (via wellknown)
 *  - Check if the user has disabled the integration manager feature
 *  - Allow / Disallow Integration manager (propagated to other riot clients)
 *
 *  The integration manager listen to account data, and can notify observer for changes.
 *
 *  The wellknown is refreshed at each application fresh start
 *
 */
@SessionScope
internal class IntegrationManager @Inject constructor(matrixConfiguration: MatrixConfiguration,
                                                      @SessionDatabase private val monarchy: Monarchy,
                                                      private val updateUserAccountDataTask: UpdateUserAccountDataTask,
                                                      private val accountDataDataSource: UserAccountDataDataSource,
                                                      private val widgetFactory: WidgetFactory)
    : SessionLifecycleObserver {

    private val currentConfigs = ArrayList<IntegrationManagerConfig>()
    private val lifecycleOwner: LifecycleOwner = LifecycleOwner { lifecycleRegistry }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val listeners = HashSet<IntegrationManagerService.Listener>()
    fun addListener(listener: IntegrationManagerService.Listener) = synchronized(listeners) { listeners.add(listener) }
    fun removeListener(listener: IntegrationManagerService.Listener) = synchronized(listeners) { listeners.remove(listener) }

    init {
        val defaultConfig = IntegrationManagerConfig(
                uiUrl = matrixConfiguration.integrationUIUrl,
                restUrl = matrixConfiguration.integrationRestUrl,
                kind = IntegrationManagerConfig.Kind.DEFAULT
        )
        currentConfigs.add(defaultConfig)
    }

    override fun onSessionStarted(session: Session) {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        observeWellknownConfig()
        accountDataDataSource
                .getLiveAccountDataEvent(UserAccountDataTypes.TYPE_ALLOWED_WIDGETS)
                .observeNotNull(lifecycleOwner) {
                    val allowedWidgetsContent = it.getOrNull()?.content?.toModel<AllowedWidgetsContent>()
                    if (allowedWidgetsContent != null) {
                        notifyWidgetPermissionsChanged(allowedWidgetsContent)
                    }
                }
        accountDataDataSource
                .getLiveAccountDataEvent(UserAccountDataTypes.TYPE_INTEGRATION_PROVISIONING)
                .observeNotNull(lifecycleOwner) {
                    val integrationProvisioningContent = it.getOrNull()?.content?.toModel<IntegrationProvisioningContent>()
                    if (integrationProvisioningContent != null) {
                        notifyIsEnabledChanged(integrationProvisioningContent)
                    }
                }
        accountDataDataSource
                .getLiveAccountDataEvent(UserAccountDataTypes.TYPE_WIDGETS)
                .observeNotNull(lifecycleOwner) {
                    val integrationManagerContent = it.getOrNull()?.asIntegrationManagerWidgetContent()
                    val config = integrationManagerContent?.extractIntegrationManagerConfig()
                    updateCurrentConfigs(IntegrationManagerConfig.Kind.ACCOUNT, config)
                }
    }

    override fun onSessionStopped(session: Session) {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    fun hasConfig() = currentConfigs.isNotEmpty()

    fun getOrderedConfigs(): List<IntegrationManagerConfig> {
        return currentConfigs.sortedBy {
            it.kind
        }
    }

    fun getPreferredConfig(): IntegrationManagerConfig {
        // This can't be null as we should have at least the default one registered
        return getOrderedConfigs().first()
    }

    /**
     * Returns false if the user as disabled integration manager feature
     */
    fun isIntegrationEnabled(): Boolean {
        val integrationProvisioningData = accountDataDataSource.getAccountDataEvent(UserAccountDataTypes.TYPE_INTEGRATION_PROVISIONING)
        val integrationProvisioningContent = integrationProvisioningData?.content?.toModel<IntegrationProvisioningContent>()
        return integrationProvisioningContent?.enabled ?: false
    }

    suspend fun setIntegrationEnabled(enable: Boolean) {
        val isIntegrationEnabled = isIntegrationEnabled()
        if (enable == isIntegrationEnabled) {
            return
        }
        val integrationProvisioningContent = IntegrationProvisioningContent(enabled = enable)
        val params = UpdateUserAccountDataTask.IntegrationProvisioning(integrationProvisioningContent = integrationProvisioningContent)
        return updateUserAccountDataTask.execute(params)
    }

    suspend fun setWidgetAllowed(stateEventId: String, allowed: Boolean) {
        val currentAllowedWidgets = accountDataDataSource.getAccountDataEvent(UserAccountDataTypes.TYPE_ALLOWED_WIDGETS)
        val currentContent = currentAllowedWidgets?.content?.toModel<AllowedWidgetsContent>()
        val newContent = if (currentContent == null) {
            val allowedWidget = mapOf(stateEventId to allowed)
            AllowedWidgetsContent(widgets = allowedWidget, native = emptyMap())
        } else {
            val allowedWidgets = currentContent.widgets.toMutableMap().apply {
                put(stateEventId, allowed)
            }
            currentContent.copy(widgets = allowedWidgets)
        }
        val params = UpdateUserAccountDataTask.AllowedWidgets(allowedWidgetsContent = newContent)
        return updateUserAccountDataTask.execute(params)
    }

    fun isWidgetAllowed(stateEventId: String): Boolean {
        val currentAllowedWidgets = accountDataDataSource.getAccountDataEvent(UserAccountDataTypes.TYPE_ALLOWED_WIDGETS)
        val currentContent = currentAllowedWidgets?.content?.toModel<AllowedWidgetsContent>()
        return currentContent?.widgets?.get(stateEventId) ?: false
    }

    suspend fun setNativeWidgetDomainAllowed(widgetType: String, domain: String, allowed: Boolean) {
        val currentAllowedWidgets = accountDataDataSource.getAccountDataEvent(UserAccountDataTypes.TYPE_ALLOWED_WIDGETS)
        val currentContent = currentAllowedWidgets?.content?.toModel<AllowedWidgetsContent>()
        val newContent = if (currentContent == null) {
            val nativeAllowedWidgets = mapOf(widgetType to mapOf(domain to allowed))
            AllowedWidgetsContent(widgets = emptyMap(), native = nativeAllowedWidgets)
        } else {
            val nativeAllowedWidgets = currentContent.native.toMutableMap().apply {
                (get(widgetType))?.let {
                    set(widgetType, it.toMutableMap().apply { set(domain, allowed) })
                } ?: run {
                    set(widgetType, mapOf(domain to allowed))
                }
            }
            currentContent.copy(native = nativeAllowedWidgets)
        }
        val params = UpdateUserAccountDataTask.AllowedWidgets(allowedWidgetsContent = newContent)
        return updateUserAccountDataTask.execute(params)
    }

    fun isNativeWidgetDomainAllowed(widgetType: String, domain: String?): Boolean {
        val currentAllowedWidgets = accountDataDataSource.getAccountDataEvent(UserAccountDataTypes.TYPE_ALLOWED_WIDGETS)
        val currentContent = currentAllowedWidgets?.content?.toModel<AllowedWidgetsContent>()
        return currentContent?.native?.get(widgetType)?.get(domain) ?: false
    }

    private fun notifyConfigurationChanged() {
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it.onConfigurationChanged(currentConfigs)
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to notify listener")
                }
            }
        }
    }

    private fun notifyWidgetPermissionsChanged(allowedWidgets: AllowedWidgetsContent) {
        Timber.v("On widget permissions changed: $allowedWidgets")
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it.onWidgetPermissionsChanged(allowedWidgets.widgets)
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to notify listener")
                }
            }
        }
    }

    private fun notifyIsEnabledChanged(provisioningContent: IntegrationProvisioningContent) {
        Timber.v("On provisioningContent changed : $provisioningContent")
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it.onIsEnabledChanged(provisioningContent.enabled)
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to notify listener")
                }
            }
        }
    }

    private fun WidgetContent.extractIntegrationManagerConfig(): IntegrationManagerConfig? {
        if (url.isNullOrBlank()) {
            return null
        }
        val integrationManagerData = data.toModel<IntegrationManagerWidgetData>()
        return IntegrationManagerConfig(
                uiUrl = url,
                restUrl = integrationManagerData?.apiUrl ?: url,
                kind = IntegrationManagerConfig.Kind.ACCOUNT
        )
    }

    private fun AccountDataEvent.asIntegrationManagerWidgetContent(): WidgetContent? {
        return extractWidgetSequence(widgetFactory)
                .filter {
                    WidgetType.IntegrationManager == it.type
                }
                .firstOrNull()?.widgetContent
    }

    private fun observeWellknownConfig() {
        val liveData = monarchy.findAllMappedWithChanges(
                { it.where(WellknownIntegrationManagerConfigEntity::class.java) },
                { IntegrationManagerConfig(it.uiUrl, it.apiUrl, IntegrationManagerConfig.Kind.HOMESERVER) }
        )
        liveData.observeNotNull(lifecycleOwner) {
            val config = it.firstOrNull()
            updateCurrentConfigs(IntegrationManagerConfig.Kind.HOMESERVER, config)
        }
    }

    private fun updateCurrentConfigs(kind: IntegrationManagerConfig.Kind, config: IntegrationManagerConfig?) {
        val hasBeenRemoved = currentConfigs.removeAll { currentConfig ->
            currentConfig.kind == kind
        }
        if (config != null) {
            currentConfigs.add(config)
        }
        if (hasBeenRemoved || config != null) {
            notifyConfigurationChanged()
        }
    }
}
