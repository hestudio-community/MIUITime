plugins {
    id("com.android.application")
}

android {
    namespace = "net.hestudio.miuitime"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        applicationId = "net.hestudio.miuitime"
        minSdk = 35
        targetSdk = 37
        versionCode = 1003
        versionName = "1.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // Needed at test runtime so classes referencing hook types (e.g. ClockPageController) load.
    testImplementation("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
