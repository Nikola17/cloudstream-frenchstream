import com.android.build.gradle.BaseExtension

version = 21

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 21
    }
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    description = "Films et séries VF/VOSTFR en HD via French-Stream (enrichi TMDB, sans CAM)"
    iconUrl = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/icons/frenchstream.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
