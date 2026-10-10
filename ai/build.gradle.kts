import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // CI は JDK 17。ローカル（JDK 21）で 18 以降の API を使うとコンパイルが通ってしまうのを防ぐ。
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// M5: router / providers はパッケージごとに行カバレッジ 80% 以上（`./gradlew :ai:koverVerify`）
kover {
    reports {
        filters {
            includes {
                packages(
                    "com.ttmrase.crownfall.ai.router",
                    "com.ttmrase.crownfall.ai.providers",
                    "com.ttmrase.crownfall.ai.ondevice",
                )
            }
        }
        verify {
            rule("coverage-gated packages line coverage per package") {
                groupBy = GroupingEntityType.PACKAGE
                minBound(80)
            }
        }
    }
}
