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

package im.vector.app.features.login2

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.configureAndStart
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.extensions.tryAsync
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.ensureTrailingSlash
import im.vector.app.features.login.HomeServerConnectionConfigFactory
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.ReAuthHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.HomeServerHistoryService
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.LoginFlowTypes
import org.matrix.android.sdk.api.auth.login.LoginWizard
import org.matrix.android.sdk.api.auth.registration.FlowResult
import org.matrix.android.sdk.api.auth.registration.RegistrationAvailability
import org.matrix.android.sdk.api.auth.registration.RegistrationResult
import org.matrix.android.sdk.api.auth.registration.RegistrationWizard
import org.matrix.android.sdk.api.auth.registration.Stage
import org.matrix.android.sdk.api.auth.wellknown.WellknownResult
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import java.util.concurrent.CancellationException

/**
 *
 */
class LoginViewModel2 @AssistedInject constructor(
        @Assisted initialState: LoginViewState2,
        private val applicationContext: Context,
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val homeServerConnectionConfigFactory: HomeServerConnectionConfigFactory,
        private val reAuthHelper: ReAuthHelper,
        private val stringProvider: StringProvider,
        private val homeServerHistoryService: HomeServerHistoryService
) : VectorViewModel<LoginViewState2, LoginAction2, LoginViewEvents2>(initialState) {

    @AssistedFactory
    interface Factory {
        fun create(initialState: LoginViewState2): LoginViewModel2
    }

    init {
        getKnownCustomHomeServersUrls()
    }

    private fun getKnownCustomHomeServersUrls() {
        setState {
            copy(knownCustomHomeServersUrls = homeServerHistoryService.getKnownServersUrls())
        }
    }

    companion object : MvRxViewModelFactory<LoginViewModel2, LoginViewState2> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: LoginViewState2): LoginViewModel2? {
            return when (val activity: FragmentActivity = (viewModelContext as ActivityViewModelContext).activity()) {
                is LoginActivity2 -> activity.loginViewModelFactory.create(state)
                // TODO is SoftLogoutActivity -> activity.loginViewModelFactory.create(state)
                else              -> error("Invalid Activity")
            }
        }
    }

    // Store the last action, to redo it after user has trusted the untrusted certificate
    private var lastAction: LoginAction2? = null
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

    private var currentJob: Job? = null
        set(value) {
            // Cancel any previous Job
            field?.cancel()
            field = value
        }

    override fun handle(action: LoginAction2) {
        when (action) {
            is LoginAction2.EnterServerUrl             -> handleEnterServerUrl()
            is LoginAction2.ChooseAServerForSignin     -> handleChooseAServerForSignin()
            is LoginAction2.UpdateSignMode             -> handleUpdateSignMode(action)
            is LoginAction2.InitWith                   -> handleInitWith(action)
            is LoginAction2.ChooseDefaultHomeServer    -> handle(LoginAction2.UpdateHomeServer(matrixOrgUrl))
            is LoginAction2.UpdateHomeServer           -> handleUpdateHomeserver(action).also { lastAction = action }
            is LoginAction2.SetUserName                -> handleSetUserName(action).also { lastAction = action }
            is LoginAction2.SetUserPassword            -> handleSetUserPassword(action).also { lastAction = action }
            is LoginAction2.LoginWith                  -> handleLoginWith(action).also { lastAction = action }
            is LoginAction2.LoginWithToken             -> handleLoginWithToken(action)
            is LoginAction2.WebLoginSuccess            -> handleWebLoginSuccess(action)
            is LoginAction2.ResetPassword              -> handleResetPassword(action)
            is LoginAction2.ResetPasswordMailConfirmed -> handleResetPasswordMailConfirmed()
            is LoginAction2.RegisterAction             -> handleRegisterAction(action)
            is LoginAction2.ResetAction                -> handleResetAction(action)
            is LoginAction2.SetupSsoForSessionRecovery -> handleSetupSsoForSessionRecovery(action)
            is LoginAction2.UserAcceptCertificate      -> handleUserAcceptCertificate(action)
            LoginAction2.ClearHomeServerHistory        -> handleClearHomeServerHistory()
            is LoginAction2.PostViewEvent              -> _viewEvents.post(action.viewEvent)
            is LoginAction2.Finish                     -> handleFinish()
        }.exhaustive
    }

    private fun handleFinish() {
        // Just post a view Event
        _viewEvents.post(LoginViewEvents2.Finish)
    }

    private fun handleChooseAServerForSignin() {
        // Just post a view Event
        _viewEvents.post(LoginViewEvents2.OpenServerSelection)
    }

    private fun handleUserAcceptCertificate(action: LoginAction2.UserAcceptCertificate) {
        // It happens when we get the login flow, or during direct authentication.
        // So alter the homeserver config and retrieve again the login flow
        when (val finalLastAction = lastAction) {
            is LoginAction2.UpdateHomeServer -> {
                currentHomeServerConnectionConfig
                        ?.let { it.copy(allowedFingerprints = it.allowedFingerprints + action.fingerprint) }
                        ?.let { getLoginFlow(it) }
            }
            is LoginAction2.SetUserName      ->
                handleSetUserNameForSignIn(
                        finalLastAction,
                        HomeServerConnectionConfig.Builder()
                                // Will be replaced by the task
                                .withHomeServerUri("https://dummy.org")
                                .withAllowedFingerPrints(listOf(action.fingerprint))
                                .build()
                )
            is LoginAction2.SetUserPassword  ->
                handleSetUserPassword(finalLastAction)
            is LoginAction2.LoginWith        ->
                handleLoginWith(finalLastAction)
        }
    }

    private fun rememberHomeServer(homeServerUrl: String) {
        homeServerHistoryService.addHomeServerToHistory(homeServerUrl)
        getKnownCustomHomeServersUrls()
    }

    private fun handleClearHomeServerHistory() {
        homeServerHistoryService.clearHistory()
        getKnownCustomHomeServersUrls()
    }

    private fun handleLoginWithToken(action: LoginAction2.LoginWithToken) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            _viewEvents.post(LoginViewEvents2.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }

            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.loginWithToken(action.loginToken)
                } catch (failure: Throwable) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                    null
                }
                        ?.let { onSessionCreated(it) }

                setState { copy(isLoading = false) }
            }
        }
    }

    private fun handleSetupSsoForSessionRecovery(action: LoginAction2.SetupSsoForSessionRecovery) {
        setState {
            copy(
                    signMode = SignMode2.SignIn,
                    loginMode = LoginMode.Sso(action.ssoIdentityProviders),
                    homeServerUrlFromUser = action.homeServerUrl,
                    homeServerUrl = action.homeServerUrl,
                    deviceId = action.deviceId
            )
        }
    }

    private fun handleRegisterAction(action: LoginAction2.RegisterAction) {
        when (action) {
            is LoginAction2.CaptchaDone                  -> handleCaptchaDone(action)
            is LoginAction2.AcceptTerms                  -> handleAcceptTerms()
            is LoginAction2.RegisterDummy                -> handleRegisterDummy()
            is LoginAction2.AddThreePid                  -> handleAddThreePid(action)
            is LoginAction2.SendAgainThreePid            -> handleSendAgainThreePid()
            is LoginAction2.ValidateThreePid             -> handleValidateThreePid(action)
            is LoginAction2.CheckIfEmailHasBeenValidated -> handleCheckIfEmailHasBeenValidated(action)
            is LoginAction2.StopEmailValidationCheck     -> handleStopEmailValidationCheck()
        }
    }

    private fun handleCheckIfEmailHasBeenValidated(action: LoginAction2.CheckIfEmailHasBeenValidated) {
        // We do not want the common progress bar to be displayed, so we do not change asyncRegistration value in the state
        currentJob = executeRegistrationStep(withLoading = false) {
            it.checkIfEmailHasBeenValidated(action.delayMillis)
        }
    }

    private fun handleStopEmailValidationCheck() {
        currentJob = null
    }

    private fun handleValidateThreePid(action: LoginAction2.ValidateThreePid) {
        currentJob = executeRegistrationStep {
            it.handleValidateThreePid(action.code)
        }
    }

    private fun executeRegistrationStep(withLoading: Boolean = true,
                                        block: suspend (RegistrationWizard) -> RegistrationResult): Job {
        if (withLoading) {
            setState { copy(isLoading = true) }
        }
        return viewModelScope.launch {
            try {
                registrationWizard?.let { block(it) }
            } catch (failure: Throwable) {
                if (failure !is CancellationException) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                }
                null
            }
                    ?.let { data ->
                        when (data) {
                            is RegistrationResult.Success      -> onSessionCreated(data.session)
                            is RegistrationResult.FlowResponse -> onFlowResponse(data.flowResult)
                        }
                    }

            setState { copy(isLoading = false) }
        }
    }

    private fun handleAddThreePid(action: LoginAction2.AddThreePid) {
        setState { copy(isLoading = true) }
        currentJob = viewModelScope.launch {
            try {
                registrationWizard?.addThreePid(action.threePid)
            } catch (failure: Throwable) {
                _viewEvents.post(LoginViewEvents2.Failure(failure))
            }
            setState { copy(isLoading = false) }
        }
    }

    private fun handleSendAgainThreePid() {
        setState { copy(isLoading = true) }
        currentJob = viewModelScope.launch {
            try {
                registrationWizard?.sendAgainThreePid()
            } catch (failure: Throwable) {
                _viewEvents.post(LoginViewEvents2.Failure(failure))
            }
            setState { copy(isLoading = false) }
        }
    }

    private fun handleAcceptTerms() {
        currentJob = executeRegistrationStep {
            it.acceptTerms()
        }
    }

    private fun handleRegisterDummy() {
        currentJob = executeRegistrationStep {
            it.dummy()
        }
    }

    /**
     * Check that the user name is available
     */
    private fun handleSetUserNameForSignUp(action: LoginAction2.SetUserName) {
        setState { copy(isLoading = true) }

        val safeRegistrationWizard = registrationWizard ?: error("Invalid")

        viewModelScope.launch {
            val available = safeRegistrationWizard.registrationAvailable(action.username)

            val event = when (available) {
                RegistrationAvailability.Available       -> {
                    // Ask for a password
                    LoginViewEvents2.OpenSignupPasswordScreen
                }
                is RegistrationAvailability.NotAvailable -> {
                    LoginViewEvents2.Failure(available.failure)
                }
            }
            _viewEvents.post(event)
            setState { copy(isLoading = false) }
        }
    }

    private fun handleCaptchaDone(action: LoginAction2.CaptchaDone) {
        currentJob = executeRegistrationStep {
            it.performReCaptcha(action.captchaResponse)
        }
    }

    // TODO Update this
    private fun handleResetAction(action: LoginAction2.ResetAction) {
        // Cancel any request
        currentJob = null

        when (action) {
            LoginAction2.ResetHomeServerUrl -> {
                viewModelScope.launch {
                    authenticationService.reset()
                    setState {
                        copy(
                                homeServerUrlFromUser = null,
                                homeServerUrl = null,
                                loginMode = LoginMode.Unknown
                        )
                    }
                }
            }
            LoginAction2.ResetSignMode      -> {
                setState {
                    copy(
                            signMode = SignMode2.Unknown,
                            loginMode = LoginMode.Unknown
                    )
                }
            }
            LoginAction2.ResetSignin        -> {
                viewModelScope.launch {
                    authenticationService.cancelPendingLoginOrRegistration()
                    setState {
                        copy(isLoading = false)
                    }
                }
                _viewEvents.post(LoginViewEvents2.CancelRegistration)
            }
            LoginAction2.ResetSignup        -> {
                viewModelScope.launch {
                    authenticationService.cancelPendingLoginOrRegistration()
                    setState {
                        // Always create a new state, to ensure the state is correctly reset
                        LoginViewState2(
                                knownCustomHomeServersUrls = knownCustomHomeServersUrls
                        )
                    }
                }
                _viewEvents.post(LoginViewEvents2.CancelRegistration)
            }
            LoginAction2.ResetResetPassword -> {
                setState {
                    copy(
                            resetPasswordEmail = null
                    )
                }
            }
        }
    }

    private fun handleUpdateSignMode(action: LoginAction2.UpdateSignMode) {
        setState {
            copy(
                    signMode = action.signMode
            )
        }

        when (action.signMode) {
            SignMode2.SignUp  -> _viewEvents.post(LoginViewEvents2.OpenServerSelection)
            SignMode2.SignIn  -> _viewEvents.post(LoginViewEvents2.OpenSignInEnterIdentifierScreen)
            SignMode2.Unknown -> Unit
        }
    }

    private fun handleEnterServerUrl() {
        _viewEvents.post(LoginViewEvents2.OpenHomeServerUrlFormScreen)
    }

    private fun handleInitWith(action: LoginAction2.InitWith) {
        loginConfig = action.loginConfig

        // If there is a pending email validation continue on this step
        try {
            if (registrationWizard?.isRegistrationStarted == true) {
                currentThreePid?.let {
                    handle(LoginAction2.PostViewEvent(LoginViewEvents2.OnSendEmailSuccess(it)))
                }
            }
        } catch (e: Throwable) {
            // NOOP. API is designed to use wizards in a login/registration flow,
            // but we need to check the state anyway.
        }
    }

    private fun handleResetPassword(action: LoginAction2.ResetPassword) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            _viewEvents.post(LoginViewEvents2.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }

            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.resetPassword(action.email, action.newPassword)
                } catch (failure: Throwable) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                    setState { copy(isLoading = false) }
                    return@launch
                }

                setState {
                    copy(
                            isLoading = false,
                            resetPasswordEmail = action.email
                    )
                }

                _viewEvents.post(LoginViewEvents2.OnResetPasswordSendThreePidDone)
            }
        }
    }

    private fun handleResetPasswordMailConfirmed() {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            _viewEvents.post(LoginViewEvents2.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }

            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.resetPasswordMailConfirmed()
                } catch (failure: Throwable) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                    setState { copy(isLoading = false) }
                    return@launch
                }
                setState {
                    copy(
                            isLoading = false,
                            resetPasswordEmail = null
                    )
                }

                _viewEvents.post(LoginViewEvents2.OnResetPasswordMailConfirmationSuccess)
            }
        }
    }

    private fun handleSetUserName(action: LoginAction2.SetUserName) = withState { state ->
        setState {
            copy(
                    userName = action.username
            )
        }

        when (state.signMode) {
            SignMode2.Unknown -> error("Developer error, invalid sign mode")
            SignMode2.SignIn  -> handleSetUserNameForSignIn(action, null)
            SignMode2.SignUp  -> handleSetUserNameForSignUp(action)
        }.exhaustive
    }

    private fun handleSetUserPassword(action: LoginAction2.SetUserPassword) = withState { state ->
        when (state.signMode) {
            SignMode2.Unknown -> error("Developer error, invalid sign mode")
            SignMode2.SignIn  -> handleSignInWithPassword(action)
            SignMode2.SignUp  -> handleRegisterWithPassword(action)
        }.exhaustive
    }

    private fun handleRegisterWithPassword(action: LoginAction2.SetUserPassword) = withState { state ->
        val username = state.userName ?: error("Developer error, username not set")

        reAuthHelper.data = action.password
        currentJob = executeRegistrationStep {
            it.createAccount(
                    userName = username,
                    password = action.password,
                    initialDeviceDisplayName = stringProvider.getString(R.string.login_default_session_public_name)
            )
        }
    }

    private fun handleSignInWithPassword(action: LoginAction2.SetUserPassword) = withState { state ->
        val username = state.userName ?: error("Developer error, username not set")
        setState { copy(isLoading = true) }
        loginWith(username, action.password)
    }

    private fun handleLoginWith(action: LoginAction2.LoginWith) {
        setState { copy(isLoading = true) }
        loginWith(action.login, action.password)
    }

    private fun loginWith(login: String, password: String) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            _viewEvents.post(LoginViewEvents2.Failure(Throwable("Bad configuration")))
            setState { copy(isLoading = false) }
        } else {
            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.login(
                            login = login,
                            password = password,
                            deviceName = stringProvider.getString(R.string.login_default_session_public_name)
                    )
                } catch (failure: Throwable) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                    null
                }
                        ?.let {
                            reAuthHelper.data = password
                            onSessionCreated(it)
                        }
                setState { copy(isLoading = false) }
            }
        }
    }

    /**
     * Perform wellknown request
     */
    private fun handleSetUserNameForSignIn(action: LoginAction2.SetUserName, homeServerConnectionConfig: HomeServerConnectionConfig?) {
        setState { copy(isLoading = true) }

        currentJob = viewModelScope.launch {
            val data = try {
                authenticationService.getWellKnownData(action.username, homeServerConnectionConfig)
            } catch (failure: Throwable) {
                onDirectLoginError(failure)
                return@launch
            }
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
                    setState { copy(isLoading = false) }
                    _viewEvents.post(LoginViewEvents2.Failure(Exception(stringProvider.getString(R.string.login_signin_matrix_id_error_invalid_matrix_id))))
                }
                else                               -> {
                    onWellKnownError()
                }
            }.exhaustive
        }
    }

    private fun onWellKnownError() {
        _viewEvents.post(LoginViewEvents2.Failure(Exception(stringProvider.getString(R.string.autodiscover_well_known_error))))
        setState { copy(isLoading = false) }
    }

    private suspend fun onWellknownSuccess(action: LoginAction2.SetUserName,
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

        // Ensure login flow is retrieved, and this is not a SSO only server
        val data = try {
            authenticationService.getLoginFlow(alteredHomeServerConnectionConfig)
        } catch (failure: Throwable) {
            _viewEvents.post(LoginViewEvents2.Failure(failure))
            null
        } ?: return

        val loginMode = when {
            data.supportedLoginTypes.contains(LoginFlowTypes.SSO)
                    && data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD) -> LoginMode.SsoAndPassword(data.ssoIdentityProviders)
            data.supportedLoginTypes.contains(LoginFlowTypes.SSO)                 -> LoginMode.Sso(data.ssoIdentityProviders)
            data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD)            -> LoginMode.Password
            else                                                                  -> LoginMode.Unsupported
        }

        val viewEvent = when (loginMode) {
            LoginMode.Password,
            is LoginMode.SsoAndPassword -> {
                retrieveProfileInfo(action.username)
                // We can navigate to the password screen
                LoginViewEvents2.OpenSigninPasswordScreen
            }
            is LoginMode.Sso            -> {
                LoginViewEvents2.OpenSsoOnlyScreen
            }
            LoginMode.Unsupported       -> LoginViewEvents2.OnLoginModeNotSupported(data.supportedLoginTypes.toList())
            LoginMode.Unknown           -> null
        }
        viewEvent?.let { _viewEvents.post(it) }

        val urlFromUser = action.username.substringAfter(":")
        setState {
            copy(
                    isLoading = false,
                    homeServerUrlFromUser = urlFromUser,
                    homeServerUrl = data.homeServerUrl,
                    loginMode = loginMode
            )
        }

        if ((loginMode == LoginMode.Password && !data.isLoginAndRegistrationSupported)
                || data.isOutdatedHomeserver) {
            // Notify the UI
            _viewEvents.post(LoginViewEvents2.OutdatedHomeserver)
        }
    }

    private suspend fun retrieveProfileInfo(username: String) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard != null) {
            setState { copy(loginProfileInfo = Loading()) }
            val result = tryAsync {
                safeLoginWizard.getProfileInfo(username)
            }
            setState { copy(loginProfileInfo = result) }
        }
    }

    private fun onDirectLoginError(failure: Throwable) {
        _viewEvents.post(LoginViewEvents2.Failure(failure))
        setState { copy(isLoading = false) }
    }

    private fun onFlowResponse(flowResult: FlowResult) {
        // If dummy stage is mandatory, and password is already sent, do the dummy stage now
        if (isRegistrationStarted
                && flowResult.missingStages.any { it is Stage.Dummy && it.mandatory }) {
            handleRegisterDummy()
        } else {
            // Notify the user
            _viewEvents.post(LoginViewEvents2.RegistrationFlowResult(flowResult, isRegistrationStarted))
        }
    }

    private suspend fun onSessionCreated(session: Session) {
        activeSessionHolder.setActiveSession(session)

        authenticationService.reset()
        session.configureAndStart(applicationContext)
        withState { state ->
            _viewEvents.post(LoginViewEvents2.OnSessionCreated(state.signMode == SignMode2.SignUp))
        }
    }

    private fun handleWebLoginSuccess(action: LoginAction2.WebLoginSuccess) = withState { state ->
        val homeServerConnectionConfigFinal = homeServerConnectionConfigFactory.create(state.homeServerUrl)

        if (homeServerConnectionConfigFinal == null) {
            // Should not happen
            Timber.w("homeServerConnectionConfig is null")
        } else {
            currentJob = viewModelScope.launch {
                try {
                    authenticationService.createSessionFromSso(homeServerConnectionConfigFinal, action.credentials)
                } catch (failure: Throwable) {
                    _viewEvents.post(LoginViewEvents2.Failure(failure))
                    null
                }
                        ?.let { onSessionCreated(it) }
            }
        }
    }

    private fun handleUpdateHomeserver(action: LoginAction2.UpdateHomeServer) {
        val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(action.homeServerUrl)
        if (homeServerConnectionConfig == null) {
            // This is invalid
            _viewEvents.post(LoginViewEvents2.Failure(Throwable("Unable to create a HomeServerConnectionConfig")))
        } else {
            getLoginFlow(homeServerConnectionConfig)
        }
    }

    private fun getLoginFlow(homeServerConnectionConfig: HomeServerConnectionConfig) = withState { state ->
        currentHomeServerConnectionConfig = homeServerConnectionConfig

        setState { copy(isLoading = true) }

        currentJob = viewModelScope.launch {
            authenticationService.cancelPendingLoginOrRegistration()

            val data = try {
                authenticationService.getLoginFlow(homeServerConnectionConfig)
            } catch (failure: Throwable) {
                _viewEvents.post(LoginViewEvents2.Failure(failure))
                setState { copy(isLoading = false) }
                null
            } ?: return@launch

            // Valid Homeserver, add it to the history.
            // Note: we add what the user has input, data.homeServerUrl can be different
            rememberHomeServer(homeServerConnectionConfig.homeServerUri.toString())

            val loginMode = when {
                data.supportedLoginTypes.contains(LoginFlowTypes.SSO)
                        && data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD) -> LoginMode.SsoAndPassword(data.ssoIdentityProviders)
                data.supportedLoginTypes.contains(LoginFlowTypes.SSO)                 -> LoginMode.Sso(data.ssoIdentityProviders)
                data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD)            -> LoginMode.Password
                else                                                                  -> LoginMode.Unsupported
            }

            val viewEvent = when (loginMode) {
                LoginMode.Password,
                is LoginMode.SsoAndPassword -> {
                    when (state.signMode) {
                        SignMode2.Unknown -> null
                        SignMode2.SignUp  -> {
                            // Check that registration is possible on this server
                            try {
                                registrationWizard?.getRegistrationFlow()

                                /*
                                    // Simulate registration disabled
                                    throw Failure.ServerError(
                                            error = MatrixError(
                                                    code = MatrixError.M_FORBIDDEN,
                                                    message = "Registration is disabled"
                                            ),
                                            httpCode = 403
                                    )
                                     */

                                LoginViewEvents2.OpenSignUpChooseUsernameScreen
                            } catch (throwable: Throwable) {
                                // Registration disabled?
                                LoginViewEvents2.Failure(throwable)
                            }
                        }
                        SignMode2.SignIn  -> LoginViewEvents2.OpenSignInWithAnythingScreen
                    }
                }
                is LoginMode.Sso            -> {
                    LoginViewEvents2.OpenSsoOnlyScreen
                }
                LoginMode.Unsupported       -> LoginViewEvents2.OnLoginModeNotSupported(data.supportedLoginTypes.toList())
                LoginMode.Unknown           -> null
            }
            viewEvent?.let { _viewEvents.post(it) }

            if ((loginMode == LoginMode.Password && !data.isLoginAndRegistrationSupported)
                    || data.isOutdatedHomeserver) {
                // Notify the UI
                _viewEvents.post(LoginViewEvents2.OutdatedHomeserver)
            }

            setState {
                copy(
                        isLoading = false,
                        homeServerUrlFromUser = homeServerConnectionConfig.homeServerUri.toString(),
                        homeServerUrl = data.homeServerUrl,
                        loginMode = loginMode
                )
            }
        }
    }

    fun getInitialHomeServerUrl(): String? {
        return loginConfig?.homeServerUrl
    }

    fun getSsoUrl(redirectUrl: String, deviceId: String?, providerId: String?): String? {
        return authenticationService.getSsoUrl(redirectUrl, deviceId, providerId)
    }

    fun getFallbackUrl(forSignIn: Boolean, deviceId: String?): String? {
        return authenticationService.getFallbackUrl(forSignIn, deviceId)
    }
}
