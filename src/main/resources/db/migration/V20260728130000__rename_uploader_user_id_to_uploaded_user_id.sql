-- Rename uploader_user_id to uploaded_user_id for consistency with entity and mapper
alter table documents
    rename column uploader_user_id to uploaded_user_id;

-- Drop old index
drop index idx_documents_uploader_user;

-- Create new index with consistent naming
create index idx_documents_uploaded_user on documents(uploaded_user_id);