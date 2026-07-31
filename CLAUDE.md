# CLAUDE.md

## 1. Project Overview & Stack
- **Domain:** Retrieval Augmented Generation (RAG) system in foundational stages.
- **Framework**: Spring Boot 3.5.0
- **Database Access: MyBatis** (NOT JPA/Hibernate)
- **Build Tool**: Maven 3.9+ (with wrapper mvnw/mvnw.cmd)
- **Language**: Java 17
- **Package**: `com.skyshift.cognitiveragengine`

## 2. Core Execution Commands
- **Compile & Validate:** `./mvnw clean compile`
- **Run Tests:** `./mvnw test`
- **Run Local App:** `./mvnw spring-boot:run`
- **Build Artifact:** `./mvnw clean package`

## 3. Project Structure

```text
cognitive_rag_engine
├─ deploy/docker-compose.yml
├─ src/main/java/com/skyshift/cognitiveragengine
│  ├─ document               # Document center
│  │	├─ controller/    # Infrastructure: REST APIs, endpoints, DTO validations
│  │	├─ service/       # Business Logic: Orchestrating agents, history, system rules
│  │	├─ model/         # Core Domain: Entities and business data objects
│  │	└─ mapper/        # Data Access: MyBatis interfaces mapping Java objects to SQL
│  ├─ ingestion              # Document ETL
│  ├─ retrieval              # PgVector / Elasticsearch retrieval
│  ├─ qa                     # RAG QA
│  │	├─ controller/    # Infrastructure: REST APIs, endpoints, DTO validations
│  │	├─ service/       # Business Logic: Orchestrating agents, history, system rules
│  │	├─ model/         # Core Domain: Entities and business data objects
│  │	└─ mapper/        # Data Access: MyBatis interfaces mapping Java objects to SQL
│  ├─ assistant              # Agent assistant
│  │	├─ controller/    # Infrastructure: REST APIs, endpoints, DTO validations
│  │	├─ service/       # Business Logic: Orchestrating agents, history, system rules
│  │	├─ model/         # Core Domain: Entities and business data objects
│  │	└─ mapper/        # Data Access: MyBatis interfaces mapping Java objects to SQL
│  ├─ storage                # MinIO storage
│  └─ common                 # Common utilities
├─ src/main/resources
│  ├─ db/migration           # Flyway migrations
│  ├─ mapper                 # MyBatis XML
│  └─ prompts                # Prompt templates
└─ docs                      # Project documentation
```

**Module Responsibilities:**
- **document**: Handles document management, CRUD operations, and metadata
- **ingestion**: ETL processes for document ingestion
- **retrieval**: Vector and text search capabilities (PgVector/Elasticsearch)
- **qa**: Question-answering logic with conversation history
- **assistant**: AI agent orchestration and workflow management
- **storage**: PgVector and MinIO object storage integration
- **common**: Shared utilities, constants, and helpers

## 4. Spring Boot 3.5.0 Coding Conventions

### 1. Core Framework & Architecture
* **Java Version:** Java 17 LTS. Use modern features like records, sealed classes, and switch pattern matching.
* **Immutability:** Default to `record` types for DTOs and configuration properties when applicable.

### 2. Dependency Injection & Configuration
* **Injection:** Always use constructor injection. Never use `@Autowired` on fields.
* **Final Fields:** Mark all injected dependencies as `final`.
* **Configuration:** Use `@ConfigurationProperties` with constructor binding. Avoid loose `@Value` annotations.

### 3. Boilerplate & Coding Style
* **Lombok:** Use Lombok annotations (`@Data`,`@Getter`, `@Setter`, `@Slf4j`) to eliminate boilerplate on standard classes.
* **Functional Programming:** Prefer Streams, `Optional`, and functional patterns over imperative loops where appropriate.

### 4. Use MyBatis conventions in Batabase (Not Spring Data JPA)
* XML mappings in `src/main/resources/mapper/`
* This means no JPA entities, `@Entity` annotations, `JpaRepository` interfaces.
* Only use MyBatis mappers with SQL in XML.

## 5. Structural RAG Guardrails
- **Spring AI First:** Maximize native Spring AI abstractions (ChatModel, VectorStore, EmbeddingModel). Do not write raw REST/HTTP clients for LLM APIs.
- **Configuration:** Maintain all application flags inside `src/main/resources/application.yaml`. Do not hardcode properties.
- **Immutable Data:** Use Java `record` types for DTOs, API request envelopes, and context payloads.
- **Validation-First:** Write integration tests alongside new endpoints or embedding pipelines using Spring Boot's internal test tools.

## 6. Think Before Coding
**Don't assume. Don't hide confusion. Surface tradeoffs.**
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them—don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 7. Simplicity First
**Minimum code that solves the problem. Nothing speculative.**
- No features beyond what was asked. No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 8. Surgical Changes
**Touch only what you must. Clean up only your own mess.**
- Don't "improve" adjacent code, comments, or formatting. Don't refactor things that aren't broken.
- Match existing style exactly, even if you would do it differently.
- Remove imports, variables, or functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked. Every changed line must trace directly to the request.

## 9. Goal-Driven Execution
**Define success criteria. Loop until verified.**
- Transform tasks into verifiable goals (e.g., "Fix the bug" → "Write a test that reproduces it, then make it pass").
- For multi-step tasks, state a brief plan up front:
  ```text
  1. [Step] → verify: [check]
  2. [Step] → verify: [check]
  ```
- Use strong success criteria to loop independently. Do not work off weak criteria like "make it work".

## 10. Entity Generation Pattern
For new entities:
1. Design-first prompt (fields, relationships, indexing)
2. 2-3 iteration rounds
3. Generate: Entity + Flyway V[N] + MyBatis Mapper interface + XML
4. /review + test
5. /compact focus on [new entity]

## 11. Project Documentation

### ReactAgent Framework Reference

For all ReactAgent related tasks, refer to the comprehensive reference:
→ `REACTAGENT_REFERENCE.md` (in project root)

#### Quick Lookup Topics:
- Agent Creation → Section "Core Building Blocks"
- Tool Definition (@Tool) → Section "Tool Definition"
- Memory & ThreadId → Section "Execution & Memory"
- Limits & Safety → Section "Limits & Safety"
- Citations Pattern → Section "Advanced Patterns"