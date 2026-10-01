plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yagay.yauto"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yagay.yauto"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:capability"))
    implementation(project(":core:registry"))
    implementation(project(":core:engine"))
    implementation(project(":core:runtime"))
    implementation(project(":core:storage"))
    implementation(project(":core:importer"))
    implementation(project(":feature:standard"))
    implementation(project(":importer:macrodroid"))
    implementation(project(":importer:shortx"))
    implementation(project(":importer:tasker"))
    implementation(project(":platform:android"))
    implementation(project(":platform:accessibility"))
    implementation(project(":platform:root"))
    implementation(project(":platform:shizuku"))
    implementation(project(":platform:xposed"))
    implementation(project(":ui:design"))
    implementation(project(":ui:home"))
    implementation(project(":ui:editor"))
    implementation(project(":ui:diagnostics"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
}
