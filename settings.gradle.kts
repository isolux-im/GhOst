rootProject.name = "GhOst"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Domaine pur (JVM) : règles d'expiration, rappels, horloge de confiance.
// Le module Android (:app) sera ajouté au lot L1 (nécessite le SDK Android).
include(":core:expiry")
