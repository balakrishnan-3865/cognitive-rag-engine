# Cognitive RAG Engine - Development Progress

## Stage 01: Project Initialization & Infrastructure Layer ✅ COMPLETE

**Duration:** Initial Setup  
**Status:** ✅ Completed

### Completed Milestones

- ✅ **Feature-based Package Structure**
  - Established layered architecture (controller → service → model → mapper)
  - Core modules: `document`, `ingestion`, `retrieval`, `qa`, `assistant`, `storage`, `common`
  - Clean separation of concerns per module

- ✅ **Spring Boot 3.5.0 Dependencies Added**
  - Spring AI for RAG and LLM integration
  - Spring Web for REST API support
  - MyBatis Spring Boot for data persistence
  - PostgreSQL JDBC driver for database connectivity
  - Flyway for database schema migrations
  - MinIO SDK for object storage
  - Spring AI Alibaba agent framework
  - Lombok for boilerplate reduction

- ✅ **Docker Compose Infrastructure**
  - PostgreSQL 15 with pgvector extension for vector storage
  - MinIO for object/document storage
  - Elasticsearch for full-text search capabilities
  - Network isolation and service discovery
  - Persistent volumes for data durability

### Key Files Established

- `pom.xml` — Maven dependencies and build configuration
- `docker-compose.yml` — Containerized data dependencies (PostgreSQL, MinIO, Elasticsearch)
- `src/main/java/com/skyshift/cognitiveragengine/` — Modular package structure
- `src/main/resources/application.yaml` — Configuration template (requires environment-specific values)

### What's Working

- Local development environment can be spun up with `docker-compose up`
- Maven build toolchain validated (`./mvnw clean compile`)
- Java 17 + Spring Boot 3.5.0 baseline operational

---

## Stage 02: Core Data Layer & Domain Models

**Status:** Phase 2.1 ✅ COMPLETE | Phase 2.2 Ready to Start  
**Focus:** Database schema, migrations, and foundational domain entities. Upload Multipart file to Minio --> save document metadata to DocumentEntity table --> Publish Async event processing.

### Phase 2.1: Multi-part API & MinIO Storage Registry ✅ COMPLETE

**Duration:** Stage 02  
**Status:** ✅ Completed

#### Completed Implementation

- [x] Create `DocumentEntity` with the required fields
- [x] Create `DocumentMapper` interface and the MyBatis xml implementation
- [x] Implement `MinioStorageService` for uploading raw streams
- [x] Build `DocumentController` exposing `POST /api/v1/documents/upload` accepting MultipartFile + groupId
- [x] Add comprehensive file validation: size, extension, path traversal prevention
- [x] Publish `DocumentUploadedEvent` via `ApplicationEventPublisher`
- [x] Create `DocumentService` orchestrating upload → save → event publish with compensating transactions
- [x] Create `DocumentUploadProperties` with environment-based configuration
- [x] Implement `FileValidator` with industry-standard path traversal prevention (StringUtils.cleanPath)
- [x] Create `DocumentIngestionAsyncListener` with @Async @TransactionalEventListener(AFTER_COMMIT)
- [x] Enable async processing with `AsyncConfiguration` and thread pool configuration
- [x] Create standard `ApiResponse<T>` wrapper for all API responses
- [x] Implement custom exception handling with `DocumentUploadException`

#### Completed Artifacts

**Core Components:**
- `DocumentController` — REST endpoint `/api/v1/documents/upload` (POST)
- `DocumentService` — Orchestrates file validation, MinIO upload, DB persistence, event publishing
- `DocumentUploadRequest` — Multipart file + groupId binding record
- `DocumentUploadedEvent` — Domain event (documentId, groupId)
- `DocumentUploadProperties` — Configuration with validation & environment variables
- `FileValidator` — Comprehensive file validation (size, extension, filename normalization)
- `DocumentIngestionAsyncListener` — Async event processor with AFTER_COMMIT transactional safety
- `AsyncConfiguration` — Enables @Async annotation processing
- `ApiResponse<T>` — Standard API response wrapper

**Configuration:**
- Thread pool configured (core: 5, max: 10, queue: 100)
- Environment variables: `DOCUMENT_UPLOAD_MAX_FILE_SIZE`, `DOCUMENT_UPLOAD_MAX_FILENAME_LENGTH`, etc.
- `.env.example` updated with default values
- `application.yaml` updated with document upload config + async thread pool

