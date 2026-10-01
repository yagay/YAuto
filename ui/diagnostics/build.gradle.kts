plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "com.yagay.yauto.ui.diagnostics"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":ui:design"))
    implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.material3)
}
