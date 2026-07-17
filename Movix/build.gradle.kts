import com.android.build.gradle.BaseExtension

version = 5

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 5
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "Movix provider for CloudStream"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
