-- =====================================================================
-- Tablewise POS (V2): link staff to Supabase Auth users.
-- Runs safely on plain Postgres too: it only adds the foreign key when the
-- Supabase "auth.users" table exists (local Docker Postgres skips it).
-- =====================================================================
do $$
begin
  if exists (select 1 from information_schema.tables
              where table_schema = 'auth' and table_name = 'users') then
    alter table pos.staff_members
      add constraint fk_staff_members_auth_user
      foreign key (user_id) references auth.users(id) on delete set null;
  end if;
end $$;
