import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sonora"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sonora"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "1.4.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // innertubex (движок InnerTubeX из Metrolist) собран под Java 21.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // Python-рантайм yt-dlp должен распаковываться из APK: .so нельзя
        // грузить прямо из архива.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3: воспроизведение + медиасессия
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Сеть / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // InnerTubeX — движок потоков из Metrolist: cipher, SABR, PoToken-контракты.
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.0")
    // yt-dlp: резервный путь для видео, где InnerTube не отдал аудио
    // (возрастные/региональные ограничения, SABR-only).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    // Картинки
    implementation("io.coil-kt:coil-compose:2.6.0")

    // WARP: официальная библиотека WireGuard (userspace wireguard-go).
    // Используется только для per-app туннеля Sonora, см. app.sonora.net.warp.
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // Material Components нужен только ради XML-темы окна (Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // Шифрованное хранилище OAuth-токенов
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
