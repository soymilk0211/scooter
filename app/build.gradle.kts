plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tw.scooter"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.scooter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-rules"))
    implementation(project(":data"))

    // BRouter 的路由核心（ADR-0016）。純 Java、346 KB、零個 android 參照。
    //
    // **沒有 Maven 座標。** 它只出現在 GitHub release 的 zip 裡
    // （https://github.com/abrensch/brouter/releases，取 `-ro.jar` 那個，
    // 不是 2.3 MB 的 `-all.jar` —— 後者含建圖工具，App 用不到）。
    // 所以版本寫在檔名裡，升級是手動換檔加改這一行。
    //
    // 搭配的 `lookups.dat` 與 `scooter-tw.brf` 在 assets/brouter/ 底下，
    // 兩者必須與這個 jar 同版本 —— lookups.dat 是標籤字典，
    // 版本對不上時的症狀是 profile 解析出錯，不是路線變差。
    implementation(files("libs/brouter-1.7.10-ro.jar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
}
