alter table users
    add column role varchar(20) not null default 'USER',
    add column last_login_at timestamp null;

alter table users
    add constraint chk_users_role check (role in ('ADMIN', 'USER'));
