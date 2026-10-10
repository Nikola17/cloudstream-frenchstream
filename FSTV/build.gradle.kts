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
    description = "Chaînes TV en direct de French Stream TV, avec événements sportifs et secours BZH-Sport"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
