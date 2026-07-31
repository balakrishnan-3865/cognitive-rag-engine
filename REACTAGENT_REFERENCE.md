# ReactAgent Quick Reference


## Framework Overview
**Dependency:** `spring-ai-alibaba-agent-framework:1.1.2.0`

ReactAgent is a ReAct (Reasoning + Acting) agent framework that combines LLM reasoning with tool execution. It manages the **Think → Act → Observe** loop automatically.

---

## Core Building Blocks

### 1. Agent Creation
```java
@Autowired
private ChatModel chatModel;  // Injected from application.yml config

ReactAgent agent = ReactAgent.builder()
    .name("my_agent")
    .model(chatModel)
    .systemPrompt("You are a helpful assistant")
    .tools(tool1, tool2)
    .saver(new MemorySaver())  // or CheckPointer for production
    .build();
```

### 2. ChatModel Configuration (application.yml)
```yaml
spring:
  ai:
    openai:  # or anthropic, azure, etc.
      api-key: ${OPENAI_API_KEY}
      model: gpt-4
      temperature: 0.7
      max-tokens: 1000
```

```java
// ChatModel is auto-configured from application.yml
@Autowired
private ChatModel chatModel;

// Or customize programmatically
ChatModel customModel = new OpenAiChatModel(openAiApi, options);
```

---

## Tool Definition (Modern @Tool Approach)

### ✅ Recommended: Using @Tool Annotation

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;

@Service
public class WeatherService {
    
    @Tool(description = "Get current weather for a given city")
    public WeatherResponse getWeather(
            @ToolParam(description = "City name") String city,
            ToolContext context) {
        
        // Access runtime context
        RunnableConfig config = (RunnableConfig) context.getContext()
            .get(AGENT_CONFIG_CONTEXT_KEY);
        String userId = config.metadata("user_id").orElse("unknown");
        
        // Business logic
        WeatherData data = weatherApi.getWeather(city);
        
        // Store data in context (citations, results, etc.)
        Map<String, Object> toolResults = (Map) context.getContext()
            .getOrDefault("TOOL_RESULTS", new HashMap<>());
        toolResults.put("weather_" + city, data);
        context.getContext().put("TOOL_RESULTS", toolResults);
        
        return new WeatherResponse(city, data.getTemperature(), data.getConditions());
    }
}
```

### Tool Registration
```java
// Register service with multiple @Tool methods
ToolCallback[] toolCallbacks = ToolCallbacks.from(weatherService);

// Register specific methods
ToolCallback weatherTool = ToolCallbacks.from(weatherService, "getWeather");
ToolCallback locationTool = ToolCallbacks.from(weatherService, "getUserLocation");

ReactAgent agent = ReactAgent.builder()
    .tools(weatherTool, locationTool)
    .build();
```

### Simple Function Tool
```java
@Tool(description = "Get user location")
public String getUserLocation(
        @ToolParam(description = "User query") String query,
        ToolContext context) {
    return "San Francisco";  // Simplified
}
```

---

## Execution & Memory

### Basic Call
```java
AssistantMessage response = agent.call("What's the weather in Miami?");
System.out.println(response.getText());
```

### With Memory (threadId)
```java
String threadId = "user-123-session-456";
RunnableConfig config = RunnableConfig.builder()
    .threadId(threadId)
    .addMetadata("user_id", "123")
    .build();

// First call
AssistantMessage response1 = agent.call("What's the weather?", config);

// Second call (remembers conversation)
AssistantMessage response2 = agent.call("How about tomorrow?", config);
```

### With Structured Output
```java
// Define response format
public class WeatherResponse {
    private String city;
    private String temperature;
    private String conditions;
    private String recommendation;
    // getters/setters
}

ReactAgent agent = ReactAgent.builder()
    .outputType(WeatherResponse.class)
    .build();

WeatherResponse response = agent.call("Weather in Miami", WeatherResponse.class);
```

---

## Limits & Safety

### Primary: ModelCallLimitHook (Counts LLM calls)
```java
ModelCallLimitHook hook = ModelCallLimitHook.builder()
    .runLimit(5)  // Max LLM thinking iterations
    .exitBehavior(ModelCallLimitHook.ExitBehavior.RETURN_PARTIAL)  // or ERROR
    .build();

ReactAgent agent = ReactAgent.builder()
    .hooks(hook)
    .build();
```

### Secondary: recursionLimit (Counts all graph nodes)
```java
ReactAgent agent = ReactAgent.builder()
    .compileConfig(CompileConfig.builder()
        .recursionLimit(20)  // Includes tool calls + LLM calls
        .build())
    .build();
