plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:capability"))
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.coroutines.core)
}
