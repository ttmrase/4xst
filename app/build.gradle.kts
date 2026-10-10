import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.ttmrase.crownfall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ttmrase.crownfall"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    // 署名: debug は固定の keystore（app/debug.keystore。debug 鍵は秘密ではない）。CI の実行ごとに鍵が変わると、
    // 上書きインストールが「アプリはインストールされていません」で失敗するため。
    // release は環境変数（RELEASE_KEYSTORE_PATH（または RELEASE_KEYSTORE_FILE）/ RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD）が
    // 揃っているときだけその鍵を使い、無ければ固定の debug 鍵にフォールバックする。鍵とパスワードはリポジトリに置かない。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystore = (System.getenv("RELEASE_KEYSTORE_PATH") ?: System.getenv("RELEASE_KEYSTORE_FILE"))?.takeIf { it.isNotBlank() }?.let { file(it) }
            val storePass = System.getenv("RELEASE_STORE_PASSWORD")
            val alias = System.getenv("RELEASE_KEY_ALIAS")
            val keyPass = System.getenv("RELEASE_KEY_PASSWORD")
            val complete = !storePass.isNullOrEmpty() && !alias.isNullOrEmpty() && !keyPass.isNullOrEmpty()
            if (keystore != null && keystore.isFile && complete) {
                storeFile = keystore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 は M8 では無効（kotlinx.serialization のリフレクションが実機でだけ壊れるのを避ける）
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ai"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
