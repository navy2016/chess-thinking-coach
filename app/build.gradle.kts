plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sanqi.coach"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.sanqi.coach"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/engineAssets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/engineLibs"))
    packaging { jniLibs.useLegacyPackaging = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val verifyProfessionalEngines by tasks.registering {
    doLast {
        val root = layout.buildDirectory.get().asFile
        val required = listOf("generated/engineAssets/engines/catalog.json") +
            listOf("stockfish", "pikafish", "katago", "fairystockfish").map { "generated/engineLibs/arm64-v8a/lib$it.so" }
        required.forEach { check(root.resolve(it).isFile) { "Missing $it; run scripts/build_android_engines.sh in the Android CI environment first" } }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyProfessionalEngines) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
