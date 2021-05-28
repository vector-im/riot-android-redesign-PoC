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

package im.vector.app.features.settings

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.di.DefaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Object to manage the Locale choice of the user
 */
object VectorLocale {
    private const val APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY"
    private const val APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY"
    private const val APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY"
    private const val APPLICATION_LOCALE_SCRIPT_KEY = "APPLICATION_LOCALE_SCRIPT_KEY"

    private val defaultLocale = Locale("en", "US")

    private const val ISO_15924_LATN = "Latn"

    /**
     * The cache of supported application languages
     */
    private val supportedLocales = mutableListOf<Locale>()

    /**
     * Provides the current application locale
     */
    var applicationLocale = defaultLocale
        private set

    private lateinit var context: Context

    /**
     * Init this object
     */
    fun init(context: Context) {
        this.context = context
        val preferences = DefaultSharedPreferences.getInstance(context)

        if (preferences.contains(APPLICATION_LOCALE_LANGUAGE_KEY)) {
            applicationLocale = Locale(preferences.getString(APPLICATION_LOCALE_LANGUAGE_KEY, "")!!,
                    preferences.getString(APPLICATION_LOCALE_COUNTRY_KEY, "")!!,
                    preferences.getString(APPLICATION_LOCALE_VARIANT_KEY, "")!!
            )
        } else {
            applicationLocale = Locale.getDefault()

            // detect if the default language is used
            val defaultStringValue = getString(context, defaultLocale, R.string.resources_country_code)
            if (defaultStringValue == getString(context, applicationLocale, R.string.resources_country_code)) {
                applicationLocale = defaultLocale
            }

            saveApplicationLocale(applicationLocale)
        }
    }

    /**
     * Save the new application locale.
     */
    fun saveApplicationLocale(locale: Locale) {
        applicationLocale = locale

        DefaultSharedPreferences.getInstance(context).edit {
            val language = locale.language
            if (language.isEmpty()) {
                remove(APPLICATION_LOCALE_LANGUAGE_KEY)
            } else {
                putString(APPLICATION_LOCALE_LANGUAGE_KEY, language)
            }

            val country = locale.country
            if (country.isEmpty()) {
                remove(APPLICATION_LOCALE_COUNTRY_KEY)
            } else {
                putString(APPLICATION_LOCALE_COUNTRY_KEY, country)
            }

            val variant = locale.variant
            if (variant.isEmpty()) {
                remove(APPLICATION_LOCALE_VARIANT_KEY)
            } else {
                putString(APPLICATION_LOCALE_VARIANT_KEY, variant)
            }

            val script = locale.script
            if (script.isEmpty()) {
                remove(APPLICATION_LOCALE_SCRIPT_KEY)
            } else {
                putString(APPLICATION_LOCALE_SCRIPT_KEY, script)
            }
        }
    }

    /**
     * Get String from a locale
     *
     * @param context    the context
     * @param locale     the locale
     * @param resourceId the string resource id
     * @return the localized string
     */
    private fun getString(context: Context, locale: Locale, resourceId: Int): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return try {
            context.createConfigurationContext(config).getText(resourceId).toString()
        } catch (e: Exception) {
            Timber.e(e, "## getString() failed")
            // use the default one
            context.getString(resourceId)
        }
    }

    /**
     * Init the supported application locales list
     */
    private fun initApplicationLocales() {
        val knownLocalesSet = HashSet<Triple<String, String, String>>()

        try {
            val availableLocales = Locale.getAvailableLocales()

            for (locale in availableLocales) {
                knownLocalesSet.add(
                        Triple(
                                getString(context, locale, R.string.resources_language),
                                getString(context, locale, R.string.resources_country_code),
                                getString(context, locale, R.string.resources_script)
                        )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "## getApplicationLocales() : failed")
            knownLocalesSet.add(
                    Triple(
                            context.getString(R.string.resources_language),
                            context.getString(R.string.resources_country_code),
                            context.getString(R.string.resources_script)
                    )
            )
        }

        val list = knownLocalesSet.mapNotNull { (language, country, script) ->
            try {
                Locale.Builder()
                        .setLanguage(language)
                        .setRegion(country)
                        .setScript(script)
                        .build()
            } catch (exception: IllformedLocaleException) {
                if (BuildConfig.DEBUG) {
                    throw exception
                }
                // Ignore this locale in production
                null
            }
        }
                // sort by human display names
                .sortedBy { localeToLocalisedString(it).lowercase(it) }

        supportedLocales.clear()
        supportedLocales.addAll(list)
    }

    /**
     * Convert a locale to a string
     *
     * @param locale the locale to convert
     * @return the string
     */
    fun localeToLocalisedString(locale: Locale): String {
        return buildString {
            append(locale.getDisplayLanguage(locale))

            if (locale.script != ISO_15924_LATN && locale.getDisplayScript(locale).isNotEmpty()) {
                append(" - ")
                append(locale.getDisplayScript(locale))
            }

            if (locale.getDisplayCountry(locale).isNotEmpty()) {
                append(" (")
                append(locale.getDisplayCountry(locale))
                append(")")
            }
        }
    }

    /**
     * Information about the locale in the current locale
     *
     * @param locale the locale to get info from
     * @return the string
     */
    fun localeToLocalisedStringInfo(locale: Locale): String {
        return buildString {
            append("[")
            append(locale.displayLanguage)
            if (locale.script != ISO_15924_LATN) {
                append(" - ")
                append(locale.displayScript)
            }
            if (locale.displayCountry.isNotEmpty()) {
                append(" (")
                append(locale.displayCountry)
                append(")")
            }
            append("]")
        }
    }

    suspend fun getSupportedLocales(): List<Locale> {
        if (supportedLocales.isEmpty()) {
            // init the known locales in background
            withContext(Dispatchers.IO) {
                initApplicationLocales()
            }
        }
        return supportedLocales
    }
}
