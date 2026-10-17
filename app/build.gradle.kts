plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "app.looi.android"
    compileSdk = 36
    defaultConfig { applicationId = "app.looi.companion"; minSdk = 24; targetSdk = 36; versionCode = 7; versionName = "0.3.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Device HTTP destinations are restricted to RFC1918 addresses in DeviceWifiClient.
val lanHosts = providers.gradleProperty("looiLanHosts").orElse("")
val networkDir = layout.buildDirectory.dir("generated/looiNetwork")
val generateLooiNetwork by tasks.registering {
    inputs.property("lanHosts", lanHosts)
    outputs.dir(networkDir)
    doLast {
        val localHosts = lanHosts.get().split(',').map(String::trim).filter(String::isNotEmpty)
        localHosts.forEach { host ->
            val octets = host.split('.').mapNotNull(String::toIntOrNull)
            val ip = octets.size == 4 && octets.all { it in 0..255 } && (octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31)
            require(ip || host.matches(Regex("[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.local"))) { "looiLanHosts accepts only private IPv4 or .local hosts" }
        }
        val target = networkDir.get().file("xml/looi_network_security.xml").asFile
        target.parentFile.mkdirs()
        val hosts = (listOf("localhost", "127.0.0.1", "::1") + localHosts).distinct().joinToString("") { "<domain includeSubdomains=\"false\">$it</domain>" }
        target.writeText("<network-security-config><base-config cleartextTrafficPermitted=\"true\"/><domain-config cleartextTrafficPermitted=\"true\">$hosts</domain-config></network-security-config>")
    }
}
android.sourceSets["main"].res.srcDir(networkDir)
tasks.named("preBuild") { dependsOn(generateLooiNetwork) }
dependencyLocking { lockAllConfigurations() }
