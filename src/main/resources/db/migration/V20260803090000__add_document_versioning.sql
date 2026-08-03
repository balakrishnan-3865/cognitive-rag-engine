ALTER TABLE documents
    ADD COLUMN root_document_id bigint NULL REFERENCES documents (id),
    ADD COLUMN version_number int NOT NULL DEFAULT 1,
    ADD COLUMN is_current_version boolean NOT NULL DEFAULT true;

CREATE INDEX idx_documents_root_current ON documents (root_document_id) WHERE is_current_version = true;
CREATE INDEX idx_documents_group_ready_current ON documents (group_id) WHERE status = 'READY' AND is_current_version = true;
