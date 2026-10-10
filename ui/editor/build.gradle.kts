plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yagay.yauto.ui.editor"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)

    implementation(project(":core:model"))
    implementation(project(":core:capability"))
    implementation(project(":core:registry"))
    implementation(project(":ui:design"))
}
