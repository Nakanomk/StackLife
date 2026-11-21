package io.github.nakanomk.stacklife.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Locale

internal enum class LanguageChoice(val tag: String) { SYSTEM(""), ENGLISH("en"), CHINESE("zh") }

internal object AppLocales {
    private const val PREFERENCES = "app_language"
    private const val TAG = "tag"

    fun choice(context: Context): LanguageChoice {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales[0]?.language
        } else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(TAG, null)
        return LanguageChoice.entries.firstOrNull { it.tag == tag } ?: LanguageChoice.SYSTEM
    }

    fun sync(context: Context): Locale {
        val requested = choice(context)
        val preferred = when {
            requested != LanguageChoice.SYSTEM -> LocaleList.forLanguageTags(requested.tag)
            Build.VERSION.SDK_INT >= 33 -> checkNotNull(context.getSystemService(LocaleManager::class.java)).systemLocales
            else -> Resources.getSystem().configuration.locales
        }
        return AppLanguage.update(List(preferred.size()) { preferred[it] })
    }

    fun set(activity: Activity, choice: LanguageChoice) {
        if (choice(activity) == choice) return
        store(activity, choice)
        if (Build.VERSION.SDK_INT < 33) activity.recreate()
    }

    fun store(context: Context, choice: LanguageChoice) {
        if (Build.VERSION.SDK_INT >= 33) {
            checkNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales = LocaleList.forLanguageTags(choice.tag)
        } else {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().apply {
                if (choice == LanguageChoice.SYSTEM) remove(TAG) else putString(TAG, choice.tag)
            }.apply()
        }
    }

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val legacy = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            legacy.getString(TAG, null)?.let { tag ->
                val manager = checkNotNull(context.getSystemService(LocaleManager::class.java))
                if (manager.applicationLocales.isEmpty && LanguageChoice.entries.any { it.tag == tag && tag.isNotEmpty() }) {
                    manager.applicationLocales = LocaleList.forLanguageTags(tag)
                }
                legacy.edit().remove(TAG).apply()
            }
        }
        sync(context)
    }
}

open class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val locale = AppLocales.sync(base)
        val configuration = if (locale == base.resources.configuration.locales[0]) base
            else base.createConfigurationContext(Configuration().apply { setLocale(locale) })
        super.attachBaseContext(configuration)
    }
}
