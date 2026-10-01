plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(project(":core:registry"))
    api(project(":core:importer"))
    api(project(":core:diagnostics"))
    testImplementation(libs.junit)
}
