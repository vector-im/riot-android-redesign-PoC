/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.login2

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.airbnb.mvrx.viewModel
import im.vector.app.R
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.extensions.POP_BACK_STACK_EXCLUSIVE
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.extensions.addFragmentToBackstack
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.extensions.resetBackstack
import im.vector.app.core.platform.ToolbarConfigurable
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.login.LoginCaptchaFragmentArgument
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.login.LoginGenericTextInputFormFragmentArgument
import im.vector.app.features.login.LoginWaitForEmailFragmentArgument
import im.vector.app.features.login.TextInputFormFragmentMode
import im.vector.app.features.login.isSupported
import im.vector.app.features.login.terms.LoginTermsFragmentArgument
import im.vector.app.features.login.terms.toLocalizedLoginTerms
import im.vector.app.features.login2.created.AccountCreatedFragment
import im.vector.app.features.login2.terms.LoginTermsFragment2
import im.vector.app.features.pin.UnlockedActivity

import org.matrix.android.sdk.api.auth.registration.FlowResult
import org.matrix.android.sdk.api.auth.registration.Stage
import org.matrix.android.sdk.api.extensions.tryOrNull
import javax.inject.Inject

/**
 * The LoginActivity manages the fragment navigation and also display the loading View
 */
open class LoginActivity2 : VectorBaseActivity<ActivityLoginBinding>(), ToolbarConfigurable, UnlockedActivity {

    private val loginViewModel: LoginViewModel2 by viewModel()

    @Inject lateinit var loginViewModelFactory: LoginViewModel2.Factory

    @CallSuper
    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    private val enterAnim = R.anim.enter_fade_in
    private val exitAnim = R.anim.exit_fade_out

    private val popEnterAnim = R.anim.no_anim
    private val popExitAnim = R.anim.exit_fade_out

    private val topFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(R.id.loginFragmentContainer)

    private val commonOption: (FragmentTransaction) -> Unit = { ft ->
        // Find the loginLogo on the current Fragment, this should not return null
        (topFragment?.view as? ViewGroup)
                // Find findViewById does not work, I do not know why
                // findViewById<View?>(R.id.loginLogo)
                ?.children
                ?.firstOrNull { it.id == R.id.loginLogo }
                ?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
        ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
    }

    final override fun getBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override fun initUiAndData() {
        if (isFirstCreation()) {
            addFirstFragment()
        }

        loginViewModel
                .subscribe(this) {
                    updateWithState(it)
                }

        loginViewModel.observeViewEvents { handleLoginViewEvents(it) }

        // Get config extra
        val loginConfig = intent.getParcelableExtra<LoginConfig?>(EXTRA_CONFIG)
        if (isFirstCreation()) {
            // TODO Check this
            loginViewModel.handle(LoginAction2.InitWith(loginConfig))
        }
    }

    protected open fun addFirstFragment() {
        addFragment(R.id.loginFragmentContainer, LoginSplashSignUpSignInSelectionFragment2::class.java)
    }

