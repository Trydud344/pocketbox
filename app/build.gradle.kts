plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.pocketbox.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pocketbox.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Copy the NDK-built PRoot binaries (libproot.so, libproot-loader.so) into
    // jniLibs so they are extracted to nativeLibraryDir and can be exec'd
    // (brief section 5.1 / 7.1). Build them first:
    //   runtime/native/build-proot-android.sh <abi>
    val prootOut = rootProject.projectDir.resolve("runtime/build/android")
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/pocketboxJniLibs"))
        }
    }
    val copyProot = tasks.register<Sync>("copyProotBinaries") {
        group = "build"
        description = "Copies the built PRoot binaries into jniLibs"
        for (abi in listOf("arm64-v8a", "x86_64")) {
            from(File(prootOut, "$abi/libproot.so")) { into(abi) }
            from(File(prootOut, "$abi/libproot-loader.so")) { into(abi) }
        }
        into(layout.buildDirectory.dir("generated/pocketboxJniLibs"))
        doFirst {
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val f = File(prootOut, "$abi/libproot.so")
                require(f.exists()) {
                    "Missing $f — run runtime/native/build-proot-android.sh $abi first"
                }
            }
        }
    }
    tasks.matching { it.name.matches(Regex("merge.*JniLibFolders|package.*")) }
        .configureEach { dependsOn(copyProot) }

    packaging {
        jniLibs {
            // Extract .so "executables" (libproot.so, libproot-loader.so) to disk.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":runtime"))

    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR generation only (no camera) for pairing a different device.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
