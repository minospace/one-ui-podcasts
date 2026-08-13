import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing credentials, kept out of git (see keystore.properties.example).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "be.miro.onecast"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.miro.onecast"
        minSdk = 23
        targetSdk = 34
        versionCode = 33
        versionName = "2.9.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        viewBinding = true
    }
}

dependencies {
    // ── Samsung One UI (SESL) ──────────────────────────────────────────────
    // oneui-design + the SESL forks of AndroidX/Material, all from Maven Central.
    val sesl = "io.github.oneuiproject.sesl"
    implementation("io.github.oneuiproject:design:1.2.6")
    implementation("io.github.oneuiproject:icons:1.1.0")
    implementation("$sesl:appcompat:1.4.0")
    implementation("$sesl:material:1.5.0")
    implementation("$sesl:recyclerview:1.4.1")
    implementation("$sesl:coordinatorlayout:1.0.0")
    implementation("$sesl:drawerlayout:1.0.0")
    implementation("$sesl:swiperefreshlayout:1.0.0")
    implementation("$sesl:preference:1.1.0")
    implementation("$sesl:viewpager2:1.1.0")
    implementation("$sesl:fragment:1.0.0")
    implementation("$sesl:customview:1.1.0")
    implementation("$sesl:core:1.3.0")

    // Kotlin coroutines (pure-Kotlin, no AndroidX conflict).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle — Flow-based only (no LiveData; no activity/fragment-ktx, which
    // would drag stock androidx.activity/fragment versions that clash with SESL).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Playback: ExoPlayer + MediaSession (background + lock-screen controls)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Networking (iTunes Search API + feed download)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Artwork loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Palette — extract artwork colours for the player's dynamic background.
    // (Only depends on androidx.core/annotation; core is supplied by the SESL fork.)
    implementation("androidx.palette:palette:1.0.0")
}

// The SESL libraries above are drop-in forks of the stock AndroidX/Material
// modules. The originals must be excluded so only the Samsung-styled forks
// remain on the classpath (otherwise duplicate classes / wrong styling).
configurations.all {
    exclude(group = "androidx.appcompat", module = "appcompat")
    exclude(group = "androidx.core", module = "core")
    exclude(group = "androidx.coordinatorlayout", module = "coordinatorlayout")
    exclude(group = "androidx.drawerlayout", module = "drawerlayout")
    exclude(group = "androidx.recyclerview", module = "recyclerview")
    exclude(group = "androidx.viewpager", module = "viewpager")
    exclude(group = "androidx.viewpager2", module = "viewpager2")
    exclude(group = "androidx.fragment", module = "fragment")
    exclude(group = "androidx.customview", module = "customview")
    exclude(group = "androidx.swiperefreshlayout", module = "swiperefreshlayout")
    exclude(group = "androidx.preference", module = "preference")
    exclude(group = "com.google.android.material", module = "material")
}
