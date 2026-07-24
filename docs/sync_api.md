# Berilo sync API contract — v1.1 (post-review)

> Implementation-ready contract for `berilo-cloud` (private repo). This
> document is the boundary artifact: the private repo implements *from* this
> file; this repo never gains service-side code. See CLAUDE.md §2/§4 and
> `docs/project_spec.md` §6 for the architectural rules this contract must
> satisfy (BYO-key stays out of scope here — sync carries no LLM keys).
>
> **Verification scope.** This file is the S3.1 deliverable and is verified
> by review in *this* repo. The RLS audit (user A gets 0 rows of user B
> across every table) and the synthetic 2500-row pagination-completeness
> test run against the live Supabase project in `berilo-cloud` — that half
> of S3.1's Verify line is Supervisor/infra-gated, not exercised here.
>
> **v1.1 note.** `berilo-cloud` has not implemented against this contract
> yet, so this is a pre-implementation revision fixing gaps found in
> plan-critic review (F1–F13 below), not a breaking change to a deployed
> API. The wire version stays `/api/v1/*` (§6).

## 1. Overview

### 1.1 Architecture

```
Android app ─┐                                    ┌─ Supabase Postgres
             ├─ Bearer <Clerk JWT> ─► Next.js API ─┤   (RLS, third-party
Web app    ──┘        (Vercel)         (thin)      └─   auth = Clerk JWKS)
```

- **Auth:** Clerk issues the session JWT on both clients. The Next.js API
  route verifies the JWT (signature, expiry, issuer) then executes Supabase
  queries through a request-scoped client carrying that JWT, so **Postgres
  RLS — not application code — is the isolation boundary** (Supabase
  third-party auth: Supabase validates the same Clerk JWT against Clerk's
  JWKS and exposes the subject claim as `auth.jwt() ->> 'sub'`). The API
  layer never uses the service-role key for user-scoped reads/writes.
- **Sync model:** pull/push, cursor-based, **last-write-wins on `updated_at`
  (UTC)** — see §1.3. Deletes are tombstones (`deleted_at` set, row kept),
  never hard deletes, so a delete on one device propagates to every other
  device and to the web app on the next pull. Hard deletion (GDPR-style
  erasure) is an out-of-band admin operation, not part of this contract.
- **Privacy boundary:** book *files* never leave the device/machine — only
  user-created data (highlights/notes, vocabulary, progress) and book
  *metadata* (title, authors, content hash, language) sync. The public
  reading page (§6.1 of the spec) additionally publishes only what a user
  explicitly opts in, per item.

### 1.2 Entity ⇄ Android-entity mapping

| Server table | Android Room entity | Notes |
|---|---|---|
| `profiles` | — (no local entity; account-level) | Clerk user id is the PK |
| `books_metadata` | `BookEntity` | `content_hash` = `BookEntity.id` |
| `highlights` | `HighlightEntity` | one row serves both highlights and notes — `note` is nullable, matching Android's merged model; see **[OPEN-4]** re: soft-delete/outbox |
| `vocabulary` | `DictionaryEntryEntity` | see **[OPEN-1]** (raw sentence) and **[OPEN-4]** (updatedAt) below |
| `progress` | `BookEntity.progressionJson` / `lastOpenedAt` | device remains source of truth for reading position (spec §6); sync is best-effort cross-device continuity only |
| `shelves` / `shelf_items` | — (new, web/social-layer only) | not currently modeled in `android/`; S3.4 territory |
| `ratings` | — (new, web/social-layer only) | |
| `shared_passages` | derived from `HighlightEntity` at share time | snapshot, not a live join |

`InterpretationEntryEntity` (paragraph interpretation cache) is **not**
synced — spec's Phase 3 sync list is "notes, highlights, vocabulary,
progress" only (`project_spec.md` §6); interpretation cache stays local.

**[OPEN-1]** `DictionaryEntryEntity` currently stores `sentenceHash` (a
stable hash) but not the raw sentence text. The `vocabulary` table below
needs the raw `sentence` for web-review display (searchable/readable
context), not just its hash. Either (a) `DictionaryEntryEntity` gains a
`sentence: String` column before S3.2 pushes this shape, or (b) the web
review app displays only `word`/`definition`/`context_meaning` without the
raw sentence. Recommend (a) — flagging for Niko rather than deciding
unilaterally since it touches an already-shipped Android entity.

**[OPEN-2]** `books_metadata` needs `source_lang`/`target_lang` for the
spec's "language pair" on shelves (§6.1), but `BookEntity` doesn't persist
language today (only `title`/`authors`). Columns are included below as
nullable so the contract doesn't block on it; S3.2's sync client should
backfill from the EPUB's `dc:language` / app settings when it starts
pushing `books_metadata` rows.

