import com.android.build.gradle.BaseExtension

version = 10

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 10
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    description = "Films et séries VF/VOSTFR via Movix & TMDB (plateformes, FStream, Wiflix)"
    iconUrl = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/icons/movix.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
