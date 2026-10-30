-- =====================================================================
-- Tablewise POS: initial schema (V1)
-- PostgreSQL 15+  ·  Supabase compatible  ·  Flyway migration
--
-- Conventions
--   * Everything lives in schema "pos" (not "public"), so Supabase's
--     auto-generated REST API does not expose it. Spring Boot connects
--     directly and owns all access.
--   * Primary keys are UUIDs (gen_random_uuid, built into PG13+), except
--     append-only logs, which use bigint identity.
--   * Every outlet-scoped row carries outlet_id (multi-outlet ready).
--   * "Enums" are text + CHECK constraints: easy to extend in a migration
--     and they map cleanly to @Enumerated(EnumType.STRING) in JPA.
--   * Money is numeric(12,2) -> BigDecimal. Stock is numeric(12,3).
--   * Timestamps are timestamptz. orders.business_date is the outlet-local
--     date, set by the application, and drives all reports.
--   * Order lines keep item name and price; bills keep the customer name.
--     History never changes when the menu or a customer record changes.
--   * orders stays narrow (what the floor and kitchen need). Money is in
--     bills, status history in order_status_events. Reports read business
--     dates through indexed views; at scale, add a daily summary table and
--     partition orders/order_items by business_date (monthly).
--   * Menu items and ingredients are soft-deleted (deleted_at).
-- =====================================================================

create schema if not exists pos;
set search_path = pos, public;

-- ---------------------------------------------------------------------
-- Shared trigger: keep updated_at current
-- ---------------------------------------------------------------------
create or replace function pos.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- =====================================================================
-- 1. TENANCY, STAFF, FLOOR, DEVICES
-- =====================================================================

