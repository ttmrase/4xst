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
        versionCode = 120
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 端末内 LLM のネイティブライブラリで APK が大きくなるので、個人の実機（arm64）向けだけにする
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 署名: debug は固定の keystore（app/debug.keystore。debug 鍵は秘密ではない）。CI の実行ごとに鍵が変わると、
    // 上書きインストールが「アプリはインストールされていません」で失敗するため。
    // release は環境変数（RELEASE_KEYSTORE_PATH、RELEASE_STORE_PASSWORD、RELEASE_KEY_ALIAS、RELEASE_KEY_PASSWORD）が
    // 揃っているときだけその鍵を使い、無ければ固定の debug 鍵にフォールバックする。鍵とパスワードはリポジトリに置かない。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: System.getenv("RELEASE_KEYSTORE_FILE")
            val keystore = keystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
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
        debug {
            // CI の ui ジョブ（x86_64 のエミュレータ）用。release は arm64-v8a のままで、x86_64 は入れない（02 §18）
            ndk {
                abiFilters += "x86_64"
            }
        }
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
        unitTests.all {
            it.useJUnitPlatform()
            // 失敗したテストのメッセージを CI のログに出す（レポートの artifact を開かなくても原因が読める）
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = false
            }
        }
        // Robolectric（NavController と Compose の回帰テスト）が manifest とリソースを使う
        unitTests.isIncludeAndroidResources = true
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
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.litertlm.android)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    // 回帰テスト用（JUnit4 の Robolectric は Vintage エンジンで JUnit5 と同居する）
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.androidx.test.espresso.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.compose.ui.test.manifest)

    // エミュレータの androidTest（CI の ui ジョブ）
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
