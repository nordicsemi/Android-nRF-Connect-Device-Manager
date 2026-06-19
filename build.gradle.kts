/*
 * Copyright (c) Runtime Inc., 2017-2018
 * Copyright (c) Intellinium SAS, 2014-2021
 * Copyright (c) Nordic Semiconductor ASA, 2021-present
 *
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    alias(libs.plugins.ksp) apply false

    // This applies Nordic look & feel to generated Dokka documentation.
    // https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/plugins/src/main/kotlin/NordicDokkaPlugin.kt
    alias(libs.plugins.nordic.dokka) apply true

    // Nordic Gradle Plugins
    // https://github.com/nordicsemi/Nordic-Gradle-Plugins
    alias(libs.plugins.nordic.android.application) apply false
    alias(libs.plugins.nordic.android.library) apply false
    alias(libs.plugins.nordic.feature.hilt) apply false
    alias(libs.plugins.nordic.kotlin) apply false
    alias(libs.plugins.nordic.publish.android) apply false
}

// Configure main Dokka page
dokka {
    pluginsConfiguration.html {
        homepageLink.set("https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager")
    }
}
