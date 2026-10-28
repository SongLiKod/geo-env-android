import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.geoenv.browser"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.geoenv.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 文档：arm64-v8a 优先；x86_64 为"可选"，本次交付仅打包 arm64-v8a
            abiFilters += listOf("arm64-v8a")
        }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("scripts/geoenv-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = keystoreProps.getProperty("storePassword", "geoenv2026")
                keyAlias = keystoreProps.getProperty("keyAlias", "geoenv")
                keyPassword = keystoreProps.getProperty("keyPassword", "geoenv2026")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            // Hilt 2.58（兼容 AGP 8.x 的最高线）读取类元数据上限 2.3.0，
            // 而 Kotlin 2.4 默认输出 2.4.0；统一降级元数据版本供 Hilt 注解处理器读取。
            freeCompilerArgs.add("-Xmetadata-version=2.3.0")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            // GeckoView 体积较大，使用压缩存储
            useLegacyPackaging = true
        }
    }
    androidResources {
        noCompress += listOf("omni.ja", "mozbuild", "gz")
    }
    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
}

// GeckoView POM 传递依赖 androidx.core 1.19 / lifecycle 2.11 要求 AGP 9.1+；
// 本项目锁定 AGP 8.13.1，且 GeckoView 实际只用到 core 1.16 / lifecycle 2.9 的基础 API，
// 统一降到缓存已有且与 AGP 8.x 兼容的版本。
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.core" &&
            (requested.name == "core" || requested.name == "core-ktx")
        ) {
            useVersion("1.16.0")
        }
        if (requested.group == "androidx.lifecycle") {
            useVersion("2.9.4")
        }
        // Hilt 2.58 注解处理器以 readStrict 读取类元数据，上限 2.3.0；
        // kotlin-stdlib 2.4.x 自身的 kotlin/Metadata 注解类也是 2.4.0 元数据，
        // 会被 Hilt 读取导致崩溃，故把 stdlib 统一到 2.3.21（元数据 2.3.0）。
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-stdlib") {
            useVersion("2.3.21")
        }
    }
}

dependencies {
    // ---- GeckoView（本地 Maven 仓库锁定版本） ----
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:158.0.20261021001945")

    // ---- Kotlin / 协程 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")

    // ---- AndroidX Core ----
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.window:window:1.3.0")

    // ---- Compose ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Navigation / Lifecycle ----
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    // ---- Hilt ----
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-android-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ---- Room + SQLCipher ----
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.19.1")

    // ---- Network ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ---- JSON / 日志 ----
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ---- DataStore（主题等轻量配置） ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- 测试 ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
