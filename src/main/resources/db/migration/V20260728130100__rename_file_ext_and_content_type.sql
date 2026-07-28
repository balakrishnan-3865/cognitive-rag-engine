-- Rename file_ext to file_extension for consistency with entity and mapper
alter table documents
    rename column file_ext to file_extension;

-- Rename content_type to context_type for consistency with entity and mapper
alter table documents
    rename column content_type to context_type;