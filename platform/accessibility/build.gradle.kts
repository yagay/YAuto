plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yagay.yauto.platform.accessibility"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.apache-extras.beanshell:bsh:2.0b6")
    implementation("org.mvel:mvel2:2.5.4.Final")
    testImplementation(libs.junit)
}
