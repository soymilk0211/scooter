import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 以 JDK 21 toolchain 編譯，但輸出 Java 17 位元碼 —— Android 端的 D8 對
// 較新的 class file 版本支援有限，統一降到 17 可避免與 :app 混用時出錯。
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
