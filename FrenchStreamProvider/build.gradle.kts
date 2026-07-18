import com.android.build.gradle.BaseExtension

version = 6

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 6
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "French-Stream provider for CloudStream"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
