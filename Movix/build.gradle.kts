import com.android.build.gradle.BaseExtension

version = 2

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 2
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "Movix provider for CloudStream"
}
