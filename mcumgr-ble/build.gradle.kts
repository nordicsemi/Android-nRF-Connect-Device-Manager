/*
 * Copyright (c) Runtime Inc., 2017-2018
 * Copyright (c) Intellinium SAS, 2014-2021
 * Copyright (c) Nordic Semiconductor ASA, 2021-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.nordic.android.library)
    alias(libs.plugins.nordic.kotlin)
    alias(libs.plugins.nordic.publish.android)
}

group = "no.nordicsemi.android"

nordicPublishing {
    POM_ARTIFACT_ID = "mcumgr-ble"
    POM_NAME = "Mcu Manager BLE Transport"

    POM_DESCRIPTION = "A Bluetooth LE transport implementation for the Mcu Manager library."
    POM_URL = "https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_URL = "https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_CONNECTION = "scm:git@github.com:NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_DEV_CONNECTION = "scm:git@github.com:NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
}

android {
    namespace = "io.runtime.mcumgr.ble"

    compileOptions {
        // for now and foreseeable future we intentionally set the build system to emit bytecode that is compatible with
        // java11 so as to ensure that we don't break the "classic xamarin (mono)" toolchain for C# android-java-bindings
        // which employs an outdated version of r8 that can only handle java11 bytecode
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            // for now and foreseeable future we intentionally set the build system to emit bytecode that is compatible with
            // java11 so as to ensure that we don't break the "classic xamarin (mono)" toolchain for C# android-java-bindings
            // which employs an outdated version of r8 that can only handle java11 bytecode
            jvmTarget = JvmTarget.JVM_11
        }
    }
}

dependencies {
    // Import mcumgr-core
    api(project(":mcumgr-core"))

    // Import the BLE Library
    api(nordic.ble)

    // Logging using SLF4J. Specify binding in the application.
    implementation(libs.slf4j)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Test
    testImplementation(libs.kotlin.test)
}
