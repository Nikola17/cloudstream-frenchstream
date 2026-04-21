// Top-level build file
plugins {
    id("com.github.ben-manes.versions") version "0.51.0"
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
