plugins {
    `java-library`
    id("io.spring.dependency-management")
}

description = "Agentic workflow patterns, sub-agents and advisors built on Spring AI"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// The Spring Boot plugin is deliberately not applied: this module is a plain library, so it has
// no bootJar and no bootRun. It still needs the Boot BOM for test dependency versions, which the
// plugin would otherwise import for us.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    // `api`, not `implementation`: SubAgent, AgenticWorkflow and AgenticWorkflowAdvisor all
    // expose Spring AI types (ChatClient, Message, ToolCallback) in their public signatures,
    // so consumers compile against them. No model provider is declared here — picking one is
    // the application's job.
    api("org.springframework.ai:spring-ai-client-chat")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
