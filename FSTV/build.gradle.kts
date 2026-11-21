import com.android.build.gradle.BaseExtension

version = 13

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 13
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    status = 1
    tvTypes = listOf("Live")
    description = "Chaînes TV HD en direct via WITV (182 chaînes) et matchs sport en direct via BZH-Sport"
    iconUrl = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/icons/fstv.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