**[OPEN-3]** Privacy granularity for shelves: this contract makes a whole
**shelf** public/private (`shelves.is_public`), not each book on it
individually, on the reading that spec §6.1's "per item" opt-in most
naturally means *per shared item* (a shelf, a rating, a passage) rather
than per-book-within-a-shelf. If Niko wants per-book granularity on a
shelf, `shelf_items` gains its own `is_public` instead — flagging rather
than guessing, since it changes the RLS policy shape below.

### 1.3 Sync model

- **Last-write-wins:** every synced row carries `updated_at timestamptz`.
  On push, the server applies an incoming write only if
  `incoming.updated_at >= current.updated_at` (ties favor the incoming
  write — the pushing device just made it). Otherwise the push is rejected
  as a `conflict` and the response carries the server's current row so the
  client overwrites its local copy. Clients supply `updated_at` from the
  device's own UTC clock; the server does not reconcile clock skew beyond
  this comparison — see **[OPEN-5]** (§7), this is a known data-loss risk,
  not fully solved by v1.
- **Offline queue:** the app queues local mutations and replays them
  through `/sync/push` in `updated_at` order on reconnect; §2 batches are
  designed to make one queue flush a small number of requests.
- **Tombstones and delete-wins:** `deleted_at timestamptz` on every synced
  table except `progress` (see below). A pull response includes tombstoned
  rows (so a receiving device deletes its local copy) until an out-of-band
  retention job purges them — no retention window is fixed by this
  contract; `berilo-cloud` may add one without breaking clients since
  tombstone presence, not absence, is what clients rely on. **Once
  `deleted_at` is set, a plain `upsert` can never clear it** — pure LWW-by-
  timestamp would let a queued edit that was created *before* a delete (but
  replayed *after* it, e.g. a device that was offline through both events)
  silently resurrect a tombstoned row. Deletion beats a same-or-later
  concurrent upsert by design; resurrecting a row requires the push item to
  set `undelete: true` explicitly (§3's `/sync/push` schema), which the
  server still subjects to the normal `updated_at` LWW check against the
  tombstone. This is enforced at the database layer, not just in API code:
  every tombstone-bearing table carries an `enforce_delete_wins` trigger
  (§2) that raises unless the request set `berilo.allow_undelete = true`
  for that transaction (the push handler sets it only when the item's
  `undelete` flag is `true`).
- **`progress` is upsert-only.** It has no `deleted_at` column and no
  delete operation — a reading-position row is simply overwritten as the
  reader advances; there is no "un-reading" a book. `op: delete` on
  `entity: progress` is rejected with `validation_error`.
- **Client-generated ids.** Every uuid-keyed entity (`highlights`,
  `shelves`, `shelf_items`, `shared_passages`) requires the client to
  generate and supply `id` in its `/sync/push` item — the server never
  relies on the DDL's `gen_random_uuid()` default on the push path (that
  default exists only for direct/admin SQL, e.g. the profile-provisioning
  webhook in §3.1). Client-generated ids make push idempotent under retry:
  replaying the same queued item after a timed-out request upserts the
  same row instead of creating a duplicate.
- **Push ordering.** A single `/sync/push` request's `entities` map is
  applied server-side in a fixed dependency order, regardless of key order
  in the request body: `books_metadata` → `shelves` → (`highlights`,
  `progress`, `ratings`, `shelf_items`, in any order — each depends only on
  `books_metadata` and/or `shelves`) → `shared_passages` (soft-depends on
  `highlights`). An item whose parent hasn't synced yet (FK violation, e.g.
  a `highlights` row pushed before its `books_metadata` row exists on the
  server) comes back `status: error`; the client retries it on the next
  push once the parent has landed — the offline queue's FIFO order
  normally prevents this, but a partial/retried batch can still hit it.

## 2. SQL schema (Postgres / Supabase)

Executable DDL. Every table has `user_id`, `created_at`, `updated_at`
(`deleted_at` where sync tombstoning applies). `pgcrypto` is required for
`gen_random_uuid()` (enabled by default on Supabase).

