plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cn.alittlecookie.lutista"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.alittlecookie.lutista"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments += "-DSHADER_ASSET_OUT=${layout.buildDirectory.get().asFile.absolutePath}/generated/shaderAssets"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 纯 JVM 单测里 android.util.Log 是空实现，默认会抛
            // "Method d in android.util.Log not mocked"。LayoutEngine 等类直接调用了 Log.d，
            // 因此让未 mock 的 Android API 返回默认值。
            isReturnDefaultValues = true
        }
    }

    sourceSets.getByName("main") {
        // SPIR-V 由 CMake 生成到 build 目录，这里是唯一入口，APK 内仍是 assets/shaders/*.spv。
        // 注意：AGP 9 的 SourceSet API 不接受 Provider（只接受 File/String），
        // 且任务顺序由文件末尾的 merge*Assets dependsOn buildCMake* 显式保证。
        assets.srcDir(File(layout.buildDirectory.get().asFile, "generated/shaderAssets"))
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.navigation3.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)

    // ExifInterface
    implementation(libs.exifinterface)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Coil
    implementation(libs.coil.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // DocumentFile (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Timber
    implementation(libs.timber)

    // MIUIX
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)

    // Reorderable
    implementation(libs.reorderable)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Shader 由 CMake/glslc 在构建期生成到 build/generated/shaderAssets（见 sourceSets.main.assets），
// 而 AGP 的 SourceSet API 不接受 Provider，静态 srcDir 不产生隐式任务依赖——
// 因此下面这段 dependsOn 是 assets 合并与 native 构建之间【唯一】的顺序保证，不要删除或收紧：
// 删掉它会导致 clean 后首次 assemble 的 APK 缺失 shaders/unified.comp.spv，
// 运行时 VulkanGpuEngine 加载失败、每台设备都弹「设备不支持」。
tasks.configureEach {
    if (name.matches(Regex("merge.*Assets"))) {
        dependsOn(tasks.matching { it.name.matches(Regex("buildCMake.*")) })
    }
}
