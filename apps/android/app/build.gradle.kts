plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional vendor registration. An unconfigured/unauthorized build retains ordinary notifications.
val xiaomiAppId = providers.gradleProperty("soundlink.xiaomiAppId")
    .orElse(providers.environmentVariable("SOUNDLINK_XIAOMI_APP_ID")).orElse("").get()
require(xiaomiAppId.isEmpty() || xiaomiAppId.all { it in '0'..'9' }) {
    "soundlink.xiaomiAppId must be the numeric App ID issued by Xiaomi"
}

android {
    namespace = "app.soundlink.android"
    compileSdk = 37
    testBuildType = providers.gradleProperty("soundlink.testBuildType").orElse("debug").get()
    defaultConfig {
        applicationId = providers.environmentVariable("SOUNDLINK_APPLICATION_ID").orElse("app.soundlink.android.dev").get()
        minSdk = 37
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1000
        versionName = "1.0.0"
        manifestPlaceholders["appLabel"] = providers.environmentVariable("SOUNDLINK_APP_NAME").orElse("Sound Link Dev").get()
        for ((field, variable) in mapOf("WEBSITE_URL" to "SOUNDLINK_WEBSITE_URL", "UPDATE_BASE_URL" to "SOUNDLINK_UPDATE_BASE_URL")) {
            val value = providers.environmentVariable(variable).orElse("").get()
            require(value.isEmpty() || (value.startsWith("https://") && value.none { it == '"' || it == '\\' || it.isWhitespace() }))
            buildConfigField("String", field, "\"$value\"")
        }
        manifestPlaceholders["xiaomiAppId"] = xiaomiAppId
        manifestPlaceholders["xiaomiDebugBuild"] = "false"
        buildConfigField("String", "XIAOMI_APP_ID", "\"$xiaomiAppId\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets
        .getByName("test")
        .resources
        .srcDir(rootProject.file("../../packages/protocol/fixtures"))
    buildFeatures {
        buildConfig = true
        compose = true
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["xiaomiDebugBuild"] = "true"
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.4.0-alpha01")
    androidTestImplementation("androidx.test:runner:1.8.0-alpha01")
    // Android 17 removed InputManager.getInstance used by older Espresso versions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha01")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