```sql
-- Shared trigger: auto-touch updated_at on every UPDATE.
create or replace function set_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

-- Shared trigger: delete-wins (§1.3). Blocks any UPDATE that would clear a
-- tombstone unless the API explicitly opted in for this transaction via
-- `set local berilo.allow_undelete = 'true'` (only done for a push item
-- with `undelete: true`, itself still subject to normal LWW).
create or replace function enforce_delete_wins()
returns trigger as $$
begin
  if old.deleted_at is not null
     and new.deleted_at is null
     and coalesce(current_setting('berilo.allow_undelete', true), 'false') <> 'true'
  then
    raise exception 'cannot clear deleted_at without explicit undelete (berilo.allow_undelete)';
  end if;
  return new;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------
-- profiles
-- ---------------------------------------------------------------------
create table profiles (
  id           text primary key,               -- Clerk user id ("user_...")
  handle       text not null unique,            -- lowercase, url-safe slug
  display_name text not null,
  is_public    boolean not null default false,  -- gates the reading page
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create trigger profiles_set_updated_at
  before update on profiles
  for each row execute function set_updated_at();

alter table profiles enable row level security;

create policy profiles_select_own on profiles
  for select using (id = (auth.jwt() ->> 'sub'));

create policy profiles_select_public on profiles
  for select using (is_public = true);

create policy profiles_upsert_own on profiles
  for insert with check (id = (auth.jwt() ->> 'sub'));

create policy profiles_update_own on profiles
  for update using (id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- books_metadata — content_hash is PK *per user* (composite), never a
-- globally shared row: keeps RLS trivially owner-based, no cross-user join.
-- ---------------------------------------------------------------------
create table books_metadata (
  user_id      text not null references profiles(id) on delete cascade,
  content_hash text not null,                   -- BookEntity.id (hex SHA-256)
  title        text not null,
  authors      text not null,
  source_lang  text,                             -- [OPEN-2]
  target_lang  text,                             -- [OPEN-2]
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  primary key (user_id, content_hash)
);

create trigger books_metadata_set_updated_at
  before update on books_metadata
  for each row execute function set_updated_at();

create trigger books_metadata_enforce_delete_wins
  before update on books_metadata
  for each row execute function enforce_delete_wins();

create index books_metadata_user_updated_idx
  on books_metadata (user_id, updated_at, content_hash);

alter table books_metadata enable row level security;

create policy books_metadata_owner_crud on books_metadata
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- highlights — one row per highlight OR note (note nullable), mirrors
-- HighlightEntity exactly.
-- ---------------------------------------------------------------------
create table highlights (
  id            uuid primary key default gen_random_uuid(),
  user_id       text not null references profiles(id) on delete cascade,
  book_hash     text not null,
  color         text not null check (color in ('AMBER','SAGE','SKY','ROSE')),
  selected_text text not null,
  note          text,
  locator_json  jsonb not null,
  chapter_title text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  foreign key (user_id, book_hash)
    references books_metadata (user_id, content_hash) on delete cascade
);

create trigger highlights_set_updated_at
  before update on highlights
  for each row execute function set_updated_at();

create trigger highlights_enforce_delete_wins
  before update on highlights
  for each row execute function enforce_delete_wins();

create index highlights_user_updated_idx
  on highlights (user_id, updated_at, id);
create index highlights_user_book_idx
  on highlights (user_id, book_hash);

alter table highlights enable row level security;

create policy highlights_owner_crud on highlights
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- vocabulary — mirrors DictionaryEntryEntity's composite cache key, plus
-- sync bookkeeping. See [OPEN-1] re: `sentence`.
-- ---------------------------------------------------------------------
create table vocabulary (
  user_id         text not null references profiles(id) on delete cascade,
  word            text not null,
  sentence_hash   text not null,
  sentence        text not null,                -- [OPEN-1]
  lang            text not null,
  model           text not null,
  definition      text not null,
  context_meaning text not null,
  base_form       text not null,
  usage_note      text not null,
  cost_eur        numeric(10,6) not null default 0,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  primary key (user_id, word, sentence_hash, lang, model)
);

create trigger vocabulary_set_updated_at
  before update on vocabulary
  for each row execute function set_updated_at();

create trigger vocabulary_enforce_delete_wins
  before update on vocabulary
  for each row execute function enforce_delete_wins();

create index vocabulary_user_updated_idx
  on vocabulary (user_id, updated_at, word, sentence_hash, lang, model);

alter table vocabulary enable row level security;

create policy vocabulary_owner_crud on vocabulary
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- progress — per user+book locator + percent. Device is source of truth;
-- this is cross-device continuity only (spec §6). Upsert-only (§1.3): no
-- deleted_at, no enforce_delete_wins trigger — there is no delete op.
-- ---------------------------------------------------------------------
create table progress (
  user_id      text not null references profiles(id) on delete cascade,
  book_hash    text not null,
  locator_json jsonb not null,
  percent      numeric(5,2) not null check (percent >= 0 and percent <= 100),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  primary key (user_id, book_hash),
  foreign key (user_id, book_hash)
    references books_metadata (user_id, content_hash) on delete cascade
);

create trigger progress_set_updated_at
  before update on progress
  for each row execute function set_updated_at();

create index progress_user_updated_idx
  on progress (user_id, updated_at, book_hash);

alter table progress enable row level security;

create policy progress_owner_crud on progress
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- shelves + shelf_items — public reading page (spec §6.1). shelf_items
-- denormalizes book_title/book_authors so public reads never need a join
-- into the owner-only books_metadata table.
-- ---------------------------------------------------------------------
create table shelves (
  id         uuid primary key default gen_random_uuid(),
  user_id    text not null references profiles(id) on delete cascade,
  kind       text not null default 'custom'
             check (kind in ('reading','read','want_to_read','custom')),
  name       text not null,
  is_public  boolean not null default false,     -- [OPEN-3]
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- at most one non-custom shelf of each kind per user
create unique index shelves_one_per_kind_idx
  on shelves (user_id, kind) where kind <> 'custom';

create trigger shelves_set_updated_at
  before update on shelves
  for each row execute function set_updated_at();

create trigger shelves_enforce_delete_wins
  before update on shelves
  for each row execute function enforce_delete_wins();

create index shelves_user_updated_idx
  on shelves (user_id, updated_at, id);

alter table shelves enable row level security;

create policy shelves_owner_crud on shelves
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

create policy shelves_select_public on shelves
  for select using (
    is_public = true
    and exists (
      select 1 from profiles p
      where p.id = shelves.user_id and p.is_public = true
    )
  );

create table shelf_items (
  id           uuid primary key default gen_random_uuid(),
  shelf_id     uuid not null references shelves(id) on delete cascade,
  user_id      text not null references profiles(id) on delete cascade,
  book_hash    text not null,
  book_title   text not null,
  book_authors text not null,
  added_at     timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  unique (shelf_id, book_hash)
);

create trigger shelf_items_set_updated_at
  before update on shelf_items
  for each row execute function set_updated_at();

create trigger shelf_items_enforce_delete_wins
  before update on shelf_items
  for each row execute function enforce_delete_wins();

create index shelf_items_user_updated_idx
  on shelf_items (user_id, updated_at, id);
create index shelf_items_shelf_idx on shelf_items (shelf_id);

alter table shelf_items enable row level security;

-- with check also verifies shelf_id actually belongs to the caller, not
-- just that the row's own user_id claims to: without this, a client could
-- insert a row with its own user_id but someone else's shelf_id, injecting
-- unwanted items into a stranger's public shelf (the public-select policy
-- below joins on shelf_id, not on shelf_items.user_id).
create policy shelf_items_owner_crud on shelf_items
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (
    user_id = (auth.jwt() ->> 'sub')
    and exists (
      select 1 from shelves s
      where s.id = shelf_items.shelf_id
        and s.user_id = (auth.jwt() ->> 'sub')
    )
  );

create policy shelf_items_select_public on shelf_items
  for select using (
    exists (
      select 1 from shelves s
      join profiles p on p.id = s.user_id
      where s.id = shelf_items.shelf_id
        and s.is_public = true
        and p.is_public = true
    )
  );

-- ---------------------------------------------------------------------
-- ratings — 1..5 stars + optional short review, book_title/authors
-- denormalized for the same reason as shelf_items.
-- ---------------------------------------------------------------------
create table ratings (
  user_id      text not null references profiles(id) on delete cascade,
  book_hash    text not null,
  book_title   text not null,
  book_authors text not null,
  stars        smallint not null check (stars between 1 and 5),
  review       text check (char_length(review) <= 2000),
  is_public    boolean not null default false,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  primary key (user_id, book_hash),
  foreign key (user_id, book_hash)
    references books_metadata (user_id, content_hash) on delete cascade
);

create trigger ratings_set_updated_at
  before update on ratings
  for each row execute function set_updated_at();

create trigger ratings_enforce_delete_wins
  before update on ratings
  for each row execute function enforce_delete_wins();

create index ratings_user_updated_idx
  on ratings (user_id, updated_at, book_hash);
create index ratings_public_book_idx
  on ratings (book_hash) where is_public = true and deleted_at is null;

alter table ratings enable row level security;

create policy ratings_owner_crud on ratings
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

create policy ratings_select_public on ratings
  for select using (
    is_public = true
    and exists (
      select 1 from profiles p
      where p.id = ratings.user_id and p.is_public = true
    )
  );

-- ---------------------------------------------------------------------
-- shared_passages — citation-length excerpt (<=500 chars) + attribution.
-- `highlight_id` is a soft reference: the passage is a snapshot taken at
-- share time and outlives edits/deletes of the source highlight.
-- ---------------------------------------------------------------------
create table shared_passages (
  id             uuid primary key default gen_random_uuid(),
  user_id        text not null references profiles(id) on delete cascade,
  book_hash      text not null,
  book_title     text not null,
  book_authors   text not null,
  highlight_id   uuid references highlights(id) on delete set null,
  excerpt        text not null check (char_length(excerpt) <= 500),
  is_public      boolean not null default false,   -- private-by-default (spec §6.1)
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz
);

create trigger shared_passages_set_updated_at
  before update on shared_passages
  for each row execute function set_updated_at();

create trigger shared_passages_enforce_delete_wins
  before update on shared_passages
  for each row execute function enforce_delete_wins();

create index shared_passages_user_updated_idx
  on shared_passages (user_id, updated_at, id);
create index shared_passages_public_feed_idx
  on shared_passages (created_at desc)
  where is_public = true and deleted_at is null;

alter table shared_passages enable row level security;

create policy shared_passages_owner_crud on shared_passages
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

create policy shared_passages_select_public on shared_passages
  for select using (
    is_public = true
    and exists (
      select 1 from profiles p
      where p.id = shared_passages.user_id and p.is_public = true
    )
  );
```

