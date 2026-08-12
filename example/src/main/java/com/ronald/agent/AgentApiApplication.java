package com.ronald.agent;

import com.ronald.agent.example.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Application entry point.
 *
 * <p>Each workflow pattern has a demo {@link CommandLineRunner} that is registered only when
 * the {@code agent.demo} property selects it, so a plain boot — and every test — starts without
 * calling the model provider. Run one with:</p>
 *
 * <pre>{@code
 * ./gradlew bootRun --args='--agent.demo=react'
 * }</pre>
 *
 * <p>Valid values: {@code sequential}, {@code parallel}, {@code conditional},
 * {@code iterative}, {@code plan-and-execute}, {@code react}. Values are matched exactly,
 * so relaxed binding does not apply — spell them as written.</p>
 */
@SpringBootApplication
public class AgentApiApplication {

    /** Property selecting which demo runner, if any, is registered. */
    private static final String DEMO_PROPERTY = "agent.demo";

    public static void main(String[] args) {
        SpringApplication.run(AgentApiApplication.class, args);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public Executor executor() {
        // Virtual threads give you one lightweight thread per branch at zero pool-sizing cost.
        // Replace with Executors.newFixedThreadPool(N) if you need bounded concurrency.
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "sequential")
    CommandLineRunner sequentialRunner(SequentialWorkflowExample service) {
        return args -> {
            String userComplaint = "I was charged twice for my subscription this month and I need a refund!";
            String result = service.processSupportRequest(userComplaint);
            System.out.println("\n=== SequentialWorkflowExample result ===");
            System.out.println(result);
        };
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "parallel")
    CommandLineRunner parallelRunner(ParallelWorkflowExample service) {
        return args -> {
            String input = "I love the new features in your product, but sometimes it crashes unexpectedly. Also, I found a bug in the latest update.";
            String result = service.analysis(input);
            System.out.println("\n=== ParallelWorkflowExample result ===");
            System.out.println(result);
        };
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "conditional")
    CommandLineRunner conditionalRunner(ConditionalWorkflowExample service) {
        return args -> {
            System.out.println("\n=== ConditionalWorkflowExample result ===");

            String r1 = service.route("I was charged twice for my last invoice. Can you help?");
            System.out.println("[BILLING] " + r1);

            String r2 = service.route("My application crashes when I try to upload a file.");
            System.out.println("[TECH] " + r2);

            String r3 = service.route("What are your business hours?");
            System.out.println("[FALLBACK] " + r3);
        };
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "iterative")
    public CommandLineRunner refineRunner(IterativeRefinementWorkflowExample service) {
        return args -> {
            System.out.println("\n=== IterativeRefinementWorkflowExample result ===");
            String input = """
                    Create a short story for children.
                    """;
            String criteria = """
                    1. The story features a turtle as the main character on an adventure.
                    2. The story should have a clear beginning, middle, and end, with a positive moral lesson about courage, friendship, or perseverance.
                    3. The story should be engaging for children, with simple language, vivid descriptions, and a happy ending.
                    4. The story should be concise, ideally under 250 words.
                    """;
            String result = service.refineContent(input, criteria);
            System.out.println("\n=== IterativeRefinementWorkflowExample result ===");
            System.out.println(result);
        };
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "plan-and-execute")
    CommandLineRunner planAndExecuteRunner(PlanAndExecuteWorkflowExample service) {
        return args -> {
            System.out.println("\n=== PlanAndExecuteWorkflowExample result ===");
            String topic = "The impact of artificial intelligence on the future of software engineering";
            String result = service.research(topic);
            System.out.println(result);
        };
    }

    @Bean
    @ConditionalOnProperty(name = DEMO_PROPERTY, havingValue = "react")
    CommandLineRunner reactRunner(ReActWorkflowExample service) {
        return args -> {
            System.out.println("\n=== ReActWorkflowExample result ===");
            String question = "How many words are in 'Four score and seven years ago our fathers brought forth on this continent a new nation'? Also convert 37 degrees Celsius to Fahrenheit, and tell me today's date.";
            String result = service.answer(question);
            System.out.println(result);
        };
    }
}