**Database:**
- `DocumentEntity` — Existing, no modifications required
- `DocumentMapper` XML — Existing, no modifications required
- Flyway migration — Existing, no modifications required

#### File Storage Structure (MinIO)

```
minio-bucket/
└── groups/{groupId}/users/{userId}/{fileId}.{extension}
    Example: groups/12/users/5/a1b2c3d4e5f6a7b8.pdf
```

#### Key Features

✅ **Security**: Path traversal prevention using StringUtils.cleanPath()  
✅ **Validation**: File size, extension type, filename length checks  
✅ **Transactions**: Compensating rollback if DB insertion fails (deletes from MinIO)  
✅ **Async Processing**: Non-blocking event-driven ingestion pipeline  
✅ **ACID Compliance**: @TransactionalEventListener(AFTER_COMMIT) ensures data consistency  
✅ **Configuration Management**: Environment-based overrides with sensible defaults  
✅ **Error Handling**: Specific error codes for debugging and client-side handling  

**Phase 2.1 Exit Checklist:**
- [x] `POST /api/v1/documents/upload` accepts file + groupId → returns 201 Created with documentId
- [x] File uploads to MinIO bucket with hierarchical path (groups/userId/groupId)
- [x] Document record saves to `documents` table with status `PENDING`
- [x] Event publishes successfully only after DB commit (AFTER_COMMIT phase)
- [x] Validation rejects invalid files (wrong type, too large, malformed paths)
- [x] Compensating transaction deletes from MinIO if DB insertion fails
- [x] Async listener processes event asynchronously without blocking API response
- [x] Proper error responses with specific error codes for client handling

---

### Phase 2.2: Document Parsing, Chunking & Vector Ingestion Pipeline

**Status:** Phase 2.2.1 ✅ COMPLETE | Phase 2.2.2 ⏳ Ready to Start  
**Duration:** 3 Sessions (Session 1 Complete, 2-3 remaining)  
**Scope:** Implements production-grade document ingestion with parsing, chunking, embedding, and search indexing
**Current Achievement:** PDFs → Chunks → Stored in document_chunks table

---

#### Phase 2.2.1: Parser Infrastructure & Chunking Foundation ✅ COMPLETE

**Duration:** Session 1  
**Status:** ✅ Completed  
**Focus:** Document parsing, fixed-size chunking (300 tokens, 20% overlap), batch insertion to document_chunks table

**Completed Implementation:**

✅ **Dependencies Added**
- Apache PDFBox 3.0.1 (PDF text extraction)
- Apache Commons Text 1.11.0 (text utilities)
- Jackson Datatype JSR310 (LocalDateTime JSON serialization)

✅ **Parser Infrastructure**
- `DocumentParser` interface with `parse(InputStream): List<Document>`
- `PdfDocumentParser` implementation using PDFBox with page-by-page extraction
- `DocumentParserFactory` with strategy pattern (auto-wires parser implementations)

✅ **Chunking & Tokenization**
- `ChunkingService` using Spring AI's `TokenTextSplitter`
  - Fixed size: 300 tokens per chunk
  - Overlap: 20% (60 tokens between chunks)
  - Returns `List<DocumentChunkEntity>` with structured metadata

✅ **Metadata Capture**
- `ChunkMetadata` DTO with:
  - `pageNumber` (PDF page reference)
  - `tokenCount` (for cost tracking)
  - `source` (original filename)
  - `confidenceScore` (future OCR quality)
  - `chunkStrategy` (strategy identifier)
  - Serialized to JSON in `metadataJson` column

✅ **Batch Insertion & Idempotency**
- `DocumentChunkBatchService` implementing idempotency:
  - DELETE existing chunks (clean slate on retry)
  - Batch INSERT new chunks into `document_chunks` table
  - Auto-generated IDs populated by database

✅ **Orchestration & Events**
- `ParseAndChunkService` (TX1 boundary):
  - Fetches DocumentEntity by ID
  - Implements idempotency guard (skip if not PENDING status)
  - Updates document status to PROCESSING
  - Orchestrates parser → chunker → batch insert flow
  - Captures failure reason on exception
  
- Updated `DocumentIngestionAsyncListener`:
  - Listens for `DocumentUploadedEvent`
  - Calls `ParseAndChunkService.parseAndChunkDocument()`

✅ **Document Reader**
- `DocumentIngestionDocumentReader` (per-request, not @Component):
  - Implements Spring AI `DocumentReader` interface
  - Fetches from `ObjectStorageService` (abstraction for MinIO/NoOp)
  - Uses `DocumentParserFactory` to select parser
  - Returns `List<Spring AI Document>`

