create table assistant_conversation_summaries (
    conversation_id bigint primary key,
    summary_text text not null,
    summarized_through_sequence_number integer not null,
    updated_at timestamp not null default current_timestamp,
    constraint fk_assistant_conversation_summary_to_conversation foreign key (conversation_id) references assistant_conversations (id) on delete cascade
);