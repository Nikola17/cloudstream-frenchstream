import com.android.build.gradle.BaseExtension

version = 4

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 4
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "French-Stream provider for CloudStream"
}
