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
        versionCode = 31
        versionName = "1.9.0"
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
        // innertubex (Р Т‘Р Р†Р С‘Р В¶Р С•Р С” InnerTubeX Р С‘Р В· Metrolist) РЎРѓР С•Р В±РЎР‚Р В°Р Р… Р С—Р С•Р Т‘ Java 21.
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // Python-РЎР‚Р В°Р Р…РЎвЂљР В°Р в„–Р С yt-dlp Р Т‘Р С•Р В»Р В¶Р ВµР Р… РЎР‚Р В°РЎРѓР С—Р В°Р С”Р С•Р Р†РЎвЂ№Р Р†Р В°РЎвЂљРЎРЉРЎРѓРЎРЏ Р С‘Р В· APK: .so Р Р…Р ВµР В»РЎРЉР В·РЎРЏ
        // Р С–РЎР‚РЎС“Р В·Р С‘РЎвЂљРЎРЉ Р С—РЎР‚РЎРЏР СР С• Р С‘Р В· Р В°РЎР‚РЎвЂ¦Р С‘Р Р†Р В°.
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

    // Media3: Р Р†Р С•РЎРѓР С—РЎР‚Р С•Р С‘Р В·Р Р†Р ВµР Т‘Р ВµР Р…Р С‘Р Вµ + Р СР ВµР Т‘Р С‘Р В°РЎРѓР ВµРЎРѓРЎРѓР С‘РЎРЏ
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Р РЋР ВµРЎвЂљРЎРЉ / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // InnerTubeX РІР‚вЂќ Р Т‘Р Р†Р С‘Р В¶Р С•Р С” Р С—Р С•РЎвЂљР С•Р С”Р С•Р Р† Р С‘Р В· Metrolist: cipher, SABR, PoToken-Р С”Р С•Р Р…РЎвЂљРЎР‚Р В°Р С”РЎвЂљРЎвЂ№.
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.0")
    // yt-dlp: Р С‘РЎРѓРЎвЂљР С•РЎвЂЎР Р…Р С‘Р С” Р С—Р С•РЎвЂљР С•Р С”Р В° Р С‘ Р С—Р С•Р С‘РЎРѓР С” Р Р† Р С•Р В±РЎвЂ¦Р С•Р Т‘ Р С•Р С–РЎР‚Р В°Р Р…Р С‘РЎвЂЎР ВµР Р…Р С‘Р в„– InnerTube
    // (Р Р†Р С•Р В·РЎР‚Р В°РЎРѓРЎвЂљР Р…РЎвЂ№Р Вµ/РЎР‚Р ВµР С–Р С‘Р С•Р Р…Р В°Р В»РЎРЉР Р…РЎвЂ№Р Вµ Р Р†Р С‘Р Т‘Р ВµР С•, SABR-only).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    // ffmpeg Р Р…РЎС“Р В¶Р ВµР Р… Р Т‘Р В»РЎРЏ Р Р…Р В°РЎР‚Р ВµР В·Р С”Р С‘ 30-РЎРѓР ВµР С”РЎС“Р Р…Р Т‘Р Р…Р С•Р С–Р С• РЎвЂћРЎР‚Р В°Р С–Р СР ВµР Р…РЎвЂљР В° Р С—Р С•Р Т‘ ReccoBeats.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // ffmpeg: Р Р…Р В°РЎР‚Р ВµР В·Р С”Р В° 30-РЎРѓР ВµР С”РЎС“Р Р…Р Т‘Р Р…Р С•Р С–Р С• РЎвЂћРЎР‚Р В°Р С–Р СР ВµР Р…РЎвЂљР В° Р С‘ Р С”Р С•Р Р…Р Р†Р ВµРЎР‚РЎвЂљР В°РЎвЂ Р С‘РЎРЏ Р Р† MP3 Р Т‘Р В»РЎРЏ ReccoBeats.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    // Р С™Р В°РЎР‚РЎвЂљР С‘Р Р…Р С”Р С‘
    implementation("io.coil-kt:coil-compose:2.6.0")

    // WARP: Р С•РЎвЂћР С‘РЎвЂ Р С‘Р В°Р В»РЎРЉР Р…Р В°РЎРЏ Р В±Р С‘Р В±Р В»Р С‘Р С•РЎвЂљР ВµР С”Р В° WireGuard (userspace wireguard-go).
    // Р ВРЎРѓР С—Р С•Р В»РЎРЉР В·РЎС“Р ВµРЎвЂљРЎРѓРЎРЏ РЎвЂљР С•Р В»РЎРЉР С”Р С• Р Т‘Р В»РЎРЏ per-app РЎвЂљРЎС“Р Р…Р Р…Р ВµР В»РЎРЏ Sonora, РЎРѓР С. app.sonora.net.warp.
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // Material Components Р Р…РЎС“Р В¶Р ВµР Р… РЎвЂљР С•Р В»РЎРЉР С”Р С• РЎР‚Р В°Р Т‘Р С‘ XML-РЎвЂљР ВµР СРЎвЂ№ Р С•Р С”Р Р…Р В° (Theme.Material3.*)
    implementation("com.google.android.material:material:1.12.0")

    // Р РЃР С‘РЎвЂћРЎР‚Р С•Р Р†Р В°Р Р…Р Р…Р С•Р Вµ РЎвЂ¦РЎР‚Р В°Р Р…Р С‘Р В»Р С‘РЎвЂ°Р Вµ OAuth-РЎвЂљР С•Р С”Р ВµР Р…Р С•Р Р†
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
