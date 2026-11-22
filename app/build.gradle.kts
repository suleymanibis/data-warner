plugins {
    id("com.android.application") version "8.12.0"
}

val releaseSigningFields = mapOf(
    "storeFile" to providers.environmentVariable("DATA_WARNER_KEYSTORE"),
    "storePassword" to providers.environmentVariable("DATA_WARNER_STORE_PASSWORD"),
    "keyAlias" to providers.environmentVariable("DATA_WARNER_KEY_ALIAS"),
    "keyPassword" to providers.environmentVariable("DATA_WARNER_KEY_PASSWORD")
)
val releaseSigning = releaseSigningFields.mapValues { it.value.orNull }
require(releaseSigning.values.all { it.isNullOrBlank() } || releaseSigning.values.none { it.isNullOrBlank() }) {
    "Set all four DATA_WARNER signing environment variables for a signed release."
}

android {
    namespace = "tr.suleyman.mobilveri"
    compileSdk = 36

    defaultConfig {
        applicationId = "tr.suleyman.mobilveri"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "1.9.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (releaseSigning.values.none { it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseSigning.getValue("storeFile")!!)
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
