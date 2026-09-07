package com.ronald.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full application context.
 *
 * <p>The API key is overridden with a placeholder so the test needs no environment variable.
 * Nothing here should reach the model provider: {@code @SpringBootTest} calls
 * {@code SpringApplication.run()}, which executes {@link CommandLineRunner} beans, so a demo
 * runner registered unconditionally would issue real, billable requests on every build.
 * The runners are gated behind the {@code agent.demo} property, which is deliberately unset
 * here.</p>
 *
 * <p>The MCP client is pinned off for the same reason. It defaults to off in
 * {@code application.properties} already, but enabling it starts every configured server —
 * spawning a child process for a stdio one — so the test states the requirement rather than
 * inheriting it.</p>
 *
 * <p>Chroma needs no such flag: {@code ChromaConfiguration} is gated on {@code agent.demo=rag},
 * so with that property unset there is no {@code ChromaVectorStore} bean to connect with. That
 * gate is what {@link #noVectorStoreIsRegisteredWithoutTheDemoProperty()} guards.</p>
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-never-used",
        "spring.ai.mcp.client.enabled=false"
})
class AgentApiApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void noVectorStoreIsRegisteredWithoutTheDemoProperty() {
        // A ChromaVectorStore bean is an InitializingBean that connects to Chroma and creates its
        // collection during startup, so one existing here would make every build depend on a
        // Chroma running on localhost:8000.
        assertEquals(0, context.getBeanNamesForType(VectorStore.class).length,
                "no VectorStore may be registered without agent.demo=rag");
        assertEquals(0, context.getBeanNamesForType(ChromaApi.class).length,
                "no ChromaApi may be registered without agent.demo=rag");
    }

    @Test
    void noDemoRunnerIsRegisteredWithoutTheDemoProperty() {
        String[] runners = context.getBeanNamesForType(CommandLineRunner.class);

        assertTrue(runners.length == 0,
                "Demo runners must stay unregistered during tests, otherwise the build issues "
                        + "live model requests. Found: " + String.join(", ", runners));
    }
}