create table organizations (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- One restaurant location. Holds billing settings from Admin > Restaurant and billing.
create table outlets (
  id                      uuid primary key default gen_random_uuid(),
  organization_id         uuid not null references organizations(id),
  name                    text not null,
  address                 text,
  phone                   text,
  gstin                   varchar(15),
  fssai_license_no        varchar(14),
  timezone                text not null default 'Asia/Kolkata',
  currency                char(3) not null default 'INR',
  gst_percent             numeric(5,2) not null default 5.00 check (gst_percent between 0 and 28),
  service_charge_percent  numeric(5,2) not null default 0    check (service_charge_percent between 0 and 20),
  next_order_no           integer not null default 1000,     -- incremented atomically with UPDATE ... RETURNING
  is_active               boolean not null default true,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now()
);

-- A person who works at an outlet. user_id links to Supabase auth.users once they sign in.
create table staff_members (
  id              uuid primary key default gen_random_uuid(),
  outlet_id       uuid not null references outlets(id),
  user_id         uuid,                       -- auth.users.id (FK added in the Supabase-only migration)
  full_name       text not null,
  phone           varchar(15),
  email           text,
  role            text not null check (role in ('owner','cashier','kitchen')),
  is_active       boolean not null default true,
  invited_at      timestamptz,
  joined_at       timestamptz,
  last_active_at  timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (outlet_id, user_id)
);

-- Billing counter / terminal ("This terminal: Counter 1"). Payments and customer displays attach to it.
create table counters (
  id          uuid primary key default gen_random_uuid(),
  outlet_id   uuid not null references outlets(id),
  name        text not null,
  sort_order  integer not null default 0,
  is_active   boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (outlet_id, name)
);

create table dining_tables (
  id          uuid primary key default gen_random_uuid(),
  outlet_id   uuid not null references outlets(id),
  label       varchar(10) not null,           -- "T1"
  seats       smallint not null default 4 check (seats > 0),
  area        text,                           -- "Ground floor", "Terrace"
  sort_order  integer not null default 0,
  is_active   boolean not null default true,
  created_at  timestamptz not null default now(),
  unique (outlet_id, label)
);

-- A paired screen. The raw device token is shown to the device once; only its SHA-256 is stored.
create table devices (
  id            uuid primary key default gen_random_uuid(),
  outlet_id     uuid not null references outlets(id),
  counter_id    uuid references counters(id) on delete set null,
  device_type   text not null default 'customer_display'
                check (device_type in ('customer_display','kitchen_display','pos_terminal')),
  name          text not null,
  token_hash    bytea not null unique,
  paired_by     uuid references staff_members(id),
  paired_at     timestamptz not null default now(),
  last_seen_at  timestamptz,
  revoked_at    timestamptz,
  user_agent    text,
  created_at    timestamptz not null default now()
);

-- Short-lived codes shown on an unpaired tablet. device_key is a random id the tablet generated.
create table device_pairing_codes (
  id          uuid primary key default gen_random_uuid(),
  code        varchar(6) not null check (code ~ '^[0-9]{6}$'),
  device_key  uuid not null,
  expires_at  timestamptz not null,
  claimed_at  timestamptz,
  device_id   uuid references devices(id) on delete cascade,
  created_at  timestamptz not null default now()
);
-- Only one unclaimed row per code. The app deletes expired codes before issuing new ones.
create unique index ux_pairing_code_unclaimed on device_pairing_codes(code) where claimed_at is null;
create index ix_pairing_device_key on device_pairing_codes(device_key);

-- =====================================================================
-- 2. MENU, INVENTORY, RECIPES
-- =====================================================================

create table menu_categories (
  id          uuid primary key default gen_random_uuid(),
  outlet_id   uuid not null references outlets(id),
  name        text not null,
  sort_order  integer not null default 0,
  is_active   boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (outlet_id, name)
);

create table menu_items (
  id                    uuid primary key default gen_random_uuid(),
  outlet_id             uuid not null references outlets(id),
  category_id           uuid not null references menu_categories(id),   -- RESTRICT: empty a category before deleting it
  name                  text not null,
  short_code            varchar(10),
  description           text,
  price                 numeric(10,2) not null check (price > 0),        -- before GST
  is_veg                boolean not null,
  prep_minutes          smallint not null default 10 check (prep_minutes >= 0),
  is_available          boolean not null default true,                    -- the on/off switch on the Menu screen
  gst_percent_override  numeric(5,2) check (gst_percent_override between 0 and 28),
  image_url             text,
  sort_order            integer not null default 0,
  deleted_at            timestamptz,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
create unique index ux_menu_items_name on menu_items(outlet_id, lower(name)) where deleted_at is null;
create index ix_menu_items_category on menu_items(category_id) where deleted_at is null;

create table suppliers (
  id          uuid primary key default gen_random_uuid(),
  outlet_id   uuid not null references outlets(id),
  name        text not null,
  phone       varchar(15),
  gstin       varchar(15),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (outlet_id, name)
);

-- Raw material. current_stock is maintained only by the stock_movements trigger below.
create table ingredients (
  id             uuid primary key default gen_random_uuid(),
  outlet_id      uuid not null references outlets(id),
  name           text not null,
  unit           text not null check (unit in ('kg','g','l','ml','pcs')),
  current_stock  numeric(12,3) not null default 0 check (current_stock >= 0),
  reorder_level  numeric(12,3) not null default 0 check (reorder_level >= 0),
  cost_per_unit  numeric(10,2) not null default 0 check (cost_per_unit >= 0),
  supplier_id    uuid references suppliers(id) on delete set null,
  deleted_at     timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create unique index ux_ingredients_name on ingredients(outlet_id, lower(name)) where deleted_at is null;

-- Ingredients used per portion of a dish (the "Ingredients used per portion" rows in the dish form).
create table recipe_items (
  menu_item_id     uuid not null references menu_items(id) on delete cascade,
  ingredient_id    uuid not null references ingredients(id),   -- RESTRICT: remove from recipes before deleting
  qty_per_portion  numeric(10,4) not null check (qty_per_portion > 0),
  primary key (menu_item_id, ingredient_id)
);
create index ix_recipe_items_ingredient on recipe_items(ingredient_id);

-- =====================================================================
-- 3. CUSTOMERS
-- =====================================================================

create table customers (
  id                uuid primary key default gen_random_uuid(),
  outlet_id         uuid not null references outlets(id),
  name              text not null,
  phone             varchar(15),            -- E.164, e.g. +919000010001
  email             text,
  notes             text,
  marketing_opt_in  boolean not null default false,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create unique index ux_customers_phone on customers(outlet_id, phone) where phone is not null;
create index ix_customers_name on customers(outlet_id, lower(name));

-- =====================================================================
-- 4. ORDERS
-- An order is what the kitchen and the floor work on. Money lives in
-- "bills"; status history lives in "order_status_events". That keeps this
-- table narrow and fast for the live boards.
-- =====================================================================

create table orders (
  id                 uuid primary key default gen_random_uuid(),
  outlet_id          uuid not null references outlets(id),
  order_no           integer not null,                 -- "#1172", from outlets.next_order_no
  business_date      date not null,                    -- outlet-local date, drives reports and partitioning
  order_type         text not null check (order_type in ('dine_in','takeaway','delivery')),
  source             text not null default 'pos' check (source in ('pos','swiggy','zomato','website')),
  status             text not null default 'new'
                     check (status in ('new','preparing','ready','completed','cancelled')),
  status_changed_at  timestamptz not null default now(), -- "Kitchen is preparing (for 9 min)"
  payment_status     text not null default 'unpaid' check (payment_status in ('unpaid','paid')),  -- kept in sync from bills
  table_id           uuid references dining_tables(id),
  counter_id         uuid references counters(id),
  customer_id        uuid references customers(id),
  notes              text,                             -- kitchen note ("less spicy")
  placed_at          timestamptz not null default now(),
  created_by         uuid references staff_members(id),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (outlet_id, order_no),
  constraint ck_dine_in_has_table    check (order_type <> 'dine_in' or table_id is not null),
  constraint ck_platform_is_delivery check (source = 'pos' or order_type = 'delivery'),
  -- Takeaway and delivery must be paid before they are handed over or dispatched.
  constraint ck_paid_before_handover check (
    not (status = 'completed' and order_type in ('takeaway','delivery') and payment_status <> 'paid')
  )
);
create index ix_orders_business_date on orders(outlet_id, business_date);
create index ix_orders_open on orders(outlet_id, status) where status not in ('completed','cancelled');
create index ix_orders_unpaid on orders(outlet_id) where payment_status = 'unpaid' and status <> 'cancelled';
create index ix_orders_customer on orders(customer_id);
create index ix_orders_table_open on orders(table_id) where status not in ('completed','cancelled');

create table order_items (
  id            uuid primary key default gen_random_uuid(),
  order_id      uuid not null references orders(id) on delete cascade,
  menu_item_id  uuid references menu_items(id) on delete set null,
  item_name     text not null,                 -- snapshot
  is_veg        boolean not null,              -- snapshot
  unit_price    numeric(10,2) not null check (unit_price >= 0),
  quantity      integer not null check (quantity > 0),
  line_total    numeric(12,2) not null,
  notes         text,
  created_at    timestamptz not null default now()
);
create index ix_order_items_order on order_items(order_id);
create index ix_order_items_menu_item on order_items(menu_item_id);

-- Every status change: who, when, and why (cancel reason goes in note).
-- Replaces per-status timestamp columns on orders.
create table order_status_events (
  id               bigint generated always as identity primary key,
  order_id         uuid not null references orders(id) on delete cascade,
  from_status      text,
  to_status        text not null check (to_status in ('new','preparing','ready','completed','cancelled')),
  actor_staff_id   uuid references staff_members(id),
  actor_device_id  uuid references devices(id),
  note             text,
  created_at       timestamptz not null default now(),
  constraint ck_cancel_has_reason check (to_status <> 'cancelled' or note is not null)
);
create index ix_order_status_events_order on order_status_events(order_id, created_at);

-- =====================================================================
-- 5. BILLS AND PAYMENTS
-- A bill is the financial record of an order: amounts, GST and the invoice
-- number. V1 has exactly one active bill per order; split bills later just
-- means more bills per order (drop ux_bills_one_active).
-- =====================================================================

-- GST invoice numbers must be consecutive and unique per financial year, max 16 characters.
-- Format used by the app: {prefix}/{yy-yy}/{6-digit serial}, e.g. SFT/26-27/000123 (prefix up to 3 chars).
create table invoice_sequences (
  outlet_id       uuid not null references outlets(id),
  financial_year  char(7) not null check (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),   -- '2026-27'
  prefix          varchar(3) not null,                                               -- 'SFT'
  next_number     integer not null default 1 check (next_number > 0),
  primary key (outlet_id, financial_year)
);

create table bills (
  id                      uuid primary key default gen_random_uuid(),
  outlet_id               uuid not null references outlets(id),
  order_id                uuid not null references orders(id) on delete cascade,
  status                  text not null default 'open' check (status in ('open','paid','void')),
  bill_to_name            text,                    -- snapshot printed on the bill
  bill_to_phone           varchar(15),             -- snapshot, also used by order search
  subtotal                numeric(12,2) not null check (subtotal >= 0),
  discount_type           text check (discount_type in ('flat','percent')),
  discount_value          numeric(12,2) not null default 0 check (discount_value >= 0),
  discount_amount         numeric(12,2) not null default 0 check (discount_amount >= 0),
  service_charge_percent  numeric(5,2)  not null default 0,
  service_charge_amount   numeric(12,2) not null default 0,
  taxable_amount          numeric(12,2) not null,
  gst_percent             numeric(5,2)  not null,
  cgst_amount             numeric(12,2) not null default 0,
  sgst_amount             numeric(12,2) not null default 0,
  round_off               numeric(6,2)  not null default 0,
  total_amount            numeric(12,2) not null check (total_amount >= 0),
  invoice_no              varchar(16),             -- assigned when the bill is finalised
  finalised_at            timestamptz,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  unique (outlet_id, invoice_no),
  constraint ck_discount_not_over_subtotal check (discount_amount <= subtotal),
  constraint ck_paid_bill_has_invoice check (status <> 'paid' or (invoice_no is not null and finalised_at is not null))
);
create unique index ux_bills_one_active on bills(order_id) where status <> 'void';
create index ix_bills_phone on bills(outlet_id, bill_to_phone) where bill_to_phone is not null;

-- One row per attempt. A failed UPI QR followed by cash is two rows on the same bill.
create table payments (
  id                  uuid primary key default gen_random_uuid(),
  outlet_id           uuid not null references outlets(id),
  bill_id             uuid not null references bills(id),
  method              text not null check (method in ('cash','upi_qr','card','payment_link','platform')),
  status              text not null default 'pending'
                      check (status in ('pending','succeeded','failed','expired','cancelled','refunded')),
  amount              numeric(12,2) not null check (amount > 0),
  tendered_amount     numeric(12,2),            -- cash handed over
  change_amount       numeric(12,2),
  gateway             text check (gateway in ('razorpay','card_terminal','platform')),
  gateway_qr_id       text,                     -- Razorpay qr_... id
  qr_payload          text,                     -- upi://pay?... rendered on the customer display
  expires_at          timestamptz,
  gateway_payment_id  text,                     -- Razorpay pay_... id
  gateway_reference   text,                     -- UTR / RRN / card approval code
  failure_reason      text,
  counter_id          uuid references counters(id),
  collected_by        uuid references staff_members(id),
  created_at          timestamptz not null default now(),
  completed_at        timestamptz,
  updated_at          timestamptz not null default now(),
  constraint ck_cash_fields check (method = 'cash' or (tendered_amount is null and change_amount is null))
);
create index ix_payments_bill on payments(bill_id);
create index ix_payments_outlet_time on payments(outlet_id, created_at);
-- At most one live attempt per bill, so two QR codes can never be active for the same bill.
create unique index ux_payments_one_pending on payments(bill_id) where status = 'pending';
create unique index ux_payments_gateway_payment on payments(gateway, gateway_payment_id) where gateway_payment_id is not null;
create unique index ux_payments_gateway_qr on payments(gateway_qr_id) where gateway_qr_id is not null;

-- Raw webhooks from Razorpay, card terminals and platforms. event_id makes processing idempotent.
-- Full platform payloads (items, customer, rider) stay here and are never copied into our tables.
create table gateway_webhook_events (
  id               bigint generated always as identity primary key,
  provider         text not null check (provider in ('razorpay','swiggy','zomato','website','card_terminal')),
  event_id         text not null,
  event_type       text not null,
  payload          jsonb not null,
  signature_valid  boolean not null,
  received_at      timestamptz not null default now(),
  processed_at     timestamptz,
  error            text,
  unique (provider, event_id)
);
create index ix_webhooks_unprocessed on gateway_webhook_events(received_at) where processed_at is null;

-- =====================================================================
-- 6. ONLINE ORDERS (Swiggy, Zomato, own website)
-- The platform owns the delivery: customer details, rider and tracking stay
-- with it. The outlet stores only what it decides or needs later:
--   * the accept / reject decision, made against a deadline
--   * the link to the kitchen order it created (stock, reports, KOT)
--   * the platform order id (rider lookup, payout reconciliation)
-- =====================================================================

create table platform_integrations (
  id                      uuid primary key default gen_random_uuid(),
  outlet_id               uuid not null references outlets(id),
  platform                text not null check (platform in ('swiggy','zomato','website')),
  external_store_id       text,
  is_accepting            boolean not null default true,     -- the pause switch on Online orders
  accept_window_seconds   smallint not null default 180 check (accept_window_seconds between 30 and 900),
  credentials_secret_ref  text,                               -- name of the secret in your vault, never the secret
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  unique (outlet_id, platform)
);

-- Maps a platform's item id to our menu item, so an accepted order resolves to recipes and stock.
create table platform_item_mappings (
  id                uuid primary key default gen_random_uuid(),
  integration_id    uuid not null references platform_integrations(id) on delete cascade,
  external_item_id  text not null,
  menu_item_id      uuid not null references menu_items(id),
  created_at        timestamptz not null default now(),
  unique (integration_id, external_item_id)
);

-- The inbox of orders waiting for a decision, and what was decided.
create table online_orders (
  id                 uuid primary key default gen_random_uuid(),
  outlet_id          uuid not null references outlets(id),
  integration_id     uuid not null references platform_integrations(id),
  external_order_id  text not null,                     -- what the rider quotes
  status             text not null default 'incoming'
                     check (status in ('incoming','accepted','rejected','missed','cancelled_by_platform')),
  received_at        timestamptz not null default now(),
  accept_by          timestamptz not null,              -- received_at + accept window
  prep_minutes       smallint,                          -- promised to the platform on accept
  decided_by         uuid references staff_members(id),
  decided_at         timestamptz,
  reject_reason      text,
  order_id           uuid unique references orders(id), -- kitchen order created on accept
  payload            jsonb not null,                    -- items and instructions as sent; shown on the incoming card
  created_at         timestamptz not null default now(),
  unique (integration_id, external_order_id),
  constraint ck_accepted_has_order  check (status <> 'accepted' or order_id is not null),
  constraint ck_rejected_has_reason check (status <> 'rejected' or reject_reason is not null)
);
create index ix_online_orders_incoming on online_orders(outlet_id, accept_by) where status = 'incoming';
create index ix_online_orders_external on online_orders(outlet_id, external_order_id);

-- =====================================================================
-- 7. STOCK LEDGER (after orders, because it references them)
-- =====================================================================

create table stock_movements (
  id              bigint generated always as identity primary key,
  outlet_id       uuid not null references outlets(id),
  ingredient_id   uuid not null references ingredients(id),
  quantity_delta  numeric(12,3) not null check (quantity_delta <> 0),   -- + restock, - consumption
  reason          text not null check (reason in
                  ('opening_stock','restock','order_consumption','order_cancellation','manual_correction','wastage')),
  order_id        uuid references orders(id),
  unit_cost       numeric(10,2),
  note            text,                                                  -- invoice no, supplier
  stock_after     numeric(12,3),                                         -- filled by trigger
  created_by      uuid references staff_members(id),
  created_at      timestamptz not null default now(),
  constraint ck_order_reasons_need_order check (reason not in ('order_consumption','order_cancellation') or order_id is not null)
);
create index ix_stock_movements_ingredient on stock_movements(ingredient_id, created_at desc);
create index ix_stock_movements_outlet on stock_movements(outlet_id, created_at desc);
create index ix_stock_movements_order on stock_movements(order_id) where order_id is not null;

-- Applies each movement to ingredients.current_stock in the same transaction.
-- The CHECK (current_stock >= 0) on ingredients makes an order fail if stock would go negative.
create or replace function pos.apply_stock_movement() returns trigger
language plpgsql as $$
declare
  v_after numeric(12,3);
begin
  update pos.ingredients
     set current_stock = current_stock + new.quantity_delta,
         updated_at    = now()
   where id = new.ingredient_id
  returning current_stock into v_after;
  if not found then
    raise exception 'ingredient % not found', new.ingredient_id;
  end if;
  new.stock_after := v_after;
  return new;
end $$;

create trigger trg_apply_stock_movement
  before insert on stock_movements
  for each row execute function pos.apply_stock_movement();

-- =====================================================================
-- 8. AUDIT LOG (admin edits: prices, settings, staff, devices)
-- =====================================================================

create table audit_log (
  id              bigint generated always as identity primary key,
  outlet_id       uuid references outlets(id),
  actor_staff_id  uuid references staff_members(id),
  action          text not null,           -- 'menu_item.price_changed', 'device.revoked'
  entity_type     text not null,
  entity_id       uuid,
  changes         jsonb,                   -- {"price": {"from": 300, "to": 320}}
  created_at      timestamptz not null default now()
);
create index ix_audit_log_outlet on audit_log(outlet_id, created_at desc);

-- =====================================================================
-- 9. updated_at triggers
-- =====================================================================
do $$
declare t text;
begin
  foreach t in array array['organizations','outlets','staff_members','menu_categories','menu_items','suppliers',
                           'ingredients','customers','orders','bills','payments','platform_integrations']
  loop
    execute format('create trigger trg_%1$s_updated_at before update on pos.%1$I
                    for each row execute function pos.set_updated_at()', t);
  end loop;
end $$;

-- =====================================================================
-- 10. READ VIEWS (screens and reports)
-- =====================================================================

-- Menu screen "Stock" column and POS availability: portions you can still make.
-- portions_left is null when a dish has no recipe (stock not tracked).
create view v_menu_availability as
select mi.id                      as menu_item_id,
       mi.outlet_id,
       mi.is_available,
       min(floor(i.current_stock / r.qty_per_portion))::int as portions_left,
       case
         when not mi.is_available then 'off'
         when count(r.ingredient_id) = 0 then 'untracked'
         when min(floor(i.current_stock / r.qty_per_portion)) <= 0 then 'out'
         when min(floor(i.current_stock / r.qty_per_portion)) <= 5 then 'low'
         else 'ok'
       end as stock_state
  from menu_items mi
  left join recipe_items r on r.menu_item_id = mi.id
  left join ingredients  i on i.id = r.ingredient_id
 where mi.deleted_at is null
 group by mi.id;

-- Inventory screen status pills and the dashboard "Needs attention" list.
create view v_ingredient_status as
select i.*,
       case when i.current_stock <= 0 then 'out'
            when i.current_stock <= i.reorder_level then 'low'
            else 'ok' end as stock_state,
       i.current_stock * i.cost_per_unit as stock_value
  from ingredients i
 where i.deleted_at is null;

-- Table picker "busy" state: a table is busy while it has an open or unpaid order.
create view v_table_occupancy as
select t.id as table_id, t.outlet_id, t.label,
       exists (select 1 from orders o
                where o.table_id = t.id
                  and o.status <> 'cancelled'
                  and (o.status <> 'completed' or o.payment_status = 'unpaid')) as is_busy
  from dining_tables t
 where t.is_active;

-- Reports: one row per outlet, business date and channel. Money comes from active bills.
create view v_daily_sales as
select o.outlet_id,
       o.business_date,
       case when o.source <> 'pos' then o.source
            when o.order_type = 'delivery' then 'own_delivery'
            else o.order_type end                 as channel,
       count(distinct o.id)                       as orders,
       sum(b.subtotal)                            as gross_sales,
       sum(b.discount_amount)                     as discount,
       sum(b.taxable_amount)                      as taxable_value,
       sum(b.cgst_amount)                         as cgst,
       sum(b.sgst_amount)                         as sgst,
       sum(b.total_amount)                        as total
  from orders o
  join bills b on b.order_id = o.id and b.status <> 'void'
 where o.status <> 'cancelled'
 group by 1, 2, 3;

-- Reports: dish-wise and category-wise sales.
create view v_item_sales as
select o.outlet_id,
       o.business_date,
       oi.menu_item_id,
       oi.item_name,
       mi.category_id,
       oi.is_veg,
       sum(oi.quantity)   as quantity,
       sum(oi.line_total) as sales
  from order_items oi
  join orders o on o.id = oi.order_id
  left join menu_items mi on mi.id = oi.menu_item_id
 where o.status <> 'cancelled'
 group by 1, 2, 3, 4, 5, 6;

-- Reports: payment modes, by the order's business date.
create view v_payment_mode_daily as
select o.outlet_id,
       o.business_date,
       p.method,
       count(*)      as payments,
       sum(p.amount) as amount
  from payments p
  join bills  b on b.id = p.bill_id
  join orders o on o.id = b.order_id
 where p.status = 'succeeded'
 group by 1, 2, 3;

-- Order card and kitchen timer: when each status was reached and who cancelled, from the event log.
create view v_order_timeline as
select e.order_id,
       min(e.created_at) filter (where e.to_status = 'new')        as placed_at,
       min(e.created_at) filter (where e.to_status = 'preparing')  as preparing_at,
       min(e.created_at) filter (where e.to_status = 'ready')      as ready_at,
       min(e.created_at) filter (where e.to_status = 'completed')  as completed_at,
       min(e.created_at) filter (where e.to_status = 'cancelled')  as cancelled_at,
       (array_agg(e.note) filter (where e.to_status = 'cancelled'))[1]           as cancel_reason,
       (array_agg(e.actor_staff_id) filter (where e.to_status = 'cancelled'))[1] as cancelled_by
  from order_status_events e
 group by e.order_id;

-- Customers screen.
create view v_customer_stats as
select c.id as customer_id,
       c.outlet_id,
       c.name,
       c.phone,
       count(o.id)                         as orders,
       coalesce(sum(b.total_amount), 0)    as total_spent,
       case when count(o.id) > 0 then round(sum(b.total_amount) / count(o.id), 2) end as average_bill,
       max(o.placed_at)                    as last_order_at,
       (select oi.item_name
          from order_items oi join orders o2 on o2.id = oi.order_id
         where o2.customer_id = c.id and o2.status <> 'cancelled'
         group by oi.item_name
         order by sum(oi.quantity) desc
         limit 1)                          as favourite_item
  from customers c
  left join orders o on o.customer_id = c.id and o.status <> 'cancelled'
  left join bills  b on b.order_id = o.id and b.status <> 'void'
 group by c.id;

-- =====================================================================
-- 11. ROW LEVEL SECURITY
-- Spring Boot connects as the schema owner and bypasses RLS. Enabling RLS
-- with no policies means any other role (anon/authenticated via Supabase
-- APIs) sees nothing, which is the safe default.
-- =====================================================================
do $$
declare r record;
begin
  for r in select tablename from pg_tables where schemaname = 'pos' loop
    execute format('alter table pos.%I enable row level security', r.tablename);
  end loop;
end $$;
