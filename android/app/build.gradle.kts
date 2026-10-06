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

// Build settings come from, in order: a -Pkept.x=... command line flag, android/local.properties, a default.
fun cfg(key: String, default: String): String = (project.findProperty(key) as String?) ?: local.getProperty(key, default)

val targetCluster = cfg("kept.cluster", "devnet")
val debugWalletEnabled = cfg("kept.debugWallet", "false").toBoolean()
require(targetCluster == "devnet" || targetCluster == "mainnet-beta") { "kept.cluster must be devnet or mainnet-beta" }
if (targetCluster == "mainnet-beta") {
    require(!debugWalletEnabled) { "The software debug wallet is forbidden on mainnet." }
    require(cfg("kept.enableMainnet", "false").toBoolean()) {
        "Mainnet builds are blocked by default. Verify the deployed program and pass -Pkept.enableMainnet=true explicitly."
    }
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
        val cluster = targetCluster
        val proxyCluster = if (cluster == "mainnet-beta") "mainnet" else "devnet"
        // The RPC endpoint the app talks to. Release builds point at our proxy, never at a key.
        buildConfigField("String", "RPC_URL", "\"${cfg("kept.rpc", "https://kept-seeker.vercel.app/api/rpc?c=$proxyCluster")}\"")
        // Websocket for the "something happened in your wallet" nudge. It carries no key, so the public
        // endpoint is fine; the reflex also polls through the proxy, so a missed nudge costs seconds.
        buildConfigField("String", "WS_URL", "\"${cfg("kept.ws", if (cluster == "mainnet-beta") "wss://api.mainnet-beta.solana.com" else "wss://api.devnet.solana.com")}\"")
        // Bulk wallet history for the Mirror (our proxy holds the API key). Empty means read over plain RPC.
        buildConfigField("String", "HISTORY_URL", "\"${cfg("kept.history", "https://kept-seeker.vercel.app/api/history?c=$proxyCluster")}\"")
        buildConfigField("String", "CLUSTER", "\"$cluster\"")
        // Emulator testing only: a software main wallet instead of a wallet app. Ignored in release builds.
        buildConfigField("boolean", "DEBUG_WALLET", debugWalletEnabled.toString())
        buildConfigField("String", "VAULT_PROGRAM", "\"${cfg("kept.program", "EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")}\"")
    }

    signingConfigs {
        val ks = cfg("kept.signing.store", "").ifBlank { null }
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = cfg("kept.signing.storePassword", "")
                keyAlias = cfg("kept.signing.alias", "kept")
                keyPassword = cfg("kept.signing.keyPassword", "")
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
