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
    POM_ARTIFACT_ID = "mcumgr-core"
    POM_NAME = "Mcu Manager Core"
    POM_DESCRIPTION = "A mobile management library for devices running nRF Connect SDK, Zephyr or Apache Mynewt (DFU, file system, logs, stats, config, etc.)."
    POM_URL = "https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/"
    POM_SCM_URL = "https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/"
    POM_SCM_CONNECTION = "scm:git@github.com:nordicsemi/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_DEV_CONNECTION = "scm:git@github.com:nordicsemi/Android-nRF-Connect-Device-Manager.git"
}

android {
    namespace = "io.runtime.mcumgr"

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
    // Annotations
    implementation(libs.annotations)

    // Logging using SLF4J. Specify binding in the application.
    implementation(libs.slf4j)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Import CBOR parser - version 2.14+ requires Android 8
    // See: https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager/issues/135
    //noinspection NewerVersionAvailable
    implementation(libs.fasterxml.cbor)
    //noinspection NewerVersionAvailable
    implementation(libs.fasterxml.core)
    //noinspection NewerVersionAvailable
    implementation(libs.fasterxml.databind)

    // Test
    testImplementation(libs.kotlin.test)
}