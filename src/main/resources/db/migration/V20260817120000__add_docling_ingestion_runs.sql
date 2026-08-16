CREATE TABLE document_ingestion_runs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents (id),
    docling_task_id TEXT,
    status TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_document_ingestion_runs_document_id ON document_ingestion_runs (document_id);

ALTER TABLE document_chunks ADD COLUMN ingestion_run_id BIGINT;
ALTER TABLE document_chunks ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT false;

-- Backfill: attribute any pre-existing chunk rows to a synthetic completed run so the
-- NOT NULL constraint below can be applied without losing existing data.
INSERT INTO document_ingestion_runs (document_id, status, completed_at)
SELECT DISTINCT document_id, 'CUTOVER_COMPLETE', CURRENT_TIMESTAMP
FROM document_chunks
WHERE ingestion_run_id IS NULL;

UPDATE document_chunks dc
SET ingestion_run_id = r.id,
    is_current = true
FROM document_ingestion_runs r
WHERE dc.ingestion_run_id IS NULL
  AND r.document_id = dc.document_id
  AND r.status = 'CUTOVER_COMPLETE';

ALTER TABLE document_chunks ALTER COLUMN ingestion_run_id SET NOT NULL;

ALTER TABLE document_chunks
    ADD CONSTRAINT fk_document_chunk_ingestion_run FOREIGN KEY (ingestion_run_id) REFERENCES document_ingestion_runs (id);

ALTER TABLE document_chunks
    ADD CONSTRAINT uk_document_chunk_run_number UNIQUE (document_id, group_id, ingestion_run_id, chunk_number);

CREATE INDEX idx_document_chunk_ingestion_run_id ON document_chunks (ingestion_run_id);