    private fun handleLoginViewEvents(event: LoginViewEvents2) {
        when (event) {
            is LoginViewEvents2.RegistrationFlowResult                     -> {
                // Check that all flows are supported by the application
                if (event.flowResult.missingStages.any { !it.isSupported() }) {
                    // Display a popup to propose use web fallback
                    onRegistrationStageNotSupported()
                } else {
                    if (event.isRegistrationStarted) {
                        // Go on with registration flow
                        handleRegistrationNavigation(event.flowResult)
                    } else {
                        /*
                        // First ask for login and password
                        // I add a tag to indicate that this fragment is a registration stage.
                        // This way it will be automatically popped in when starting the next registration stage
                        addFragmentToBackstack(R.id.loginFragmentContainer,
                                LoginFragment2::class.java,
                                tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                                option = commonOption
                        )

                         */
                    }
                }
            }
            is LoginViewEvents2.OutdatedHomeserver                         -> {
                AlertDialog.Builder(this)
                        .setTitle(R.string.login_error_outdated_homeserver_title)
                        .setMessage(R.string.login_error_outdated_homeserver_warning_content)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                Unit
            }
            is LoginViewEvents2.OpenServerSelection                        ->
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginServerSelectionFragment2::class.java,
                        option = { ft ->
                            findViewById<View?>(R.id.loginSplashLogo)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // Disable transition of text
                            // findViewById<View?>(R.id.loginSplashTitle)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // No transition here now actually
                            // findViewById<View?>(R.id.loginSplashSubmit)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // TODO Disabled because it provokes a flickering
                            // ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
                        })
            is LoginViewEvents2.OpenHomeServerUrlFormScreen                -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginServerUrlFormFragment2::class.java,
                        option = commonOption)
            }
            is LoginViewEvents2.OpenSignInEnterIdentifierScreen            -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginFragmentSigninUsername2::class.java,
                        option = { ft ->
                            findViewById<View?>(R.id.loginSplashLogo)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // Disable transition of text
                            // findViewById<View?>(R.id.loginSplashTitle)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // No transition here now actually
                            // findViewById<View?>(R.id.loginSplashSubmit)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // TODO Disabled because it provokes a flickering
                            // ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
                        })
            }
            is LoginViewEvents2.OpenSsoOnlyScreen                          -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginSsoOnlyFragment2::class.java,
                        option = commonOption)
            }
            is LoginViewEvents2.OnWebLoginError                            -> onWebLoginError(event)
            is LoginViewEvents2.OpenResetPasswordScreen                    ->
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginResetPasswordFragment2::class.java,
                        option = commonOption)
            is LoginViewEvents2.OnResetPasswordSendThreePidDone            -> {
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginResetPasswordMailConfirmationFragment2::class.java,
                        option = commonOption)
            }
            is LoginViewEvents2.OnResetPasswordMailConfirmationSuccess     -> {
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginResetPasswordSuccessFragment2::class.java,
                        option = commonOption)
            }
            is LoginViewEvents2.OnResetPasswordMailConfirmationSuccessDone -> {
                // Go back to the login fragment
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
            }
            is LoginViewEvents2.OnSendEmailSuccess       ->
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginWaitForEmailFragment2::class.java,
                        LoginWaitForEmailFragmentArgument(event.email),
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            is LoginViewEvents2.OpenSigninPasswordScreen -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginFragmentSigninPassword2::class.java,
                        tag = FRAGMENT_LOGIN_TAG,
                        option = commonOption)
            }
            is LoginViewEvents2.OpenSignupPasswordScreen -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginFragmentSignupPassword2::class.java,
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            }
            is LoginViewEvents2.OpenSignUpChooseUsernameScreen             -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginFragmentSignupUsername2::class.java,
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            }
            is LoginViewEvents2.OpenSignInWithAnythingScreen               -> {
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginFragmentToAny2::class.java,
                        tag = FRAGMENT_LOGIN_TAG,
                        option = commonOption)
            }
            is LoginViewEvents2.OnSendMsisdnSuccess                        ->
                addFragmentToBackstack(R.id.loginFragmentContainer,
                        LoginGenericTextInputFormFragment2::class.java,
                        LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.ConfirmMsisdn, true, event.msisdn),
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            is LoginViewEvents2.Failure                                    ->
                // This is handled by the Fragments
                Unit
            is LoginViewEvents2.OnLoginModeNotSupported                    ->
                onLoginModeNotSupported(event.supportedTypes)
            is LoginViewEvents2.OnSessionCreated                           -> handleOnSessionCreated(event)
            is LoginViewEvents2.Finish                                     -> terminate(true)
            is LoginViewEvents2.CancelRegistration                         -> handleCancelRegistration()
        }.exhaustive
    }

    private fun handleCancelRegistration() {
        // Cleanup the back stack
        resetBackstack()
    }

    private fun handleOnSessionCreated(event: LoginViewEvents2.OnSessionCreated) {
        if (event.newAccount) {
            // Propose to set avatar and display name
            // Back on this Fragment will finish the Activity
            addFragmentToBackstack(R.id.loginFragmentContainer,
                    AccountCreatedFragment::class.java,
                    option = commonOption)
        } else {
            terminate(false)
        }
    }

    private fun terminate(newAccount: Boolean) {
        val intent = HomeActivity.newIntent(
                this,
                accountCreation = newAccount
        )
        startActivity(intent)
        finish()
    }

    private fun updateWithState(LoginViewState2: LoginViewState2) {
        // Loading
        setIsLoading(LoginViewState2.isLoading)
    }

    // Hack for AccountCreatedFragment
    fun setIsLoading(isLoading: Boolean) {
        views.loginLoading.isVisible = isLoading
    }

    private fun onWebLoginError(onWebLoginError: LoginViewEvents2.OnWebLoginError) {
        // Pop the backstack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // And inform the user
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_error)
                .setMessage(getString(R.string.login_sso_error_message, onWebLoginError.description, onWebLoginError.errorCode))
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    /**
     * Handle the SSO redirection here
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        intent?.data
                ?.let { tryOrNull { it.getQueryParameter("loginToken") } }
                ?.let { loginViewModel.handle(LoginAction2.LoginWithToken(it)) }
    }

    private fun onRegistrationStageNotSupported() {
        AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.login_registration_not_supported))
                .setPositiveButton(R.string.yes) { _, _ ->
                    addFragmentToBackstack(R.id.loginFragmentContainer,
                            LoginWebFragment2::class.java,
                            option = commonOption)
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    private fun onLoginModeNotSupported(supportedTypes: List<String>) {
        AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.login_mode_not_supported, supportedTypes.joinToString { "'$it'" }))
                .setPositiveButton(R.string.yes) { _, _ ->
                    addFragmentToBackstack(R.id.loginFragmentContainer,
                            LoginWebFragment2::class.java,
                            option = commonOption)
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    private fun handleRegistrationNavigation(flowResult: FlowResult) {
        // Complete all mandatory stages first
        val mandatoryStage = flowResult.missingStages.firstOrNull { it.mandatory }

        if (mandatoryStage != null) {
            doStage(mandatoryStage)
        } else {
            // Consider optional stages
            val optionalStage = flowResult.missingStages.firstOrNull { !it.mandatory && it !is Stage.Dummy }
            if (optionalStage == null) {
                // Should not happen...
            } else {
                doStage(optionalStage)
            }
        }
    }

    private fun doStage(stage: Stage) {
        // Ensure there is no fragment for registration stage in the backstack
        supportFragmentManager.popBackStack(FRAGMENT_REGISTRATION_STAGE_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        when (stage) {
            is Stage.ReCaptcha -> addFragmentToBackstack(R.id.loginFragmentContainer,
                    LoginCaptchaFragment2::class.java,
                    LoginCaptchaFragmentArgument(stage.publicKey),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Email     -> addFragmentToBackstack(R.id.loginFragmentContainer,
                    LoginGenericTextInputFormFragment2::class.java,
                    LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.SetEmail, stage.mandatory),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Msisdn    -> addFragmentToBackstack(R.id.loginFragmentContainer,
                    LoginGenericTextInputFormFragment2::class.java,
                    LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.SetMsisdn, stage.mandatory),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Terms     -> addFragmentToBackstack(R.id.loginFragmentContainer,
                    LoginTermsFragment2::class.java,
                    LoginTermsFragmentArgument(stage.policies.toLocalizedLoginTerms(getString(R.string.resources_language))),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            else               -> Unit // Should not happen
        }
    }

    override fun configure(toolbar: Toolbar) {
        configureToolbar(toolbar)
    }

    companion object {
        private const val FRAGMENT_REGISTRATION_STAGE_TAG = "FRAGMENT_REGISTRATION_STAGE_TAG"
        private const val FRAGMENT_LOGIN_TAG = "FRAGMENT_LOGIN_TAG"

        private const val EXTRA_CONFIG = "EXTRA_CONFIG"

        // Note that the domain can be displayed to the user for confirmation that he trusts it. So use a human readable string
        const val VECTOR_REDIRECT_URL = "element://connect"

        fun newIntent(context: Context, loginConfig: LoginConfig?): Intent {
            return Intent(context, LoginActivity2::class.java).apply {
                putExtra(EXTRA_CONFIG, loginConfig)
            }
        }
    }
}
