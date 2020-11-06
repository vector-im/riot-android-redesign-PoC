/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.login

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.configureAndStart
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.ensureTrailingSlash
import im.vector.app.features.signout.soft.SoftLogoutActivity
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.LoginFlowResult
import org.matrix.android.sdk.api.auth.data.LoginFlowTypes
import org.matrix.android.sdk.api.auth.login.LoginWizard
import org.matrix.android.sdk.api.auth.registration.FlowResult
import org.matrix.android.sdk.api.auth.registration.RegistrationResult
import org.matrix.android.sdk.api.auth.registration.RegistrationWizard
import org.matrix.android.sdk.api.auth.registration.Stage
import org.matrix.android.sdk.api.auth.wellknown.WellknownResult
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.Cancelable
import timber.log.Timber
import java.util.concurrent.CancellationException

/**
 *
 */
class LoginViewModel @AssistedInject constructor(
        @Assisted initialState: LoginViewState,
        private val applicationContext: Context,
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val homeServerConnectionConfigFactory: HomeServerConnectionConfigFactory,
        private val reAuthHelper: ReAuthHelper,
        private val stringProvider: StringProvider,
        private val analyticsEngine: AnalyticsEngine
) : VectorViewModel<LoginViewState, LoginAction, LoginViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: LoginViewState): LoginViewModel
    }

    companion object : MvRxViewModelFactory<LoginViewModel, LoginViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: LoginViewState): LoginViewModel? {
            return when (val activity: FragmentActivity = (viewModelContext as ActivityViewModelContext).activity()) {
                is LoginActivity      -> activity.loginViewModelFactory.create(state)
                is SoftLogoutActivity -> activity.loginViewModelFactory.create(state)
                else                  -> error("Invalid Activity")
            }
        }
    }

    // Store the last action, to redo it after user has trusted the untrusted certificate
    private var lastAction: LoginAction? = null
    private var currentHomeServerConnectionConfig: HomeServerConnectionConfig? = null

    private val matrixOrgUrl = stringProvider.getString(R.string.matrix_org_server_url).ensureTrailingSlash()

    val currentThreePid: String?
        get() = registrationWizard?.currentThreePid

    // True when login and password has been sent with success to the homeserver
    val isRegistrationStarted: Boolean
        get() = authenticationService.isRegistrationStarted

    private val registrationWizard: RegistrationWizard?
        get() = authenticationService.getRegistrationWizard()

    private val loginWizard: LoginWizard?
        get() = authenticationService.getLoginWizard()

    private var loginConfig: LoginConfig? = null

    private var currentTask: Cancelable? = null

    override fun handle(action: LoginAction) {
        when (action) {
            is LoginAction.UpdateServerType           -> handleUpdateServerType(action)
            is LoginAction.UpdateSignMode             -> handleUpdateSignMode(action)
            is LoginAction.InitWith                   -> handleInitWith(action)
            is LoginAction.UpdateHomeServer           -> handleUpdateHomeserver(action).also { lastAction = action }
            is LoginAction.LoginOrRegister            -> handleLoginOrRegister(action).also { lastAction = action }
            is LoginAction.LoginWithToken             -> handleLoginWithToken(action)
            is LoginAction.WebLoginSuccess            -> handleWebLoginSuccess(action)
            is LoginAction.ResetPassword              -> handleResetPassword(action)
            is LoginAction.ResetPasswordMailConfirmed -> handleResetPasswordMailConfirmed()
            is LoginAction.RegisterAction             -> handleRegisterAction(action)
            is LoginAction.ResetAction                -> handleResetAction(action)
            is LoginAction.SetupSsoForSessionRecovery -> handleSetupSsoForSessionRecovery(action)
            is LoginAction.UserAcceptCertificate      -> handleUserAcceptCertificate(action)
            is LoginAction.PostViewEvent              -> _viewEvents.post(action.viewEvent)
        }.exhaustive
    }

    private fun handleUserAcceptCertificate(action: LoginAction.UserAcceptCertificate) {
        // It happen when we get the login flow, or during direct authentication.
        // So alter the homeserver config and retrieve again the login flow
        when (val finalLastAction = lastAction) {
            is LoginAction.UpdateHomeServer ->
                currentHomeServerConnectionConfig
                        ?.let { it.copy(allowedFingerprints = it.allowedFingerprints + action.fingerprint) }
                        ?.let { getLoginFlow(it) }
            is LoginAction.LoginOrRegister  ->
                handleDirectLogin(
                        finalLastAction,
                        HomeServerConnectionConfig.Builder()
                                // Will be replaced by the task
                                .withHomeServerUri("https://dummy.org")
                                .withAllowedFingerPrints(listOf(action.fingerprint))
                                .build()
                )
        }
    }

    private fun handleLoginWithToken(action: LoginAction.LoginWithToken) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState {
                copy(
                        asyncLoginAction = Fail(Throwable("Bad configuration"))
                )
            }
        } else {
            setState {
                copy(
                        asyncLoginAction = Loading()
                )
            }

            currentTask = safeLoginWizard.loginWithToken(
                    action.loginToken,
                    object : MatrixCallback<Session> {
                        override fun onSuccess(data: Session) {
                            onSessionCreated(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            _viewEvents.post(LoginViewEvents.Failure(failure))
                            setState {
                                copy(
                                        asyncLoginAction = Fail(failure)
                                )
                            }
                        }
                    })
        }
    }

    private fun handleSetupSsoForSessionRecovery(action: LoginAction.SetupSsoForSessionRecovery) {
        setState {
            copy(
                    signMode = SignMode.SignIn,
                    loginMode = LoginMode.Sso,
                    homeServerUrl = action.homeServerUrl,
                    deviceId = action.deviceId
            )
        }
    }

    private fun handleRegisterAction(action: LoginAction.RegisterAction) {
        when (action) {
            is LoginAction.CaptchaDone                  -> handleCaptchaDone(action)
            is LoginAction.AcceptTerms                  -> handleAcceptTerms()
            is LoginAction.RegisterDummy                -> handleRegisterDummy()
            is LoginAction.AddThreePid                  -> handleAddThreePid(action)
            is LoginAction.SendAgainThreePid            -> handleSendAgainThreePid()
            is LoginAction.ValidateThreePid             -> handleValidateThreePid(action)
            is LoginAction.CheckIfEmailHasBeenValidated -> handleCheckIfEmailHasBeenValidated(action)
            is LoginAction.StopEmailValidationCheck     -> handleStopEmailValidationCheck()
        }
    }

    private fun handleCheckIfEmailHasBeenValidated(action: LoginAction.CheckIfEmailHasBeenValidated) {
        // We do not want the common progress bar to be displayed, so we do not change asyncRegistration value in the state
        currentTask?.cancel()
        currentTask = null
        currentTask = registrationWizard?.checkIfEmailHasBeenValidated(action.delayMillis, registrationCallback)
    }

    private fun handleStopEmailValidationCheck() {
        currentTask?.cancel()
        currentTask = null
    }

    private fun handleValidateThreePid(action: LoginAction.ValidateThreePid) {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.handleValidateThreePid(action.code, registrationCallback)
    }

    private val registrationCallback = object : MatrixCallback<RegistrationResult> {
        override fun onSuccess(data: RegistrationResult) {
            /*
              // Simulate registration disabled
              onFailure(Failure.ServerError(MatrixError(
                      code = MatrixError.FORBIDDEN,
                      message = "Registration is disabled"
              ), 403))
            */

            setState {
                copy(
                        asyncRegistration = Uninitialized
                )
            }

            when (data) {
                is RegistrationResult.Success      -> onSessionCreated(data.session)
                is RegistrationResult.FlowResponse -> onFlowResponse(data.flowResult)
            }
        }

        override fun onFailure(failure: Throwable) {
            if (failure !is CancellationException) {
                _viewEvents.post(LoginViewEvents.Failure(failure))
            }
            setState {
                copy(
                        asyncRegistration = Uninitialized
                )
            }
        }
    }

    private fun handleAddThreePid(action: LoginAction.AddThreePid) {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.addThreePid(action.threePid, object : MatrixCallback<RegistrationResult> {
            override fun onSuccess(data: RegistrationResult) {
                setState {
                    copy(
                            asyncRegistration = Uninitialized
                    )
                }
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(LoginViewEvents.Failure(failure))
                setState {
                    copy(
                            asyncRegistration = Uninitialized
                    )
                }
            }
        })
    }

    private fun handleSendAgainThreePid() {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.sendAgainThreePid(object : MatrixCallback<RegistrationResult> {
            override fun onSuccess(data: RegistrationResult) {
                setState {
                    copy(
                            asyncRegistration = Uninitialized
                    )
                }
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(LoginViewEvents.Failure(failure))
                setState {
                    copy(
                            asyncRegistration = Uninitialized
                    )
                }
            }
        })
    }

    private fun handleAcceptTerms() {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.acceptTerms(registrationCallback)
    }

    private fun handleRegisterDummy() {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.dummy(registrationCallback)
    }

    private fun handleRegisterWith(action: LoginAction.LoginOrRegister) {
        setState { copy(asyncRegistration = Loading()) }
        reAuthHelper.data = action.password
        currentTask = registrationWizard?.createAccount(
                action.username,
                action.password,
                action.initialDeviceName,
                registrationCallback
        )
    }

    private fun handleCaptchaDone(action: LoginAction.CaptchaDone) {
        setState { copy(asyncRegistration = Loading()) }
        currentTask = registrationWizard?.performReCaptcha(action.captchaResponse, registrationCallback)
    }

    private fun handleResetAction(action: LoginAction.ResetAction) {
        // Cancel any request
        currentTask?.cancel()
        currentTask = null

        when (action) {
            LoginAction.ResetHomeServerType -> {
                setState {
                    copy(
                            serverType = ServerType.Unknown
                    )
                }
            }
            LoginAction.ResetHomeServerUrl  -> {
                authenticationService.reset()

                setState {
                    copy(
                            asyncHomeServerLoginFlowRequest = Uninitialized,
                            homeServerUrl = null,
                            loginMode = LoginMode.Unknown,
                            serverType = ServerType.Unknown,
                            loginModeSupportedTypes = emptyList()
                    )
                }
            }
            LoginAction.ResetSignMode       -> {
                setState {
                    copy(
                            asyncHomeServerLoginFlowRequest = Uninitialized,
                            signMode = SignMode.Unknown,
                            loginMode = LoginMode.Unknown,
                            loginModeSupportedTypes = emptyList()
                    )
                }
            }
            LoginAction.ResetLogin          -> {
                authenticationService.cancelPendingLoginOrRegistration()

                setState {
                    copy(
                            asyncLoginAction = Uninitialized,
                            asyncRegistration = Uninitialized
                    )
                }
            }
            LoginAction.ResetResetPassword  -> {
                setState {
                    copy(
                            asyncResetPassword = Uninitialized,
                            asyncResetMailConfirmed = Uninitialized,
                            resetPasswordEmail = null
                    )
                }
            }
        }
    }

    private fun handleUpdateSignMode(action: LoginAction.UpdateSignMode) {
        setState {
            copy(
                    signMode = action.signMode
            )
        }

        when (action.signMode) {
            SignMode.SignUp             -> startRegistrationFlow()
            SignMode.SignIn             -> startAuthenticationFlow()
            SignMode.SignInWithMatrixId -> _viewEvents.post(LoginViewEvents.OnSignModeSelected(SignMode.SignInWithMatrixId))
            SignMode.Unknown            -> Unit
        }
    }

    private fun handleUpdateServerType(action: LoginAction.UpdateServerType) {
        setState {
            copy(
                    serverType = action.serverType
            )
        }

        when (action.serverType) {
            ServerType.Unknown   -> Unit /* Should not happen */
            ServerType.MatrixOrg ->
                // Request login flow here
                handle(LoginAction.UpdateHomeServer(matrixOrgUrl))
            ServerType.EMS,
            ServerType.Other     -> _viewEvents.post(LoginViewEvents.OnServerSelectionDone(action.serverType))
        }.exhaustive
    }

    private fun handleInitWith(action: LoginAction.InitWith) {
        loginConfig = action.loginConfig

        // If there is a pending email validation continue on this step
        try {
            if (registrationWizard?.isRegistrationStarted == true) {
                currentThreePid?.let {
                    handle(LoginAction.PostViewEvent(LoginViewEvents.OnSendEmailSuccess(it)))
                }
            }
        } catch (e: Throwable) {
            // NOOP. API is designed to use wizards in a login/registration flow,
            // but we need to check the state anyway.
        }
    }

    private fun handleResetPassword(action: LoginAction.ResetPassword) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState {
                copy(
                        asyncResetPassword = Fail(Throwable("Bad configuration")),
                        asyncResetMailConfirmed = Uninitialized
                )
            }
        } else {
            setState {
                copy(
                        asyncResetPassword = Loading(),
                        asyncResetMailConfirmed = Uninitialized
                )
            }

            currentTask = safeLoginWizard.resetPassword(action.email, action.newPassword, object : MatrixCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    setState {
                        copy(
                                asyncResetPassword = Success(data),
                                resetPasswordEmail = action.email
                        )
                    }

                    _viewEvents.post(LoginViewEvents.OnResetPasswordSendThreePidDone)
                }

                override fun onFailure(failure: Throwable) {
                    setState {
                        copy(
                                asyncResetPassword = Fail(failure)
                        )
                    }
                }
            })
        }
    }

    private fun handleResetPasswordMailConfirmed() {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState {
                copy(
                        asyncResetPassword = Uninitialized,
                        asyncResetMailConfirmed = Fail(Throwable("Bad configuration"))
                )
            }
        } else {
            setState {
                copy(
                        asyncResetPassword = Uninitialized,
                        asyncResetMailConfirmed = Loading()
                )
            }

            currentTask = safeLoginWizard.resetPasswordMailConfirmed(object : MatrixCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    setState {
                        copy(
                                asyncResetMailConfirmed = Success(data),
                                resetPasswordEmail = null
                        )
                    }

                    _viewEvents.post(LoginViewEvents.OnResetPasswordMailConfirmationSuccess)
                }

                override fun onFailure(failure: Throwable) {
                    setState {
                        copy(
                                asyncResetMailConfirmed = Fail(failure)
                        )
                    }
                }
            })
        }
    }

    private fun handleLoginOrRegister(action: LoginAction.LoginOrRegister) = withState { state ->
        when (state.signMode) {
            SignMode.Unknown            -> error("Developer error, invalid sign mode")
            SignMode.SignIn             -> handleLogin(action)
            SignMode.SignUp             -> handleRegisterWith(action)
            SignMode.SignInWithMatrixId -> handleDirectLogin(action, null)
        }.exhaustive
    }

    private fun handleDirectLogin(action: LoginAction.LoginOrRegister, homeServerConnectionConfig: HomeServerConnectionConfig?) {
        setState {
            copy(
                    asyncLoginAction = Loading()
            )
        }

        authenticationService.getWellKnownData(action.username, homeServerConnectionConfig, object : MatrixCallback<WellknownResult> {
            override fun onSuccess(data: WellknownResult) {
                when (data) {
                    is WellknownResult.Prompt          ->
                        onWellknownSuccess(action, data, homeServerConnectionConfig)
                    is WellknownResult.FailPrompt      ->
                        // Relax on IS discovery if home server is valid
                        if (data.homeServerUrl != null && data.wellKnown != null) {
                            onWellknownSuccess(action, WellknownResult.Prompt(data.homeServerUrl!!, null, data.wellKnown!!), homeServerConnectionConfig)
                        } else {
                            onWellKnownError()
                        }
                    is WellknownResult.InvalidMatrixId -> {
                        setState {
                            copy(
                                    asyncLoginAction = Uninitialized
                            )
                        }
                        _viewEvents.post(LoginViewEvents.Failure(Exception(stringProvider.getString(R.string.login_signin_matrix_id_error_invalid_matrix_id))))
                    }
                    else                               -> {
                        onWellKnownError()
                    }
                }.exhaustive
            }

            override fun onFailure(failure: Throwable) {
                onDirectLoginError(failure)
            }
        })
    }

    private fun onWellKnownError() {
        setState {
            copy(
                    asyncLoginAction = Uninitialized
            )
        }
        _viewEvents.post(LoginViewEvents.Failure(Exception(stringProvider.getString(R.string.autodiscover_well_known_error))))
    }

    private fun onWellknownSuccess(action: LoginAction.LoginOrRegister,
                                   wellKnownPrompt: WellknownResult.Prompt,
                                   homeServerConnectionConfig: HomeServerConnectionConfig?) {
        val alteredHomeServerConnectionConfig = homeServerConnectionConfig
                ?.copy(
                        homeServerUri = Uri.parse(wellKnownPrompt.homeServerUrl),
                        identityServerUri = wellKnownPrompt.identityServerUrl?.let { Uri.parse(it) }
                )
                ?: HomeServerConnectionConfig(
                        homeServerUri = Uri.parse(wellKnownPrompt.homeServerUrl),
                        identityServerUri = wellKnownPrompt.identityServerUrl?.let { Uri.parse(it) }
                )

        authenticationService.directAuthentication(
                alteredHomeServerConnectionConfig,
                action.username,
                action.password,
                action.initialDeviceName,
                object : MatrixCallback<Session> {
                    override fun onSuccess(data: Session) {
                        onSessionCreated(data)
                    }

                    override fun onFailure(failure: Throwable) {
                        onDirectLoginError(failure)
                    }
                })
    }

    private fun onDirectLoginError(failure: Throwable) {
        if (failure is Failure.UnrecognizedCertificateFailure) {
            // Display this error in a dialog
            _viewEvents.post(LoginViewEvents.Failure(failure))
            setState {
                copy(
                        asyncLoginAction = Uninitialized
                )
            }
        } else {
            setState {
                copy(
                        asyncLoginAction = Fail(failure)
                )
            }
        }
    }

    private fun handleLogin(action: LoginAction.LoginOrRegister) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState {
                copy(
                        asyncLoginAction = Fail(Throwable("Bad configuration"))
                )
            }
        } else {
            setState {
                copy(
                        asyncLoginAction = Loading()
                )
            }

            currentTask = safeLoginWizard.login(
                    action.username,
                    action.password,
                    action.initialDeviceName,
                    object : MatrixCallback<Session> {
                        override fun onSuccess(data: Session) {
                            reAuthHelper.data = action.password
                            onSessionCreated(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            setState {
                                copy(
                                        asyncLoginAction = Fail(failure)
                                )
                            }
                        }
                    })
        }
    }

    private fun startRegistrationFlow() {
        setState {
            copy(
                    asyncRegistration = Loading()
            )
        }

        currentTask = registrationWizard?.getRegistrationFlow(registrationCallback)
    }

    private fun startAuthenticationFlow() {
        // Ensure Wizard is ready
        loginWizard

        _viewEvents.post(LoginViewEvents.OnSignModeSelected(SignMode.SignIn))
    }

    private fun onFlowResponse(flowResult: FlowResult) {
        // If dummy stage is mandatory, and password is already sent, do the dummy stage now
        if (isRegistrationStarted
                && flowResult.missingStages.any { it is Stage.Dummy && it.mandatory }) {
            handleRegisterDummy()
        } else {
            // Notify the user
            _viewEvents.post(LoginViewEvents.RegistrationFlowResult(flowResult, isRegistrationStarted))
        }
    }

    private fun onSessionCreated(session: Session) {
        activeSessionHolder.setActiveSession(session)
        authenticationService.reset()
        session.configureAndStart(applicationContext)
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.Init(session))
        setState {
            copy(
                    asyncLoginAction = Success(Unit)
            )
        }
    }

    private fun handleWebLoginSuccess(action: LoginAction.WebLoginSuccess) = withState { state ->
        val homeServerConnectionConfigFinal = homeServerConnectionConfigFactory.create(state.homeServerUrl)

        if (homeServerConnectionConfigFinal == null) {
            // Should not happen
            Timber.w("homeServerConnectionConfig is null")
        } else {
            authenticationService.createSessionFromSso(homeServerConnectionConfigFinal, action.credentials, object : MatrixCallback<Session> {
                override fun onSuccess(data: Session) {
                    onSessionCreated(data)
                }

                override fun onFailure(failure: Throwable) = setState {
                    copy(asyncLoginAction = Fail(failure))
                }
            })
        }
    }

    private fun handleUpdateHomeserver(action: LoginAction.UpdateHomeServer) {
        val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(action.homeServerUrl)

        if (homeServerConnectionConfig == null) {
            // This is invalid
            _viewEvents.post(LoginViewEvents.Failure(Throwable("Unable to create a HomeServerConnectionConfig")))
        } else {
            getLoginFlow(homeServerConnectionConfig)
        }
    }

    private fun getLoginFlow(homeServerConnectionConfig: HomeServerConnectionConfig) {
        currentHomeServerConnectionConfig = homeServerConnectionConfig

        currentTask?.cancel()
        currentTask = null
        authenticationService.cancelPendingLoginOrRegistration()

        setState {
            copy(
                    asyncHomeServerLoginFlowRequest = Loading(),
                    // If user has entered https://matrix.org, ensure that server type is ServerType.MatrixOrg
                    // It is also useful to set the value again in the case of a certificate error on matrix.org
                    serverType = if (homeServerConnectionConfig.homeServerUri.toString() == matrixOrgUrl) ServerType.MatrixOrg else serverType
            )
        }

        currentTask = authenticationService.getLoginFlow(homeServerConnectionConfig, object : MatrixCallback<LoginFlowResult> {
            override fun onFailure(failure: Throwable) {
                _viewEvents.post(LoginViewEvents.Failure(failure))
                setState {
                    copy(
                            asyncHomeServerLoginFlowRequest = Uninitialized,
                            // If we were trying to retrieve matrix.org login flow, also reset the serverType
                            serverType = if (serverType == ServerType.MatrixOrg) ServerType.Unknown else serverType
                    )
                }
            }

            override fun onSuccess(data: LoginFlowResult) {
                when (data) {
                    is LoginFlowResult.Success -> {
                        val loginMode = when {
                            // SSO login is taken first
                            data.supportedLoginTypes.contains(LoginFlowTypes.SSO)      -> LoginMode.Sso
                            data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD) -> LoginMode.Password
                            else                                                       -> LoginMode.Unsupported
                        }

                        // FIXME We should post a view event here normally?
                        setState {
                            copy(
                                    asyncHomeServerLoginFlowRequest = Uninitialized,
                                    homeServerUrl = data.homeServerUrl,
                                    loginMode = loginMode,
                                    loginModeSupportedTypes = data.supportedLoginTypes.toList()
                            )
                        }
                        if ((loginMode == LoginMode.Password && !data.isLoginAndRegistrationSupported)
                                || data.isOutdatedHomeserver) {
                            // Notify the UI
                            _viewEvents.post(LoginViewEvents.OutdatedHomeserver)
                        }
                    }
                }
            }
        })
    }

    override fun onCleared() {
        super.onCleared()

        currentTask?.cancel()
    }

    fun getInitialHomeServerUrl(): String? {
        return loginConfig?.homeServerUrl
    }
}
