import org.gradle.api.tasks.JavaExec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// macOS muestra "java"/"main.kt" cuando corre sin empaquetar; esto lo arregla
// tambien para hotRun y variantes.
tasks.configureEach {
    if (name.startsWith("run")) {
        (this as? JavaExec)?.jvmArgs(
            "-Dapple.awt.application.name=Ignite",
            "-Xdock:name=Ignite",
        )
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.filekit.core)
    implementation(libs.koin.core)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.andyl.ignite.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ignite"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "com.andyl.ignite"
                iconFile.set(project.file("../art/icons/macos/Ignite.icns"))
            }
            windows {
                iconFile.set(project.file("../art/icons/windows/Ignite.ico"))
            }
            linux {
                iconFile.set(project.file("../art/icons/png/ignite-icon-512.png"))
            }
        }
    }
}