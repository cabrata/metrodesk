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
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "metrodesk"
            packageVersion = "1.0.0"
            description = "Desktop YouTube Music client based on Metrolist"
            vendor = "Metrodesk"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.net.http", "java.sql", "jdk.unsupported", "java.naming")
            linux {
                iconFile.set(project.file("icon.png"))
                debMaintainer = "metrodesk@caliph.dev"
                appCategory = "Audio"
                debPackageVersion = "1.0.0"
            }
            windows { iconFile.set(project.file("icon.ico")); menu = true; shortcut = true }
            macOS { iconFile.set(project.file("icon.icns")) }
        }
    }
}

tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.metrodesk.ProbeKt")
    args = (project.findProperty("videoId") as String?)?.let { listOf(it) } ?: emptyList()
}
