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
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// M1 DoD: sim / economy パッケージは「パッケージごとに」行カバレッジ 80% 以上（`./gradlew :core:koverVerify`）
kover {
    reports {
        // 注意: filters は koverXmlReport / koverHtmlReport にも効く（M1 では sim / economy のみが対象）
        filters {
            includes {
                packages("com.ttmrase.crownfall.core.sim", "com.ttmrase.crownfall.core.economy")
            }
        }
        verify {
            rule("sim / economy line coverage per package") {
                groupBy = GroupingEntityType.PACKAGE
                minBound(80)
            }
        }
    }
}
