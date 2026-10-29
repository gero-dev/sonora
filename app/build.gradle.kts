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
        versionCode = 30
        versionName = "1.8.0"
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
        // innertubex (РґРІРёР¶РѕРє InnerTubeX РёР· Metrolist) СЃРѕР±СЂР°РЅ РїРѕРґ Java 21.
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
        // Python-СЂР°РЅС‚Р°Р№Рј yt-dlp РґРѕР»Р¶РµРЅ СЂР°СЃРїР°РєРѕРІС‹РІР°С‚СЊСЃСЏ РёР· APK: .so РЅРµР»СЊР·СЏ
        // РіСЂСѓР·РёС‚СЊ РїСЂСЏРјРѕ РёР· Р°СЂС…РёРІР°.
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

    // Media3: РІРѕСЃРїСЂРѕРёР·РІРµРґРµРЅРёРµ + РјРµРґРёР°СЃРµСЃСЃРёСЏ
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // РЎРµС‚СЊ / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // InnerTubeX вЂ” РґРІРёР¶РѕРє РїРѕС‚РѕРєРѕРІ РёР· Metrolist: cipher, SABR, PoToken-РєРѕРЅС‚СЂР°РєС‚С‹.
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.0")
    // yt-dlp: РёСЃС‚РѕС‡РЅРёРє РїРѕС‚РѕРєР° Рё РїРѕРёСЃРє РІ РѕР±С…РѕРґ РѕРіСЂР°РЅРёС‡РµРЅРёР№ InnerTube
    // (РІРѕР·СЂР°СЃС‚РЅС‹Рµ/СЂРµРіРёРѕРЅР°Р»СЊРЅС‹Рµ РІРёРґРµРѕ, SABR-only).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    // ffmpeg РЅСѓР¶РµРЅ РґР»СЏ РЅР°СЂРµР·РєРё 30-СЃРµРєСѓРЅРґРЅРѕРіРѕ С„СЂР°РіРјРµРЅС‚Р° РїРѕРґ ReccoBeats.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // ffmpeg: РЅР°СЂРµР·РєР° 30-СЃРµРєСѓРЅРґРЅРѕРіРѕ С„СЂР°РіРјРµРЅС‚Р° Рё РєРѕРЅРІРµСЂС‚Р°С†РёСЏ РІ MP3 РґР»СЏ ReccoBeats.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    // РљР°СЂС‚РёРЅРєРё
    implementation("io.coil-kt:coil-compose:2.6.0")

    // WARP: РѕС„РёС†РёР°Р»СЊРЅР°СЏ Р±РёР±Р»РёРѕС‚РµРєР° WireGuard (userspace wireguard-go).
    // РСЃРїРѕР»СЊР·СѓРµС‚СЃСЏ С‚РѕР»СЊРєРѕ РґР»СЏ per-app С‚СѓРЅРЅРµР»СЏ Sonora, СЃРј. app.sonora.net.warp.
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // Material Components РЅСѓР¶РµРЅ С‚РѕР»СЊРєРѕ СЂР°РґРё XML-С‚РµРјС‹ РѕРєРЅР° (Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // РЁРёС„СЂРѕРІР°РЅРЅРѕРµ С…СЂР°РЅРёР»РёС‰Рµ OAuth-С‚РѕРєРµРЅРѕРІ
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