✅ **Database Mapping**
- Added methods to `DocumentMapper`:
  - `selectById(documentId)`
  - `updateStatus(documentId, status)`
  - `updateStatusAndReason(documentId, status, failureReason)`

- Added methods to `DocumentChunkMapper`:
  - `batchInsertChunks(chunks)`
  - `deleteByDocumentIdAndGroupId(documentId, groupId)`
  - `selectByDocumentIdAndGroupId(documentId, groupId)`
  - `selectByDocumentIdAndGroupIdWithPagination()` (for Phase 2.2.2)

✅ **Configuration**
- `application.yaml` ingestion section:
  - `chunk-size-tokens: 300`
  - `chunk-overlap-percentage: 20`
  - Batch sizes for embedding and Elasticsearch

✅ **Status Enum**
- `DocumentStatus`: PENDING → PROCESSING → READY (or FAILED)

**Phase 2.2.1 Exit Checklist - ALL COMPLETE:** ✅
- [x] PDF documents parsed to Spring AI Document objects
- [x] 300-token chunks with 20% overlap created and verified
- [x] Chunks batch-inserted into `document_chunks` table with auto-generated IDs
- [x] Idempotency guard prevents duplicate chunks on event replay
- [x] Document status updated to PROCESSING during ingestion
- [x] Structured metadata captured as JSON in `metadataJson` column
- [x] Document failure reason captured on parse errors
- [x] Async listener integrated with ParseAndChunkService
- [x] Transaction TX1 boundary isolated and atomic

**Key Deliverables**
- ✅ PDFs → chunks in document_chunks table
- ✅ 300-token fixed-size chunks with 20% overlap
- ✅ Structured metadata with pageNumber, tokenCount, source, confidence
- ✅ Idempotency via DELETE + INSERT pattern
- ✅ Async event-driven ingestion
- ✅ Status tracking (PENDING → PROCESSING)

---

#### Phase 2.2.2: Async Orchestration & Vector Embedding ✅ COMPLETE

**Duration:** Session 2-3  
**Status:** ✅ Completed  
**Focus:** Implement TX2 vector embedding (isolated transaction), batch embedding calls, TX3 Elasticsearch indexing, async event-driven orchestration with transactional safety

**Completed in Session 2-3:**

✅ **VectorIngestionService Implementation** (pre-existing, integrated)
- [x] Idempotent delete-before-insert pattern with pgvector
- [x] Metadata construction with column-field precedence
- [x] Batch processing with configurable batch size
- [x] Comprehensive test coverage

✅ **Event-Driven Async Orchestration**
- [x] Event record created for post-chunk trigger
- [x] Transactional event listener with AFTER_COMMIT phase:
  - Ensures chunks are committed before async tasks begin
  - Decouples parse/chunk transaction from vector/Elasticsearch stages
  - Listener marked @Async for non-blocking execution in thread pool
- [x] Async method executes in dedicated thread pool (core: 5, max: 10)

✅ **TX2: Vector Embedding (Isolated Transaction)**
- [x] Separate transaction boundary (REQUIRES_NEW propagation)
- [x] Fetches chunks from database by documentId & groupId
- [x] Calls vector embedding service for pgvector storage
- [x] Returns early if no chunks found (graceful handling)
- [x] Throws BusinessException on embedding failures (proper error propagation)

✅ **TX3: Elasticsearch Indexing (Non-Transactional)**
- [x] No transaction boundary (eventually-consistent model)
- [x] Fetches chunks and retrieves document filename
- [x] Calls Elasticsearch indexing service for sparse search
- [x] Logs failures without blocking document availability (pgvector is primary)
- [x] Non-blocking exception handling preserves document READY status

✅ **Status Management**
- [x] Document automatically marked READY after both vector and ES stages complete
- [x] Failure reasons captured with stage-specific context
- [x] Status transitions: PENDING → PROCESSING → READY (or FAILED on error)

✅ **Integration Points**
- [x] Parse and chunk service publishes event after chunk commit
- [x] Event listener coordinates async orchestration
- [x] Orchestrator manages all 3 transaction boundaries internally
- [x] VectorIngestionService integrated for pgvector storage
- [x] ElasticsearchChunkIndexService (pre-existing) integrated for sparse indexing

