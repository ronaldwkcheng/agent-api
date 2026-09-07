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
 * <p>Chroma's vector store is pinned off on the same principle. Its autoconfiguration defaults to
 * <b>on</b> once the starter is on the classpath — {@code matchIfMissing = true} — and its store
 * connects to Chroma and creates its collection during startup, so only the
 * {@code spring.ai.vectorstore.type} value keeps this build off the network.
 * {@code application.properties} sets it to {@code none} already; the test states the requirement
 * rather than inheriting it, and {@link #noVectorStoreIsRegisteredWithoutChromaSelected()}
 * guards it.</p>
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-never-used",
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.vectorstore.type=none"
})
class AgentApiApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void noVectorStoreIsRegisteredWithoutChromaSelected() {
        // A ChromaVectorStore bean is an InitializingBean that connects to Chroma and creates its
        // collection during startup, so one existing here would make every build depend on a
        // Chroma running on localhost:8000. Unlike the MCP client, this autoconfiguration is on
        // unless told otherwise, so the property is the only thing standing between the build and
        // the network.
        assertEquals(0, context.getBeanNamesForType(VectorStore.class).length,
                "no VectorStore may be registered unless spring.ai.vectorstore.type=chroma");
        assertEquals(0, context.getBeanNamesForType(ChromaApi.class).length,
                "no ChromaApi may be registered unless spring.ai.vectorstore.type=chroma");
    }

    @Test
    void noDemoRunnerIsRegisteredWithoutTheDemoProperty() {
        String[] runners = context.getBeanNamesForType(CommandLineRunner.class);

        assertTrue(runners.length == 0,
                "Demo runners must stay unregistered during tests, otherwise the build issues "
                        + "live model requests. Found: " + String.join(", ", runners));
    }
}
