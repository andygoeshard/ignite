import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidRoom)
    alias(libs.plugins.ksp)
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvm()

    android {
        namespace = "com.andyl.ignite.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.zxing.core)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.lifecycle.viewmodel.navigation3)

            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.coroutinesCore)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.server.call.logging)

            implementation(libs.navigation3.ui)

            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs)
            implementation(libs.filekit.dialogs.compose)

            implementation(libs.room.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.koin.test)
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.netty)
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.server.cio)
            implementation(libs.zxing.core)
            implementation(libs.zxing.android.embedded)
            implementation(libs.androidx.activity.compose)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    add("kspAndroid", libs.room.compiler)
}
