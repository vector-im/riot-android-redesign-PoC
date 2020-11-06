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

package im.vector.app.features.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.annotation.CallSuper
import androidx.preference.PreferenceFragmentCompat
import im.vector.app.R
import im.vector.app.core.di.DaggerScreenComponent
import im.vector.app.core.di.HasScreenInjector
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.toast
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import javax.inject.Inject

abstract class VectorSettingsBaseFragment : PreferenceFragmentCompat(), HasScreenInjector {

    val vectorActivity: VectorBaseActivity by lazy {
        activity as VectorBaseActivity
    }

    private var mLoadingView: View? = null

    // members
    protected lateinit var session: Session
    protected lateinit var errorFormatter: ErrorFormatter
    private lateinit var screenComponent: ScreenComponent

    abstract val preferenceXmlRes: Int

    @CallSuper
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(preferenceXmlRes)
        bindPref()
    }

    override fun onAttach(context: Context) {
        screenComponent = DaggerScreenComponent.factory().create(vectorActivity.getVectorComponent(), vectorActivity)
        super.onAttach(context)
        session = screenComponent.activeSessionHolder().getActiveSession()
        errorFormatter = screenComponent.errorFormatter()
        injectWith(injector())
    }

    @Inject protected lateinit var analyticsEngine: AnalyticsEngine

    protected fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun injector(): ScreenComponent {
        return screenComponent
    }

    override fun onResume() {
        super.onResume()
        Timber.i("onResume Fragment ${javaClass.simpleName}")
        vectorActivity.supportActionBar?.setTitle(titleRes)
        // find the view from parent activity
        mLoadingView = vectorActivity.findViewById(R.id.vector_settings_spinner_views)
    }

    @CallSuper
    override fun onDestroyView() {
        super.onDestroyView()
        uiDisposables.clear()
    }

    override fun onDestroy() {
        uiDisposables.dispose()
        super.onDestroy()
    }

    abstract fun bindPref()

    abstract var titleRes: Int

    /* ==========================================================================================
     * Disposable
     * ========================================================================================== */

    private val uiDisposables = CompositeDisposable()

    protected fun Disposable.disposeOnDestroyView() {
        uiDisposables.add(this)
    }

    /* ==========================================================================================
     * Protected
     * ========================================================================================== */

    protected fun notImplemented() {
        // Snackbar cannot be display on PreferenceFragment. TODO It's maybe because the show() method is not used...
        // Snackbar.make(requireView(), R.string.not_implemented, Snackbar.LENGTH_SHORT)
        activity?.toast(R.string.not_implemented)
    }

    /**
     * Display the loading view.
     */
    protected fun displayLoadingView() {
        // search the loading view from the upper view
        if (null == mLoadingView) {
            var parent = view

            while (parent != null && mLoadingView == null) {
                mLoadingView = parent.findViewById(R.id.vector_settings_spinner_views)
                parent = parent.parent as View
            }
        } else {
            mLoadingView?.visibility = View.VISIBLE
        }
    }

    /**
     * Hide the loading view.
     */
    protected fun hideLoadingView() {
        mLoadingView?.visibility = View.GONE
    }

    /**
     * Hide the loading view and refresh the preferences.
     *
     * @param refresh true to refresh the display
     */
    protected fun hideLoadingView(refresh: Boolean) {
        mLoadingView?.visibility = View.GONE

        if (refresh) {
            // TODO refreshDisplay()
        }
    }

    /**
     * A request has been processed.
     * Display a toast if there is a an error message
     *
     * @param errorMessage the error message
     */
    protected fun onCommonDone(errorMessage: String?) {
        if (!isAdded) {
            return
        }
        activity?.runOnUiThread {
            if (errorMessage != null && errorMessage.isNotBlank()) {
                activity?.toast(errorMessage)
            }
            hideLoadingView()
        }
    }
}
