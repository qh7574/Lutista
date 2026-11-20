plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ============================== Release 签名 & 版本号 ==============================
// 签名凭据全部来自环境变量（CI 由 GitHub Actions 从 Secrets 注入），
// 本地不设这些变量时【不创建】签名配置，assembleRelease 照旧产出 app-release-unsigned.apk。
// 这里刻意使用 providers.environmentVariable(...) 而不是 System.getenv(...)：
// 本项目开启了 configuration cache，Provider 读取会被正确登记为 CC 输入，
// 而 System.getenv 在配置期读取不会登记，会导致 CC 复用到错误的配置（污染/静默错误）。
val keystoreFilePath: String? = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystoreStorePassword: String? = providers.environmentVariable("KEYSTORE_STORE_PASSWORD").orNull
val keystoreKeyAlias: String? = providers.environmentVariable("KEYSTORE_KEY_ALIAS").orNull
val keystoreKeyPassword: String? = providers.environmentVariable("KEYSTORE_KEY_PASSWORD").orNull

// 四个变量齐备才认为「可以签名」；缺任意一个就整体跳过，不报错、不改变原有行为。
val hasReleaseSigning: Boolean = listOf(
    keystoreFilePath,
    keystoreStorePassword,
    keystoreKeyAlias,
    keystoreKeyPassword
).all { !it.isNullOrBlank() }

// 版本号可由 CI 覆盖：./gradlew assembleRelease -PversionCode=7 -PversionName=1.0.1
// 不传时保持默认 1 / "1.0"，与改动前完全一致。
val releaseVersionCode: Int = providers.gradleProperty("versionCode").map(String::toInt).orNull ?: 1
val releaseVersionName: String = providers.gradleProperty("versionName").orNull ?: "1.0"

android {
    namespace = "cn.alittlecookie.lutista"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.alittlecookie.lutista"
        minSdk = 29
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

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

    // 凭据来自环境变量，本地不设则跳过签名（不创建该 signingConfig，也不挂到 release 上）。
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(keystoreFilePath))
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // 提测变体：运行时行为与 release 完全一致（R8 + 资源压缩、isDebuggable=false），
        // 但用 debug 密钥签名，因此【不需要任何 secret】，fork / PR 也能构建出可安装包。
        //
        // 存在的理由：debug 变体 isDebuggable=true 且不跑 R8，Compose/MIUIX 的启动与滚动手感
        // 明显劣化，无法用来判断真实体验。CI 每次 push 出的包必须是这一档。
        // 注意：不要给这里加 applicationIdSuffix，否则装出来会是另一个包名，无法覆盖测试。
        create("perf") {
            initWith(getByName("release"))
            // initWith(release) 会连 signingConfig 一起复制过来，这里必须显式覆盖回 debug 密钥，
            // 否则 perf 构建也会去要 KEYSTORE_* 环境变量。
            signingConfig = signingConfigs.getByName("debug")
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
