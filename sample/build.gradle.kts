import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Copyright (c) Nordic Semiconductor ASA, 2018-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    alias(libs.plugins.nordic.android.application)
    alias(libs.plugins.nordic.kotlin)
    alias(libs.plugins.ksp)
}

android {
    namespace = "no.nordicsemi.android.mcumgr.sample"

    defaultConfig {
        applicationId = "no.nordicsemi.android.nrfconnectdevicemanager"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    // Mcu Mgr
    implementation(project(":mcumgr-ble"))

    // Use nRF Cloud Observability feature with native BLE client.
    implementation(project(":observability"))
    implementation(nordic.blek.client.android)

    // Use nRF Cloud OTA feature to check for updates and download images for devices supporting it.
    implementation(project(":ota"))

    // AndroidX libraries
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)

    // Dagger
    implementation(libs.dagger)
    implementation(libs.dagger.android)
    implementation(libs.dagger.android.support)
    ksp(libs.dagger.compiler)
    ksp(libs.dagger.android.processor)

    // Brings the new BluetoothLeScanner API to older platforms
    implementation(nordic.compat.scanner)

    // Timber & SLF4J
    implementation(libs.slf4j.timber)
    implementation(nordic.log.timber)
    implementation(nordic.kotlin.log.timber)

    // GSON
    implementation(libs.gson)

    // Test
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit4)
}
