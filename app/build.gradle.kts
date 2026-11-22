import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val kitAar = providers.gradleProperty("stacklifeKitAar").orNull?.let { path ->
    val aar = file(path)
    require(aar.isFile && MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).toHexString() ==
        "7c0937ecde545a262619e5fa19d56a21b0ad3fd8013cbc4fe058c497808702bd") {
        "kitLab requires the pinned SharedView Kit 1.0.0.5 AAR; distribution is not authorized by the demo's license."
    }
    aar
}

android {
    namespace = "io.github.nakanomk.stacklife"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "io.github.nakanomk.stacklife"
        minSdk = 26
        targetSdk = 37
        versionCode = 26
        versionName = "0.10.11"
        testInstrumentationRunner = "io.github.nakanomk.stacklife.acceptance.StackLifeTestRunner"
    }
    buildTypes {
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        if (kitAar != null) create("kitLab") {
            initWith(getByName("release"))
            applicationIdSuffix = ".kitlab"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testOptions.unitTests.all {
        // Existing localized-message fixtures use Chinese; resolver tests exercise both languages explicitly.
        it.systemProperty("user.language", "zh")
        it.systemProperty("user.country", "CN")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("kitLab")) {
        it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation(libs.browser)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.nav.runtime)
    implementation(libs.nav.ui)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.reorderable)
    implementation(libs.kotlinflow)
    implementation(libs.jgrapht)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.materialkolor)
    implementation(libs.miuix.blur) {
        exclude(group = "org.jetbrains.compose.foundation", module = "foundation-android")
    }
    if (kitAar != null) add("kitLabImplementation", files(kitAar))
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    debugImplementation(libs.test.activity)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
}
