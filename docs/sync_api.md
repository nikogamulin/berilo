# Berilo sync API contract — v1

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
| `highlights` | `HighlightEntity` | one row serves both highlights and notes — `note` is nullable, matching Android's merged model |
| `vocabulary` | `DictionaryEntryEntity` | see **[OPEN-1]** below |
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
  this comparison (documented risk, not solved by this contract — devices
  are expected to be NTP-synced).
- **Offline queue:** the app queues local mutations and replays them
  through `/sync/push` in `updated_at` order on reconnect; §2 batches are
  designed to make one queue flush a small number of requests.
- **Tombstones:** `deleted_at timestamptz` on every synced table. A pull
  response includes tombstoned rows (so a receiving device deletes its
  local copy) until an out-of-band retention job purges them — no retention
  window is fixed by this contract; `berilo-cloud` may add one without
  breaking clients since tombstone presence, not absence, is what clients
  rely on.

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

create index vocabulary_user_updated_idx
  on vocabulary (user_id, updated_at, word, sentence_hash, lang, model);

alter table vocabulary enable row level security;

create policy vocabulary_owner_crud on vocabulary
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

-- ---------------------------------------------------------------------
-- progress — per user+book locator + percent. Device is source of truth;
-- this is cross-device continuity only (spec §6).
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

create index shelf_items_user_updated_idx
  on shelf_items (user_id, updated_at, id);
create index shelf_items_shelf_idx on shelf_items (shelf_id);

alter table shelf_items enable row level security;

create policy shelf_items_owner_crud on shelf_items
  for all
  using (user_id = (auth.jwt() ->> 'sub'))
  with check (user_id = (auth.jwt() ->> 'sub'));

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
  is_public      boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz
);

create trigger shared_passages_set_updated_at
  before update on shared_passages
  for each row execute function set_updated_at();

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
`shelf_items` (shelf `is_public` AND owner profile `is_public`), `ratings`
(row `is_public` AND owner profile `is_public`), `shared_passages` (row
`is_public` AND owner profile `is_public`). `books_metadata`, `highlights`,
`vocabulary`, `progress` have **no** public policy — never readable outside
the owner, even if the profile is public, matching spec §6.1's "book files
never touch the service" and "book metadata only" on shelves (which is why
those columns are denormalized onto `shelf_items`/`ratings` instead of
requiring a public join into `books_metadata`).

## 3. REST endpoints (OpenAPI 3.1)

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
                      required: [op, updated_at]
                      properties:
                        op: { type: string, enum: [upsert, delete] }
                        updated_at:
                          type: string
                          format: date-time
                          description: Client UTC clock; drives LWW.
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
                    items: { type: object }
                  ratings:
                    type: array
                    items: { type: object }
                  shared_passages:
                    type: array
                    items: { type: object }
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
                    items: { type: object }
                  next_cursor: { type: string, nullable: true }
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

## 4. Pagination discipline

Every list-returning query is paginated — no unbounded `select *`. Two
consequences enforced by this contract, not left to the implementer's
discretion:

- **Supabase's 1000-row default is the ceiling, not the target.** `limit`
  parameters cap at 1000 and every endpoint that can return more than one
  row exposes a cursor, including `/public/readers/{handle}`'s embedded
  sections — a reader with a large shelf must not silently truncate.
- **Cursor semantics: keyset, not offset.** Cursors encode
  `(updated_at, id)` (or `(created_at, id)` for `/public/passages`, which
  orders newest-first) — a two-column tuple because `updated_at` alone is
  not unique and offset pagination drifts under concurrent writes. A cursor
  is an opaque base64 string; clients must not construct or parse it. A
  page fetches `limit + 1` rows to compute `has_more` without a second
  count query.
- Each entity in `/sync/pull` carries its **own** cursor — cursors are not
  comparable across tables (different write volumes, independent
  `updated_at` ranges). A client keeps a small `{entity: cursor}` map
  locally and only advances an entity's cursor when its `has_more` is
  `false`.

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