**RLS summary (owner-only unless noted):** every table restricts
`insert`/`update`/`delete` to `user_id = auth.jwt()->>'sub'`. The *only*
public `select` grants are: `profiles` (where `is_public`), `shelves` +
`shelf_items` (shelf `is_public` AND owner profile `is_public`; `shelf_items`
insert/update additionally requires the target `shelf_id` to already belong
to the caller, closing a cross-user injection where a client could otherwise
attach its own rows to someone else's public shelf), `ratings` (row
`is_public` AND owner profile `is_public`), `shared_passages` (row
`is_public` AND owner profile `is_public`; `is_public` defaults to `false`
— row creation alone does **not** make a passage public, matching spec
§6.1's private-by-default rule; the client sets `is_public: true`
explicitly when the user shares). `books_metadata`, `highlights`,
`vocabulary`, `progress` have **no** public policy — never readable outside
the owner, even if the profile is public, matching spec §6.1's "book files
never touch the service" and "book metadata only" on shelves (which is why
those columns are denormalized onto `shelf_items`/`ratings` instead of
requiring a public join into `books_metadata`). Every tombstone-bearing
table also carries an `enforce_delete_wins` trigger (delete-wins, §1.3) —
independent of RLS, it blocks any `UPDATE` from clearing `deleted_at`
outside the explicit undelete path.

