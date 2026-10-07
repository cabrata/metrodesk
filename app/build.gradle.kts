import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":innertube"))
    implementation(compose.desktop.currentOs)
    implementation(libs.material3)
    implementation(libs.material.icons)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.vlcj)
    implementation(libs.protobuf.kotlin)
    implementation(libs.dbus.core)
    implementation(libs.dbus.transport)
    testImplementation(libs.junit)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("kotlin") }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.metrodesk.MainKt"
        jvmArgs += listOf("-Xmx768m")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "metrodesk"
            packageVersion = "1.0.0"
            description = "Desktop YouTube Music client based on Metrolist"
            vendor = "Metrodesk"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.net.http", "java.sql", "jdk.unsupported", "java.naming", "jdk.security.auth")
            linux {
                iconFile.set(project.file("icon.png"))
                debMaintainer = "metrodesk@caliph.dev"
                appCategory = "Audio"
                debPackageVersion = "1.0.0"
            }
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable id so MSI upgrades replace old installs instead of duplicating them.
                upgradeUuid = "5b8e1f0c-6f3a-4d7e-9c2b-3a4d5e6f7a8b"
            }
        }
    }
}

tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.metrodesk.ProbeKt")
    args = (project.findProperty("videoId") as String?)?.let { listOf(it) } ?: emptyList()
}

tasks.register<JavaExec>("ltProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.metrodesk.LtProbeKt")
}

tasks.register<JavaExec>("playerSmoke") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.metrodesk.PlayerSmokeKt")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("smoke-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("smoke-data").get().asFile.absolutePath)
}

tasks.register<JavaExec>("networkSmoke") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.metrodesk.PlayerSmokeKt")
    args("network")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("network-smoke-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("network-smoke-data").get().asFile.absolutePath)
}
