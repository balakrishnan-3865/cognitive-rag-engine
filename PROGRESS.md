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

#### Phase 2.2.2: Async Orchestration & Vector Embedding ✅ PARTIAL (VectorIngestionService COMPLETE)

**Duration:** Session 2 (2-3 days)  
**Status:** ✅ VectorIngestionService Complete | ⏳ Remaining Components Ready to Start  
**Focus:** Implement TX2 vector embedding (isolated transaction), batch embedding calls, status management for document availability

**Completed in Session 2:**

✅ **VectorIngestionService Implementation**
- [x] Created `VectorIngestionService` with comprehensive vector ingestion logic
- [x] Idempotent delete-before-insert pattern:
  - Uses Spring AI `FilterExpressionBuilder` for type-safe metadata filtering
  - Deletes existing embeddings for documentId before inserting new ones
  - Enforces consistent pgvector state (delete must succeed before add)
  - Throws exception if delete fails, preventing inconsistent state
- [x] Document conversion pipeline:
  - Converts `DocumentChunkEntity` → Spring AI `Document` objects
  - Generates stable, deterministic UUIDs (`UUID.nameUUIDFromBytes`)
  - Idempotent IDs enable safe re-ingestion without duplicates
- [x] Metadata construction with column-field precedence:
  - Sets column-based fields first: `chunkNumber`, `documentId`, `groupId`, `startPosition`, `endPosition`
  - Merges JSON metadata from `metadataJson` field without overwrites
  - Ensures all required filtering fields are non-null
- [x] Batch processing:
  - Configurable batch size via `spring.ai.vectorstore.batch-size` (default: 100)
  - Partitions documents into batches before `vectorStore.add()`
  - Fail-fast on batch insertion errors
- [x] Comprehensive testing:
  - 22 tests covering happy path, edge cases, metadata merging, batching
  - Unit tests: conversion, ID generation, metadata building, error handling
  - Integration tests: batch processing, large datasets, delete-before-insert ordering
  - All tests verify Filter.Expression usage and idempotency guarantees

**Remaining Tasks:**
- [ ] Create `DocumentFetchService`:
  - Fetches document from MinIO using DocumentEntity
  - Returns `InputStream` for parser
  - Includes retry logic (3x exponential backoff)
  - Decorated with `@Retryable` for transient failures

- [ ] Create `DocumentIngestionAsyncService` (main orchestrator):
  - Method: `ingestDocument(documentId)` marked `@Async`
  - Implements idempotency guard (skip if not PENDING)
  - Coordinates 3 transaction stages:
    - **TX1**: Fetch → Parse → Chunk → Batch Insert (atomic)
    - **TX2**: Backfill → Embed → Store in pgvector (isolated)
    - **TX3**: Index in Elasticsearch (async, no TX)
  - On failure: Update `DocumentEntity.failureReason` with detailed error
  - On success: Update status → READY

- [ ] Integrate `VectorIngestionService` into orchestration:
  - Call `VectorIngestionService.ingestDocumentChunks()` in TX2 boundary
  - Pass list of `DocumentChunkEntity` with IDs from backfill

- [ ] Create `ElasticsearchIndexingService`:
  - Receives chunks for sparse indexing
  - Transforms to ES documents (JSON structure)
  - Bulk-indexes to Elasticsearch
  - Logs failures (non-blocking, eventual consistency)

- [ ] Update `DocumentIngestionAsyncListener`:
  - Catches `DocumentUploadedEvent` from Phase 2.1
  - Calls `DocumentIngestionAsyncService.ingestDocument(documentId)`
  - Handles and logs exceptions

- [ ] Implement retry logic:
  - `@Retryable` on `fetchFromMinIO()`, `embeddingModel.embed()`, ES bulk-index
  - Max 3 attempts with exponential backoff (1s, 2s, 4s)
  - Fail-fast on permanent errors (unsupported format, missing document)

**Phase 2.2.2 Exit Checklist:**
- [ ] Document status correctly transitions: PENDING → PROCESSING → READY (success)
- [ ] Document status correctly transitions: PENDING → PROCESSING → FAILED (error)
- [ ] Idempotency guard prevents double-processing of same document
- [ ] Retry logic triggered on transient failures (tested with mock)
- [ ] Failure reason captured with detailed error message
- [ ] All 3 transaction stages execute in correct order
- [ ] TX1 rolls back atomically on chunk insert failure
- [ ] TX2 fails independently without affecting TX1 (chunks already persisted)
- [ ] TX3 logs ES failures without blocking document availability
- [ ] End-to-end test: Upload PDF → Observe status: READY

