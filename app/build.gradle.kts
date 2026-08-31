plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myaiapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myaiapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        // SMART FILTER: यह ऐप का साइज़ छोटा रखेगा और सिर्फ Vivo Y75 5G जैसे 64-bit फोन के लिए इंजन पैक करेगा
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Background Processing Libraries
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // C++ Engine Bridge (JNA Library) - यह Kotlin को C++ से बिना एरर के जोड़ेगी
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
