plugins {
    id("com.android.application")
}

android {
    namespace = "com.cx531.adbkeep"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cx531.adbkeep"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 与 Aerial 同用 debug 签名，方便共存/覆盖安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 极简：不需要任何第三方依赖
}