## 3. REST endpoints (OpenAPI 3.1)

### 3.1 Profile provisioning

`profiles` rows are never created by the client directly (the
`profiles_upsert_own` RLS policy in §2 is a defensive fallback, not the
primary path). Provisioning is server-side:

1. **Clerk `user.created` webhook** (received by a `berilo-cloud` API route,
   verified via Clerk's webhook signing secret) inserts the `profiles` row
   using the Supabase **service-role key** (a trusted server context that
   bypasses RLS by design — the only place in this contract that does):
   `id` = the Clerk user id, `handle` = a slugified default from Clerk's
   `username` if present, else `user_id` with a random suffix, retried on
   the `handle` unique-constraint violation, `display_name` = Clerk's
   `first_name`/`last_name` or `username` fallback, `is_public` = `false`.
2. **`PATCH /profile`** (below) lets the signed-in user change `handle`
   (validated as a lowercase URL-safe slug; a taken handle 409s),
   `display_name`, and `is_public`. This goes through the normal
   Clerk-JWT-authenticated path and relies on the existing
   `profiles_update_own` RLS policy — no service-role key involved.

```yaml
openapi: 3.1.0
info:
  title: Berilo Sync API
  version: "1.0.0"
servers:
  - url: https://berilo.app/api/v1
security:
  - clerkBearer: []
paths:
  /sync/pull:
    post:
      summary: Pull changes since per-entity cursors.
      security:
        - clerkBearer: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [entities]
              properties:
                entities:
                  type: array
                  items:
                    type: string
                    enum: [books_metadata, highlights, vocabulary, progress,
                           shelves, shelf_items, ratings, shared_passages]
                cursors:
                  type: object
                  description: >
                    Map of entity name to opaque cursor from a prior pull's
                    next_cursor for that entity. Omitted or null = pull from
                    the beginning.
                  additionalProperties:
                    type: string
                    nullable: true
                limit:
                  type: integer
                  minimum: 1
                  maximum: 1000
                  default: 1000
      responses:
        "200":
          description: Per-entity changed rows since each cursor.
          content:
            application/json:
              schema:
                type: object
                properties:
                  entities:
                    type: object
                    additionalProperties:
                      type: object
                      properties:
                        rows:
                          type: array
                          items: { type: object }
                        next_cursor:
                          type: string
                          nullable: true
                        has_more:
                          type: boolean
        "401": { $ref: "#/components/responses/Unauthorized" }
        "429": { $ref: "#/components/responses/RateLimited" }

  /sync/push:
    post:
      summary: Batched upsert/delete per entity, LWW on updated_at.
      security:
        - clerkBearer: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [entities]
              properties:
                entities:
                  type: object
                  additionalProperties:
                    type: array
                    items:
                      type: object
                      required: [op, id, updated_at]
                      properties:
                        op: { type: string, enum: [upsert, delete] }
                        id:
                          type: string
                          description: >
                            Client-generated. For uuid-keyed entities
                            (highlights, shelves, shelf_items,
                            shared_passages) a client-generated UUID. For
                            composite-PK entities (books_metadata,
                            vocabulary, progress, ratings) the natural key
                            fields (e.g. content_hash) instead — see §4's
                            per-entity cursor table for the exact tuple.
                        updated_at:
                          type: string
                          format: date-time
                          description: Client UTC clock; drives LWW.
                        undelete:
                          type: boolean
                          default: false
                          description: >
                            Only meaningful with op: upsert against a
                            tombstoned row (deleted_at set). See §1.3
                            delete-wins: without this, such an upsert is
                            rejected rather than silently resurrecting the
                            row. Invalid (validation_error) on entity:
                            progress, which has no delete op.
                      additionalProperties: true
      responses:
        "200":
          description: Per-item apply results, in request order.
          content:
            application/json:
              schema:
                type: object
                properties:
                  results:
                    type: object
                    additionalProperties:
                      type: array
                      items:
                        type: object
                        properties:
                          key:
                            type: object
                            description: Echoed primary-key fields of the item.
                          status:
                            type: string
                            enum: [applied, conflict, error]
                          server_row:
                            type: object
                            nullable: true
                            description: Present when status is conflict — client overwrites local copy with this.
                          error:
                            type: string
                            nullable: true
        "401": { $ref: "#/components/responses/Unauthorized" }
        "413": { description: Batch too large (>1000 items across all entities in one request). }
        "429": { $ref: "#/components/responses/RateLimited" }

  /public/readers/{handle}:
    get:
      summary: Opted-in profile page data for a public reader.
      security: []
      parameters:
        - name: handle
          in: path
          required: true
          schema: { type: string }
        - name: cursor
          in: query
          schema: { type: string, nullable: true }
        - name: limit
          in: query
          schema: { type: integer, minimum: 1, maximum: 1000, default: 100 }
      responses:
        "200":
          description: Public profile with opted-in shelves/ratings/passages, paginated per section.
          content:
            application/json:
              schema:
                type: object
                properties:
                  profile:
                    type: object
                    properties:
                      handle: { type: string }
                      display_name: { type: string }
                  shelves:
                    type: array
                    description: >
                      Public shelf projection. book_hash is never included
                      — the public surface is display metadata only.
                    items:
                      type: object
                      properties:
                        name: { type: string }
                        kind: { type: string }
                        items:
                          type: array
                          items:
                            type: object
                            properties:
                              book_title: { type: string }
                              book_authors: { type: string }
                              added_at: { type: string, format: date-time }
                  ratings:
                    type: array
                    description: book_hash excluded, see shelves above.
                    items:
                      type: object
                      properties:
                        book_title: { type: string }
                        book_authors: { type: string }
                        stars: { type: integer }
                        review: { type: string, nullable: true }
                        created_at: { type: string, format: date-time }
                  shared_passages:
                    type: array
                    description: book_hash excluded, see shelves above.
                    items:
                      type: object
                      properties:
                        book_title: { type: string }
                        book_authors: { type: string }
                        excerpt: { type: string }
                        created_at: { type: string, format: date-time }
                  next_cursor: { type: string, nullable: true }
        "404": { description: Profile not found or not public. }
        "429": { $ref: "#/components/responses/RateLimited" }

  /public/passages:
    get:
      summary: Recent public shared passages, newest first.
      security: []
      parameters:
        - name: cursor
          in: query
          schema: { type: string, nullable: true }
        - name: limit
          in: query
          schema: { type: integer, minimum: 1, maximum: 1000, default: 50 }
      responses:
        "200":
          description: Paginated public passage feed.
          content:
            application/json:
              schema:
                type: object
                properties:
                  passages:
                    type: array
                    description: >
                      book_hash is never included — public surface is
                      display metadata only.
                    items:
                      type: object
                      properties:
                        handle: { type: string, description: Sharer's public handle. }
                        book_title: { type: string }
                        book_authors: { type: string }
                        excerpt: { type: string }
                        created_at: { type: string, format: date-time }
                  next_cursor: { type: string, nullable: true }
        "429": { $ref: "#/components/responses/RateLimited" }

  /profile:
    patch:
      summary: Update the signed-in user's own profile (§3.1). Never creates one.
      security:
        - clerkBearer: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                handle:
                  type: string
                  description: Lowercase URL-safe slug, unique.
                display_name: { type: string }
                is_public: { type: boolean }
      responses:
        "200":
          description: Updated profile.
          content:
            application/json:
              schema:
                type: object
                properties:
                  handle: { type: string }
                  display_name: { type: string }
                  is_public: { type: boolean }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "409": { description: handle already taken. }
        "429": { $ref: "#/components/responses/RateLimited" }

components:
  securitySchemes:
    clerkBearer:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: Clerk session JWT, verified server-side and forwarded to Supabase's third-party-auth check.
  responses:
    Unauthorized:
      description: Missing/invalid/expired Clerk JWT.
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorEnvelope" }
    RateLimited:
      description: Too many requests. Retry-After header set.
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ErrorEnvelope" }
  schemas:
    ErrorEnvelope:
      type: object
      properties:
        error:
          type: object
          required: [code, message]
          properties:
            code: { type: string }
            message: { type: string }
            request_id: { type: string }
```

**Error envelope:** every non-2xx response body is
`{"error": {"code": "...", "message": "...", "request_id": "..."}}`. `code`
is a stable machine-readable string (`unauthorized`, `invalid_cursor`,
`batch_too_large`, `rate_limited`, `not_found`, `validation_error`).

**Rate limits:** `/sync/pull` and `/sync/push` — 60 requests/min per
authenticated user. `/public/*` — 120 requests/min per IP. Both return
`429` with a `Retry-After` header (seconds) on the error envelope above.
Vercel serverless functions are stateless and multi-instance, so an
in-memory counter does not work — limiter state lives in a shared external
store (e.g. Upstash Redis via `@upstash/ratelimit`, or a Supabase table
with a sliding-window counter if avoiding a second vendor is preferred);
`berilo-cloud` picks the concrete backend, this contract only fixes the
limits and the response shape.

**Infra tier note:** production reliability likely needs paid tiers, not
free ones — Vercel Pro (longer function duration for large sync batches,
cron for the tombstone-retention job) and a paid Supabase plan (the free
tier pauses the project after ~7 days of inactivity, which is fatal for an
always-on sync backend). Sizing this is a `berilo-cloud` infra decision,
not part of this contract, but the free-tier defaults should not be
assumed to hold in production.

## 4. Pagination discipline

Every list-returning query is paginated — no unbounded `select *`. Two
consequences enforced by this contract, not left to the implementer's
discretion:

- **Supabase's 1000-row default is the ceiling, not the target.** `limit`
  parameters cap at 1000 and every endpoint that can return more than one
  row exposes a cursor, including `/public/readers/{handle}`'s embedded
  sections — a reader with a large shelf must not silently truncate.
- **Cursor semantics: keyset, not offset.** A cursor is
  `(updated_at, <full primary-key tuple>)` — never `updated_at` alone,
  because Postgres evaluates `now()` once per transaction: a bulk insert
  (e.g. an initial backfill) can give many rows an *identical* `updated_at`,
  and ordering by timestamp alone would non-deterministically skip or
  repeat rows across pages. Ordering by `(updated_at, pk...)` — a strict
  total order, since the PK tuple is unique — breaks every tie
  deterministically. The exact tuple per entity (excluding `user_id`, which
  is constant within one authenticated pull and does not need to be part of
  the sort key):

  | Entity | Cursor tuple |
  |---|---|
  | `books_metadata` | `(updated_at, content_hash)` |
  | `highlights` | `(updated_at, id)` |
  | `vocabulary` | `(updated_at, word, sentence_hash, lang, model)` |
  | `progress` | `(updated_at, book_hash)` |
  | `shelves` | `(updated_at, id)` |
  | `shelf_items` | `(updated_at, id)` |
  | `ratings` | `(updated_at, book_hash)` |
  | `shared_passages` | `(updated_at, id)` |
  | `/public/passages` | `(created_at, id)`, newest-first |

  A cursor is an opaque base64 string; clients must not construct or parse
  it. A page fetches `limit + 1` rows to compute `has_more` without a
  second count query.
- Each entity in `/sync/pull` carries its **own** cursor — cursors are not
  comparable across tables (different write volumes, independent
  `updated_at` ranges, different tuple shapes per the table above).
- **Drain protocol.** Within one sync round, a client repeatedly calls
  `/sync/pull` for an entity, feeding each response's `next_cursor` back in
  as that entity's cursor for the next call, purely in-memory — it does
  **not** write to durable local storage (Room/DataStore) after every page.
  Only once a page comes back with `has_more: false` does the client
  persist that final cursor as the entity's new durable sync baseline. This
  keeps the meaning of the persisted cursor simple and singular ("fully
  caught up as of here") rather than "possibly mid-drain," and means a
  crash mid-drain just restarts that entity's drain from the last durable
  baseline (safe — keyset pagination is idempotent to resume from any
  earlier point, just not maximally efficient).

## 5. Timestamps and DST

All timestamps are `timestamptz` in Postgres, serialized as UTC ISO-8601
(`2026-07-24T13:00:00Z`) over the wire — never a local offset, never a
naive datetime. Clients convert to/from local time only at the UI layer.
This sidesteps DST ambiguity entirely: UTC has no DST transitions, so the
LWW comparison (`incoming.updated_at >= current.updated_at`, §1.3) and
keyset cursors (§4) are monotonic regardless of which local timezone —
including its DST transitions — a device sits in. `berilo-cloud`'s test
suite must still exercise the DST-crossing scenario named in the S3.2 Verify
line (device writes spanning a local DST boundary, e.g. Europe/Ljubljana's
March/October transitions), since it is the client's local→UTC conversion,
not this UTC-only wire format, that DST could break.

## 6. Versioning

Contract version **v1**, served at `/api/v1/*` (see `servers.url` above).
**Additive-only within v1:** new optional fields, new entities, and new
enum values may be added without a version bump — clients must ignore
unknown fields. Any breaking change (removing/renaming a field, changing a
type, tightening a previously-optional constraint, changing LWW/pagination
semantics) requires `/api/v2` served alongside `/api/v1` until every
client build in the field has migrated.

## 7. Open decisions for Niko

- **[OPEN-1]** `vocabulary.sentence` needs a new column on
  `DictionaryEntryEntity` (currently only `sentenceHash`) before S3.2 can
  push it — confirm before S3.2 starts, or descope the raw sentence from
  web review.
- **[OPEN-2]** `books_metadata.source_lang`/`target_lang` have no Android
  source today; confirm S3.2 backfills them (from EPUB `dc:language` /
  settings) rather than leaving them null indefinitely.
- **[OPEN-3]** Privacy granularity is per-shelf (`shelves.is_public`), not
  per-book-on-a-shelf. Confirm this matches the "per item" reading of spec
  §6.1, or this becomes `shelf_items.is_public` instead (changes the RLS
  policy on `shelf_items`).
- **[OPEN-4]** Android's synced entities aren't sync-shaped yet:
  `HighlightEntity` has no soft-delete flag and no outbox/pending-mutation
  table (S2.6 shipped it as local-only), and `DictionaryEntryEntity` has no
  `updatedAt` (only `createdAt`, treated as an immutable cache row). Both
  are required by this contract — `updated_at` drives LWW on every pushed
  item (§1.3), and a delete needs something to tombstone. S3.2 must add
  soft-delete/outbox support to `HighlightEntity` and an `updatedAt` column
  to `DictionaryEntryEntity` before it can implement the sync client
  against this contract.
- **[OPEN-5]** Client-clock LWW (§1.3) has a real data-loss mode: a device
  with a wrong clock can make its writes always lose (clock behind) or
  always win and clobber others' newer edits (clock ahead), silently, with
  no server-side signal. Three options, not decided here:
  (a) **server-authoritative `updated_at`** — the server stamps `now()` at
  receipt instead of trusting the client value; ordering becomes
  receipt-order, not edit-order, which is simpler and immune to client
  clock skew but can misorder two edits to the same record made on
  different devices while one was offline (the offline edit "happened"
  earlier but syncs — and so gets stamped — later); (b) **reject future
  timestamps** — server 400s any incoming `updated_at` more than a small
  skew tolerance ahead of server time, forcing the client to fix its clock;
  catches gross skew, not small drift; (c) **accept with an NTP
  assumption** — v1 as currently specified; simplest, and mobile OS clocks
  are NTP-synced by default, but silent small drift is an unhandled
  residual risk. Recommendation if this needs tightening: **(a)
  server-authoritative**, since it fully removes client clock trust from
  the isolation-critical path at the cost of the offline-edit-ordering
  edge case above, which is rarer and lower-stakes than a systematically
  wrong device clock. Flagging rather than changing the contract
  unilaterally since it alters push semantics client code will be written
  against.
