plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yagay.yauto.platform.shizuku"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:capability"))
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.coroutines.android)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
