alter table refresh_tokens
    add column previous_token_hash varchar(255) null;
