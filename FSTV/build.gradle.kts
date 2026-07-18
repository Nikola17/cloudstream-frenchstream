import com.android.build.gradle.BaseExtension

version = 1

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 1
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "Chaînes TV en direct de French Stream TV"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
