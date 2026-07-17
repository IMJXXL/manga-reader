plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mangareader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mangareader"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "29.0.14206865"

    buildTypes {
        debug {
            // 使用默认 debug 签名
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 签名配置：通过环境变量或 local.properties 提供
            signingConfigs.create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../manga-reader.jks")
                storePassword = System.getenv("STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
            signingConfig = signingConfigs.getByName("release")
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
        aidl = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf("**/libzip.so", "**/libpdfium.so")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // ViewPager2 for horizontal page flip
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // RecyclerView (transitive but explicit for clarity)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // SubsamplingScaleImageView for efficient large image rendering
    implementation("com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Apache Commons Compress - ZIP/7Z handling
    implementation("org.apache.commons:commons-compress:1.26.1")

    // JunRAR - RAR/CBR support
    implementation("com.github.junrar:junrar:7.5.5")

    // Coil - image loading with memory+disk cache
    implementation("io.coil-kt:coil-compose:2.7.0")

    // zhanghai libarchive-android (open source, Apache 2.0)
    implementation(files("libs/libarchive-android.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
