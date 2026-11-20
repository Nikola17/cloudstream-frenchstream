import com.android.build.gradle.BaseExtension

version = 9

extensions.configure<BaseExtension>("android") {
    defaultConfig {
        versionCode = 9
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "Chaînes TV en direct via WITV, matchs et événements sport via BZH-Sport (bzzh.lat)"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
