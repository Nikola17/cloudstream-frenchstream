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
    description = "Animes et films d'animation de French-Manga"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
