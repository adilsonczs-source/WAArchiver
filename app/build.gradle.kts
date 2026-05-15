// ══════════════════════════════════════════════════════
// app/build.gradle.kts
// WhatsApp Archiver — Dependências e configuração
// ══════════════════════════════════════════════════════

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

android {
    namespace = "com.whatsapparchiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whatsapparchiver"
        minSdk = 26          // Android 8.0 (Notification Channels obrigatório)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ── AndroidX Core ──
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // ── Room (banco local) ──
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── ML Kit OCR (offline, sem internet) ──
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Para português: também disponível mas aumenta tamanho do APK
    // implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // ── Gráficos (estatísticas por dia/semana/mês) ──
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ── Google Drive (opcional, para backup) ──
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // ── Segurança (armazenar chave de API) ──
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── WorkManager (agendamento de tarefas em background) ──
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ── Testes ──
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Adicionar no settings.gradle para MPAndroidChart:
// repositories { maven { url 'https://jitpack.io' } }
