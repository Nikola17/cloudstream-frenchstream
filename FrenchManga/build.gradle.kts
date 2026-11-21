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
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    description = "Animes et films d'animation VF/VOSTFR en HD via French-Manga"
    iconUrl = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/icons/frenchmanga.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
