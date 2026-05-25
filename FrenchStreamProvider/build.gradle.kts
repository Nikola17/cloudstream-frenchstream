import com.android.build.gradle.BaseExtension

version = 3

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 3
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "French-Stream provider for CloudStream"
}
