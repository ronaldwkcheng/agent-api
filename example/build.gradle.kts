plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Runnable demos of each workflow pattern"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    // The Boot BOM comes from the Spring Boot plugin; only the AI BOM needs importing.
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    implementation(project(":api"))
    implementation("org.springframework.boot:spring-boot-starter")

    // :api is provider-agnostic, so the choice of model provider — and its autoconfiguration —
    // lands here.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
