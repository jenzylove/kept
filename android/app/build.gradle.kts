import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Dev-only settings live in android/local.properties (gitignored): kept.rpc=<url>, kept.signing.*
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.kept"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kept"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // The RPC endpoint the app talks to. Release builds point at our proxy, never at a key.
        buildConfigField("String", "RPC_URL", "\"${local.getProperty("kept.rpc", "https://api.mainnet-beta.solana.com")}\"")
        buildConfigField("String", "CLUSTER", "\"${local.getProperty("kept.cluster", "mainnet-beta")}\"")
        // Emulator testing only: a software main wallet instead of a wallet app. Ignored in release builds.
        buildConfigField("boolean", "DEBUG_WALLET", local.getProperty("kept.debugWallet", "false"))
        buildConfigField("String", "VAULT_PROGRAM", "\"${local.getProperty("kept.program", "EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")}\"")
    }

    signingConfigs {
        val ks = local.getProperty("kept.signing.store")
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = local.getProperty("kept.signing.storePassword")
                keyAlias = local.getProperty("kept.signing.alias")
                keyPassword = local.getProperty("kept.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.withType<Test>().configureEach {
    // Shared test vectors (real mainnet transactions) live with the TypeScript engine.
    systemProperty("kept.vectors", rootProject.file("../engine/vectors.json").absolutePath)
    systemProperty("kept.idl", rootProject.file("../programs/kept_vault/idl.json").absolutePath)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.sol4k:sol4k:0.8.2")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