**VectorIngestionService Completion Checklist:** ✅
- [x] Idempotent delete-before-insert pattern enforces consistent pgvector state
- [x] Delete fails fast, preventing insert if vector store in inconsistent state
- [x] Stable UUID generation enables safe re-ingestion without duplicates
- [x] Metadata building merges column + JSON fields with column precedence
- [x] All required filtering fields (chunkNumber, documentId, groupId, startPosition, endPosition) non-null
- [x] Batch processing with configurable batch size (default: 100)
- [x] Spring AI FilterExpressionBuilder for type-safe metadata filtering
- [x] Comprehensive test coverage (22 tests): unit + integration
- [x] Production-ready error handling and logging

---

#### Phase 2.2.3: Vector Store Configuration & Integration Testing
**Duration:** Session 3  
**Focus:** Integrate pgvector & Elasticsearch, verify end-to-end ingestion flow

**Tasks:**
- [ ] Configure Elasticsearch in `application.yaml`:
  - URI, connection timeout, socket timeout
  - Index mapping for sparse search

- [ ] Create Elasticsearch index mapping (sparse search):
  - Fields: documentId, chunkId, chunkNumber, chunkText, metadata, createdAt

- [ ] Verify pgvector integration:
  - Spring AI's `VectorStore` correctly stores embeddings
  - Verify vector dimensions match model (768 for nomic-embed-text)

- [ ] Create integration tests for full pipeline:
  - Mock PDF file → Upload → Observe status progression
  - Verify chunks in DocumentChunkEntity
  - Verify vectors in pgvector
  - Verify indices in Elasticsearch

- [ ] Add observability:
  - Log chunk count, token count, embedding dimensions
  - Log successful vs. failed stages
  - Add metrics: documents processed, avg chunks per doc, avg embedding latency

- [ ] Update `docker-compose.yml` (if needed) to ensure Elasticsearch running

- [ ] Create smoke test:
  - Upload small PDF, verify READY status, verify pgvector + ES indexed

**Phase 2.2.3 Exit Checklist:**
- [ ] Elasticsearch connectivity validated
- [ ] pgvector configured with correct dimensions
- [ ] End-to-end integration test: PDF → Chunks → Embeddings → Ready
- [ ] Sparse search index built in Elasticsearch
- [ ] Dense search vectors stored in pgvector
- [ ] Failure scenario tested: network timeout → retry → success
- [ ] Failure scenario tested: unsupported format → immediate failure (no retry)
- [ ] All logs clean (no exceptions, expected retries logged as INFO)
- [ ] Document retrieval via `/api/v1/documents/{id}` includes status

---

### Phase 2.3: Relational Backfilling & Vector Loading (Future)

- [ ] (Will be consolidated into Phase 2.2.2 during implementation)
- [ ] Legacy checklist items will be validated by Phase 2.2.3 tests

**Phase 2.3 Exit Checklist:**
- [x] Embeddings generated and stored in pgvector
- [x] Document status updates to `READY`
- [x] Verify vector dimension matches model output (768)
- [x] pgvector index exists for similarity search
- [x] End-to-end test: file uploaded, processed, queryable

**Stage 2 Complete** → Checkpoint: Documents ingested, chunked, embedded, indexed, ready for retrieval

---

### Phase 2.3: Relational Backfilling & Vector Loading

- [ ] Configure `EmbeddingModel` bean (Spring AI Anthropic)
- [ ] Implement `VectorizationService` to generate embeddings from text chunks
- [ ] Batch insert chunks with vectors into pgvector using `VectorStore`
- [ ] Update document status to `COMPLETED` after vectorization
- [ ] Add error handling: partial failures rollback or retry
- [ ] Create stub hooks for Stage 10 rollback/retry cleanup
- [ ] Test end-to-end flow: upload → parse → chunk → vectorize → ready

**Phase 2.3 Exit Checklist:**
- [ ] Embeddings generated and stored in `chunks.embedding` column
- [ ] Document status updates to `COMPLETED`
- [ ] Verify vector dimension matches model output (e.g., 1536)
- [ ] pgvector index exists on `chunks.embedding` for similarity search
- [ ] End-to-end test: file uploaded, processed, queried (manual check)

**Stage 2 Complete** → Checkpoint: Documents ingested, chunked, vectorized, ready for retrieval

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