```

### Key Difference
| Mechanism | Counts | Purpose |
|-----------|--------|---------|
| **ModelCallLimitHook** | Only LLM API calls | Cost control (primary) |
| **recursionLimit** | All graph steps (LLM + tools) | Structural safety (backup) |

---

## Advanced Patterns

### 1. Tracking Multiple Tool Calls with Citations

```java
// ToolResultHolder for tracking across tools
public class ToolResultHolder {
    private final Map<String, Citation> citations = new ConcurrentHashMap<>();
    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    
    public void addResult(String id, Citation citation) {
        if (!processed.contains(id)) {
            citations.put(id, citation);
            processed.add(id);
        }
    }
    
    public List<Citation> getAll() { return new ArrayList<>(citations.values()); }
}

// In tool method
@Tool(description = "Get weather")
public WeatherData getWeather(
        @ToolParam(description = "City") String city,
        ToolContext context) {
    
    ToolResultHolder holder = (ToolResultHolder) context.getContext()
        .get("RESULT_HOLDER");
    
    WeatherData data = api.getWeather(city);
    holder.addResult(city + "_weather", 
        new Citation(city, data, "WeatherAPI"));
    
    return data;
}

// Agent setup
ToolResultHolder holder = new ToolResultHolder();
ReactAgent agent = ReactAgent.builder()
    .tools(ToolCallbacks.from(weatherService))
    .toolContext(Map.of("RESULT_HOLDER", holder))
    .build();
```

### 2. Awareness Hook (Notify Agent of Limits)
```java
public class AwarenessHook implements Hook {
    private int callCount = 0;
    private final int warningThreshold = 7;
    
    @Override
    public void beforeModelCall(AgentContext context) {
        callCount++;
        if (callCount >= warningThreshold) {
            context.addSystemMessage(
                "WARNING: You have " + (10 - callCount) + " iterations left. " +
                "Please provide final answer now."
            );
        }
    }
}

ReactAgent agent = ReactAgent.builder()
    .hooks(new AwarenessHook(), modelLimitHook)
    .build();
```

### 3. Production Memory (Persistent)
```java
// In-memory (dev only)
.saver(new MemorySaver())

// Production (persistent)
.saver(new CheckPointer(jdbcTemplate))  // or Redis, etc.
```

### 4. Fallback for Partial Results
```java
try {
    AssistantMessage response = agent.call(query, config);
    if (isComplete(response)) {
        return response.getText();
    }
    return fallbackService.getCachedResponse(query);
} catch (ModelCallLimitExceededException e) {
    return "I couldn't complete the analysis. Here's what I found so far...";
}
```

---

## Key Concepts Quick Reference

### ModelCallLimitHook vs recursionLimit
```java
// Example with 2 tool calls:
// Iteration: Think → Tool → Think → Tool → Think → Response

// ModelCallLimitHook counts: 3 (only LLM thinks)
// recursionLimit counts: 6 (every node in graph)

// Use BOTH for production safety
ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
    .runLimit(10)  // Primary cost control
    .build();

.compileConfig(CompileConfig.builder()
    .recursionLimit(25)  // Safety net (higher than runLimit)
    .build());
```

### ToolContext Usage
```java
// ToolContext passes runtime data between agent and tools
public Response myTool(Request req, ToolContext ctx) {
    // Read data
    String userId = ctx.getContext().get("USER_ID");
    RunnableConfig config = ctx.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
    
    // Write data
    ctx.getContext().put("CITATION", citation);
    ctx.getContext().put("RESULT", result);
    
    return response;
}
```

### RunnableConfig.metadata()
```java
// Pass data to tools via metadata
RunnableConfig config = RunnableConfig.builder()
    .threadId(threadId)
    .addMetadata("user_id", "123")
    .addMetadata("session_id", "abc")
    .addMetadata("request_id", UUID.randomUUID().toString())
    .build();

// Access in tool:
String userId = (String) config.metadata("user_id").orElse("default");
```

---

## Common Scenarios

### Scenario: Agent with Weather + Location Tools
```java
@Service
public class WeatherService {
    @Tool(description = "Get weather for city")
    public String getWeather(@ToolParam(description = "City name") String city) {
        return "Sunny in " + city;
    }
    
    @Tool(description = "Get user's location")
    public String getUserLocation(@ToolParam(description = "User query") String query) {
        return "San Francisco";  // From context or user profile
    }
}

// Agent with injected ChatModel
@Service
public class AgentService {
    @Autowired
    private ChatModel chatModel;
    
