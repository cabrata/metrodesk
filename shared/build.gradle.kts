plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
