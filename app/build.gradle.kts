plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
val copySampleScores by tasks.registering(Copy::class) {
    from(rootProject.file("samples")) { include("*.json") }
    into(layout.buildDirectory.dir("generated/scoreAssets/scores"))
}
val copyThirdPartyNotices by tasks.registering(Copy::class) {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/noticeAssets"))
}
android {
    namespace = "io.github.openmusic"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.openmusic"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/scoreAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/noticeAssets"))
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
tasks.named("preBuild").configure { dependsOn(copySampleScores, copyThirdPartyNotices) }
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(rootProject.fileTree("samples") { include("*.json") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("net.alphatab:alphaTab:1.6.1")
    // Same Java API with 16 KiB-aligned native libraries; see docs/native-compatibility.md.
    implementation("net.alphatab:alphaSkia:3.5.147")
    implementation("net.alphatab:alphaSkia-android:3.5.147")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
