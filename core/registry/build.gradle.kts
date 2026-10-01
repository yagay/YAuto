plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model")); implementation(project(":core:logging")); implementation(project(":core:capability")); implementation(libs.kotlinx.coroutines.core)
}
