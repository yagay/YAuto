plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:importer"))
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