    public ReactAgent createWeatherAgent() {
        ToolCallback[] tools = ToolCallbacks.from(new WeatherService());
        
        return ReactAgent.builder()
            .name("weather_agent")
            .model(chatModel)
            .tools(tools)
            .systemPrompt("""
                You are a weather assistant with access to:
                - getWeather: Get weather for a specific city
                - getUserLocation: Detect user's location
                
                If location is unknown, use getUserLocation first.
                """)
            .saver(new MemorySaver())
            .build();
    }
}
```

### Scenario: Agent with Citation Tracking
```java
@Service
public class ResearchService {
    @Tool(description = "Search knowledge base")
    public SearchResult searchKnowledge(
            @ToolParam(description = "Search query") String query,
            ToolContext context) {
        
        SearchResult result = knowledgeBase.search(query);
        
        // Store citation
        Citation citation = new Citation(query, result);
        Map<String, List<Citation>> citations = 
            (Map) context.getContext().getOrDefault("CITATIONS", new HashMap<>());
        citations.computeIfAbsent("search_" + query, k -> new ArrayList<>())
            .add(citation);
        context.getContext().put("CITATIONS", citations);
        
        return result;
    }
}
```

### Scenario: Multi-Agent with Different Models
```java
@Service
public class MultiAgentService {
    @Autowired
    private ChatModel chatModel;  // default model
    
    @Autowired
    private ChatModel fastModel;  // configured separately
    
    public ReactAgent createFastAgent() {
        return ReactAgent.builder()
            .name("quick_agent")
            .model(fastModel)  // faster, cheaper model
            .tools(tools)
            .build();
    }
    
    public ReactAgent createComplexAgent() {
        return ReactAgent.builder()
            .name("complex_agent")
            .model(chatModel)  // more capable model
            .tools(complexTools)
            .build();
    }
}
```

---

## Production Checklist

- [ ] Use **ModelCallLimitHook** with `runLimit(5-10)`
- [ ] Use **recursionLimit** as safety net (2-3x runLimit)
- [ ] Use **CheckPointer** for persistent memory (not MemorySaver)
- [ ] Implement **fallback handling** for partial responses
- [ ] Use **ToolResultHolder** for tracking multiple tool calls
- [ ] Add **awareness hooks** to notify agent of limits
- [ ] Log all agent interactions for debugging
- [ ] Monitor token usage and costs
- [ ] Handle exceptions gracefully (ModelCallLimitExceededException)
- [ ] Use **@Tool** annotation for cleaner tool definitions
- [ ] Configure ChatModel via `application.yml` for environment-specific settings
- [ ] Use Spring's `@Autowired` for dependency injection

---

## Dependency Setup

```xml
<dependencies>
    <!-- ReactAgent Framework -->
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-agent-framework</artifactId>
        <version>1.1.2.0</version>
    </dependency>
    
    <!-- Your LLM Provider (e.g., OpenAI) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
        <version>1.1.2</version>
    </dependency>
    
    <!-- Or Anthropic -->
    <!-- <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-anthropic</artifactId>
        <version>1.1.2</version>
    </dependency> -->
</dependencies>
```

---

## Quick Error Recovery

```java
@Service
public class AgentResponseHandler {
    @Autowired
    private ChatModel chatModel;
    
    public String callWithFallback(ReactAgent agent, String query) {
        try {
            AssistantMessage response = agent.call(query);
            return response.getText();
        } catch (ModelCallLimitExceededException e) {
            // Agent hit iteration limit
            return "I need more time to analyze this. Here's what I found so far...";
        } catch (Exception e) {
            // Other failures
            log.error("Agent failed", e);
            return "I couldn't process that request. Please try again.";
        }
    }
}
```

---

## Configuration Best Practices

### application.yml
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4-turbo-preview
      temperature: 0.7
      max-tokens: 2000
      # Optional: multiple models
    openai-fast:
      api-key: ${OPENAI_API_KEY}
      model: gpt-3.5-turbo
      temperature: 0.5
      max-tokens: 1000
```

### Java Configuration
```java
@Configuration
public class AgentConfig {
    
    @Bean
    @Primary
    public ChatModel chatModel(OpenAiApi openAiApi) {
        return new OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
            .model("gpt-4-turbo-preview")
            .temperature(0.7)
            .build());
    }
    
    @Bean
    public ChatModel fastModel(OpenAiApi openAiApi) {
        return new OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
            .model("gpt-3.5-turbo")
            .temperature(0.5)
            .build());
    }
}
```

---