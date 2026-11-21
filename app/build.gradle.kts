plugins {
    id("com.android.application") version "8.12.0"
}

android {
    namespace = "tr.suleyman.mobilveri"
    compileSdk = 36

    defaultConfig {
        applicationId = "tr.suleyman.mobilveri"
        minSdk = 30
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
