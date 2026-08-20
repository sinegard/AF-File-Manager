plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.test") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
