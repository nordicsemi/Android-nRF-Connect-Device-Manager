/*
 * Copyright (c) 2023, Nordic Semiconductor
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

@Suppress("UnstableApiUsage")
pluginManagement {
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        gradlePluginPortal {
            content {
                includeGroupAndSubgroups("com.gradle")
                includeGroupAndSubgroups("no.nordicsemi")
                includeGroupAndSubgroups("org.jetbrains")
            }
        }
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        // Use Nordic Gradle Version Catalog with common external libraries versions.
        // Link: https://github.com/NordicSemiconductor/Android-Gradle-Plugins
        create("libs") {
            // Note: Freeze at 2.11, 2.12+ increased minSdk to 23:
            // https://github.com/NordicSemiconductor/Android-Gradle-Plugins/releases/tag/2.12-2
            from("no.nordicsemi.android.gradle:version-catalog:2.11.5")
        }
        // Fixed versions for Nordic libraries.
        create("nordic") {
            from(files("gradle/nordic.versions.toml"))
        }
        // Nordic Version Catalog is released after library releases, so cannot be used internally in libs.
        // Link: https://github.com/NordicSemiconductor/Android-Version-Catalog
        // create("nordic") {
        //    from("no.nordicsemi.android:version-catalog:2025.10.00")
        // }
    }
}

rootProject.name = "nRF Connect Device Manager"

include(":mcumgr-core")
include(":mcumgr-ble")
include(":observability")
include(":ota")
include(":sample")
