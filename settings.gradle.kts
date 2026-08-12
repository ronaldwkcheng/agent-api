pluginManagement {
    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings

    // Versions are declared once, in gradle.properties, so the subprojects can apply these
    // plugins by id alone.
    plugins {
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version springDependencyManagementVersion
    }
}

rootProject.name = "agent-subagent"

include("api", "example")
