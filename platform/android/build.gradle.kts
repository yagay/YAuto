plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yagay.yauto.platform.android"
    compileSdk = 37

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:capability"))
    implementation(project(":core:registry"))
    implementation(project(":core:storage"))
    implementation(project(":core:importer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.rhino)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.beanshell)
    implementation(libs.mvel)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    testImplementation(project(":core:runtime"))
}
