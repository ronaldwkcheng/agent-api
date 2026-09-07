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

    // MCP tool servers. Autoconfigures the McpSyncClients and the ToolCallbackProvider bean that
    // ReActWorkflow.Builder.toolCallbackProvider(...) consumes; the servers themselves are
    // declared in application.properties and are disabled by default. This is the plain
    // HttpClient-based starter — the -webflux variant would drag a reactive stack into a
    // deliberately non-web app.
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")

    // Chroma, the vector store the RAG demo retrieves from. Deliberately the plain store library
    // and not spring-ai-starter-vector-store-chroma: the starter's autoconfiguration builds a
    // ChromaVectorStore eagerly, and that bean connects to Chroma and creates its collection
    // while the context starts, so a plain boot and every @SpringBootTest would need a running
    // Chroma on localhost:8000. ChromaConfiguration wires the same beans behind the agent.demo
    // property instead.
    implementation("org.springframework.ai:spring-ai-chroma-store")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
