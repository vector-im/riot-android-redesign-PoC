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

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.airbnb.mvrx.args
import im.vector.app.R
import im.vector.app.core.utils.AssetReader
import im.vector.app.databinding.FragmentLoginCaptchaBinding
import im.vector.app.features.login.JavascriptResponse
import im.vector.app.features.login.LoginCaptchaFragmentArgument
import org.matrix.android.sdk.internal.di.MoshiProvider
import timber.log.Timber
import java.net.URLDecoder
import java.util.Formatter
import javax.inject.Inject

/**
 * In this screen, the user is asked to confirm he is not a robot
 */
class LoginCaptchaFragment2 @Inject constructor(
        private val assetReader: AssetReader
) : AbstractLoginFragment2<FragmentLoginCaptchaBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginCaptchaBinding {
        return FragmentLoginCaptchaBinding.inflate(inflater, container, false)
    }

    private val params: LoginCaptchaFragmentArgument by args()

    private var isWebViewLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(state: LoginViewState2) {
        views.loginCaptchaWevView.settings.javaScriptEnabled = true

        val reCaptchaPage = assetReader.readAssetFile("reCaptchaPage.html") ?: error("missing asset reCaptchaPage.html")

        val html = Formatter().format(reCaptchaPage, params.siteKey).toString()
        val mime = "text/html"
        val encoding = "utf-8"

        val homeServerUrl = state.homeServerUrl ?: error("missing url of homeserver")
        views.loginCaptchaWevView.loadDataWithBaseURL(homeServerUrl, html, mime, encoding, null)
        views.loginCaptchaWevView.requestLayout()

        views.loginCaptchaWevView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (!isAdded) {
                    return
                }

                // Show loader
                views.loginCaptchaProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                if (!isAdded) {
                    return
                }

                // Hide loader
                views.loginCaptchaProgress.isVisible = false
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Timber.d("## onReceivedSslError() : ${error.certificate}")

                if (!isAdded) {
                    return
                }

                AlertDialog.Builder(requireActivity())
                        .setMessage(R.string.ssl_could_not_verify)
                        .setPositiveButton(R.string.ssl_trust) { _, _ ->
                            Timber.d("## onReceivedSslError() : the user trusted")
                            handler.proceed()
                        }
                        .setNegativeButton(R.string.ssl_do_not_trust) { _, _ ->
                            Timber.d("## onReceivedSslError() : the user did not trust")
                            handler.cancel()
                        }
                        .setOnKeyListener(DialogInterface.OnKeyListener { dialog, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                                handler.cancel()
                                Timber.d("## onReceivedSslError() : the user dismisses the trust dialog.")
                                dialog.dismiss()
                                return@OnKeyListener true
                            }
                            false
                        })
                        .setCancelable(false)
                        .show()
            }

            // common error message
            private fun onError(errorMessage: String) {
                Timber.e("## onError() : $errorMessage")

                // TODO
                // Toast.makeText(this@AccountCreationCaptchaActivity, errorMessage, Toast.LENGTH_LONG).show()

                // on error case, close this activity
                // runOnUiThread(Runnable { finish() })
            }

            @SuppressLint("NewApi")
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)

                if (request.url.toString().endsWith("favicon.ico")) {
                    // Ignore this error
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    onError(errorResponse.reasonPhrase)
                } else {
                    onError(errorResponse.toString())
                }
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                onError(description)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url?.startsWith("js:") == true) {
                    var json = url.substring(3)
                    var javascriptResponse: JavascriptResponse? = null

                    try {
                        // URL decode
                        json = URLDecoder.decode(json, "UTF-8")
                        javascriptResponse = MoshiProvider.providesMoshi().adapter(JavascriptResponse::class.java).fromJson(json)
                    } catch (e: Exception) {
                        Timber.e(e, "## shouldOverrideUrlLoading(): failed")
                    }

                    val response = javascriptResponse?.response
                    if (javascriptResponse?.action == "verifyCallback" && response != null) {
                        loginViewModel.handle(LoginAction2.CaptchaDone(response))
                    }
                }
                return true
            }
        }
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetSignup)
    }

    override fun updateWithState(state: LoginViewState2) {
        if (!isWebViewLoaded) {
            setupWebView(state)
            isWebViewLoaded = true
        }
    }
}
