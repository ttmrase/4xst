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
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// M1 DoD: sim / economy パッケージは「パッケージごとに」行カバレッジ 80% 以上（`./gradlew :core:koverVerify`）
kover {
    reports {
        // 注意: filters は koverXmlReport / koverHtmlReport にも効く（対象は includes のパッケージのみ）
        filters {
            includes {
                packages(
                    "com.ttmrase.crownfall.core.sim",
                    "com.ttmrase.crownfall.core.economy",
                    "com.ttmrase.crownfall.core.session",
                    "com.ttmrase.crownfall.core.buff",
                    "com.ttmrase.crownfall.core.research",
                    "com.ttmrase.crownfall.core.military",
                    "com.ttmrase.crownfall.core.hero",
                    "com.ttmrase.crownfall.core.map",
                    "com.ttmrase.crownfall.core.march",
                    "com.ttmrase.crownfall.core.combat",
                    "com.ttmrase.crownfall.core.alliance",
                    "com.ttmrase.crownfall.core.chat",
                    "com.ttmrase.crownfall.core.shop",
                    "com.ttmrase.crownfall.core.quest",
                    "com.ttmrase.crownfall.core.event",
                    "com.ttmrase.crownfall.core.notify",
                    "com.ttmrase.crownfall.core.bubble",
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