**Phase 2.2.2 Exit Checklist:** ✅ ALL COMPLETE
- [x] Document status correctly transitions: PENDING → PROCESSING → READY (success)
- [x] Document status correctly transitions: PENDING → PROCESSING → FAILED (error)
- [x] Event published only after chunks committed (AFTER_COMMIT phase)
- [x] Chunks visible in database before vector ingestion attempts to read them
- [x] Failure reason captured with detailed error message and stage context
- [x] All 3 transaction stages execute in correct order (TX1 → Event → TX2 → TX3)
- [x] TX1 atomic: chunks committed or rolled back completely
- [x] TX2 isolated: vector failures don't affect chunk persistence
- [x] TX3 non-blocking: ES failures don't prevent document availability
- [x] End-to-end flow: Upload PDF → Parse → Chunk → Embed → Index → READY
- [x] Async execution non-blocking: API returns immediately after chunk commit
- [x] Thread pool configured for async task execution

**Architectural Achievement:** ✅
- [x] Event-driven decoupled architecture with transactional safety
- [x] Multi-boundary transaction strategy for resilience
- [x] AFTER_COMMIT phase ensures data visibility before async operations
- [x] Graceful degradation: pgvector primary, Elasticsearch optional
- [x] Production-ready error handling and observability

---

#### Phase 2.2.3: Integration Testing & Observability ⏳ READY TO START

**Duration:** Session 4  
**Focus:** End-to-end integration tests, observability instrumentation, failure scenario validation

**Tasks:**
- [ ] Create integration tests for full pipeline:
  - Upload PDF → verify status progression (PENDING → PROCESSING → READY)
  - Verify chunks in DocumentChunkEntity
  - Verify vectors in pgvector with correct dimensions (768)
  - Verify sparse indices in Elasticsearch

- [ ] Add observability:
  - Log chunk count, token count, embedding dimensions
  - Log stage transitions and timing information
  - Metrics: documents processed, avg chunks per doc, embedding latency, ES indexing latency

- [ ] Failure scenario tests:
  - Network timeout during vector embedding → verify retry mechanism
  - Unsupported document format → verify immediate failure (no retry)
  - Elasticsearch unavailable → verify document still marked READY (pgvector primary)

- [ ] Create smoke test:
  - Upload small PDF, verify READY status within reasonable time
  - Query pgvector for similar chunks
  - Query Elasticsearch for keyword matches

- [ ] Documentation update:
  - Endpoint reference: `/api/v1/documents/upload` returns 201 with documentId
  - Status polling: `/api/v1/documents/{id}` shows PENDING/PROCESSING/READY/FAILED
  - Error codes: document not found, unsupported format, processing failed

**Phase 2.2.3 Exit Checklist:**
- [ ] End-to-end integration test: PDF → Chunks → Vectors → Ready
- [ ] Elasticsearch connectivity validated with actual index operations
- [ ] pgvector stores vectors with correct dimensions (768 for nomic-embed-text)
- [ ] Dense search via pgvector functional
- [ ] Sparse search via Elasticsearch functional
- [ ] Retry mechanism tested and working
- [ ] Failure scenarios handled gracefully
- [ ] All logs clean and informative
- [ ] Document status queryable via API

**Stage 2 Complete** → Checkpoint: Documents ingested, chunked, embedded, indexed, ready for retrieval

---

## Development Notes

- **Build:** `./mvnw clean compile` / `./mvnw test` / `./mvnw spring-boot:run`
- **Local Infrastructure:** `docker-compose up -d` (from `deploy/` directory)
- **Java Version:** Java 17 LTS (modern features: records, sealed classes, pattern matching)
- **Code Style:** Constructor injection, immutable records, Lombok annotations, functional streams
- **Testing:** Integration tests via Spring Boot Test; mock external services

---

## Commit History

- **Stage 02.1** — Document upload to MinIO with metadata persistence and async ingestion
  - Implemented DocumentController with multipart file upload endpoint
  - Created DocumentService with compensating transactions
  - Added FileValidator with path traversal prevention
  - Implemented DocumentIngestionAsyncListener with @Async @TransactionalEventListener
  - Configured thread pool for async document ingestion processing
  - Added comprehensive file validation and error handling
  
- `085a8ae` — Add default MinIO bucket and getDefaultBucket
- `3d457f1` — Add MinIO object storage & NoOp fallback
- `980cb0b` — Add document entities, mappers, migrations, docs
- `fb25d3f` — Add Elasticsearch and ElasticVue services
- `d117bed` — Initialize Spring Boot 3 RAG project infrastructure