package io.github.nakanomk.stacklife.acceptance

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.runner.AndroidJUnitRunner
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.LanguageChoice
import io.github.nakanomk.stacklife.i18n.LocalizedActivity

class StackLifeTestRunner : AndroidJUnitRunner() {
    private var previous: LanguageChoice? = null

    override fun callApplicationOnCreate(app: Application) {
        AppLocales.initialize(app)
        previous = AppLocales.choice(app)
        AppLocales.store(app, LanguageChoice.CHINESE)
        super.callApplicationOnCreate(app)
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        super.newActivity(cl, if (className == ComponentActivity::class.java.name) LocalizedActivity::class.java.name else className, intent)

    override fun finish(resultCode: Int, results: Bundle?) {
        previous?.let { AppLocales.store(targetContext, it) }
        super.finish(resultCode, results)
    }
}
