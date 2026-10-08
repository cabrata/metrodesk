import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.protobuf)
}

val appVersion = providers.gradleProperty("appVersion").get()
val sharedSources = layout.buildDirectory.dir("generated/utaloom")
// ponytail: AGP 9 source directory sets have no excludes. Generate a filtered source view, never vendor a second app.
val prepareSharedSources = tasks.register<Sync>("prepareSharedSources") {
    from("../app/src/main/kotlin") {
        exclude(
            "com/utaloom/Main.kt", "com/utaloom/Probe.kt", "com/utaloom/data/Paths.kt",
            "com/utaloom/platform/Mpris.kt", "com/utaloom/platform/NativeLibs.kt",
            "com/utaloom/platform/Updates.kt", "com/utaloom/platform/DesktopAudioEngine.kt",
            "com/utaloom/platform/ArtworkBitmap.kt", "com/utaloom/platform/PlaylistFiles.kt",
        )
    }
    from("../innertube/src/main/kotlin", "../shared/src/commonMain/kotlin")
    into(sharedSources)
}

android {
    namespace = "com.utaloom.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.utaloom.android"
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        val (major, minor, patch) = appVersion.split('.').map(String::toInt)
        versionCode = major * 1_000_000 + minor * 1_000 + patch
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    val keyFile = System.getenv("UTALOOM_KEYSTORE")
    if (!keyFile.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keyFile)
            storePassword = System.getenv("UTALOOM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("UTALOOM_KEY_ALIAS")
            keyPassword = System.getenv("UTALOOM_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    sourceSets["main"].proto { srcDir("../app/src/main/proto") }
    sourceSets["main"].kotlin.directories.add(sharedSources.get().asFile.path)
    packaging.resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(prepareSharedSources) }

dependencies {
    implementation(libs.activity.compose)
    implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.get()}")
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.get()}")
    implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.get()}")
    implementation(libs.material3)
    implementation(libs.material.icons)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.innertubex.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.protobuf.kotlin)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach { builtins { create("java") } }
    }
}
