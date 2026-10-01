plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.yagay.yauto.platform.xposed"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:capability"))
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.coroutines.android)
    compileOnly("io.github.libxposed:api:102.0.0")
}
