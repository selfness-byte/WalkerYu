import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// (선택) local.properties 에 키를 넣으면 앱 설정의 기본값으로 쓰인다. 비워 두고 앱에서 입력해도 된다.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProp(key: String): String =
    localProps.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.mj.transitmap"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.mj.transitmap"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.2"

        buildConfigField("String", "GBIS_SERVICE_KEY", "\"${localProp("GBIS_SERVICE_KEY")}\"")
        buildConfigField("String", "SEOUL_SUBWAY_KEY", "\"${localProp("SEOUL_SUBWAY_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // API 키 없이 동작하는 오픈스트리트맵 지도
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // 현재 위치(FusedLocationProviderClient)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
