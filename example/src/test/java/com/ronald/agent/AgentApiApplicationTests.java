package com.ronald.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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
    void noDemoRunnerIsRegisteredWithoutTheDemoProperty() {
        String[] runners = context.getBeanNamesForType(CommandLineRunner.class);

        assertTrue(runners.length == 0,
                "Demo runners must stay unregistered during tests, otherwise the build issues "
                        + "live model requests. Found: " + String.join(", ", runners));
    }
}
