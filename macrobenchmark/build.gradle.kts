plugins { alias(libs.plugins.android.test) }

android {
    namespace = "io.github.nakanomk.stacklife.macrobenchmark"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
androidComponents { beforeVariants { it.enable = it.buildType == "benchmark" } }

dependencies {
    implementation(libs.macrobenchmark)
    implementation(libs.uiautomator)
    implementation(libs.test.junit)
    implementation(libs.test.runner)
}
