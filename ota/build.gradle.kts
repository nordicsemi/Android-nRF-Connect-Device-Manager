/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

plugins {
    alias(libs.plugins.nordic.android.library)
    alias(libs.plugins.nordic.kotlin)
    alias(libs.plugins.nordic.publish.android)
}

group = "no.nordicsemi.android"

nordicPublishing {
    POM_ARTIFACT_ID = "ota"
    POM_NAME = "Android Device Management OTA Library"

    POM_DESCRIPTION = "A library for checking and downloading OTA releases from in nRF Cloud."
    POM_URL = "https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_URL = "https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_CONNECTION = "scm:git@github.com:NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
    POM_SCM_DEV_CONNECTION = "scm:git@github.com:NordicSemiconductor/Android-nRF-Connect-Device-Manager.git"
}

android {
    namespace = "no.nordicsemi.android.ota"
}

dependencies {
    implementation(project(":mcumgr-core"))

    // The MemfaultCloud library provides convenience APIs for mobile applications that
    // interact with web services.
    // https://github.com/memfault/memfault-cloud-android
    implementation(libs.memfault.cloud)

    implementation(libs.kotlinx.coroutines.android)

    //noinspection NewerVersionAvailable
    implementation(libs.fasterxml.databind)

    // Use SLF4J for logging.
    implementation(libs.slf4j)
}
