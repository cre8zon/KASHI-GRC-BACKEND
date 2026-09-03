# AI module — setup

## 1. Schema

`spring.jpa.hibernate.ddl-auto=update` is already set, so the eight tables are
created on first boot:

| Table | Purpose |
|---|---|
| `ai_interactions` | one row per model call — the audit trail |
| `ai_prompt_templates` | versioned prompts, seeded from classpath on first boot |
| `ai_document_chunks` | retrievable passages; MySQL is the truth, Qdrant is the index |
| `ai_suggestion_feedback` | the flywheel — accept/reject/edit on every suggestion |
| `ai_usage_counters` | pre-aggregated token spend per tenant per month |
| `ai_org_profiles` | the grounding facts that make output specific |
| `ai_ingestion_jobs` | per-attempt ingestion tracking |

`ai_interactions` and `ai_document_chunks` grow fastest. Both carry LONGTEXT
columns; plan a retention sweep on `ai_interactions` once volume is real —
twelve months is a reasonable default for a compliance audit trail, but check
what your own retention policy commits you to before choosing.

## 2. Qdrant

```bash
docker run -d --name kashi-qdrant \
  -p 6333:6333 -p 6334:6334 \
  -v $(pwd)/qdrant_storage:/qdrant/storage \
  qdrant/qdrant:latest
```

The collection is created automatically at startup
(`app.ai.qdrant.auto-create-collection=true`). In production, provision it from
infrastructure code and set that to `false` so the application does not hold
schema authority.

Nothing here touches MySQL beyond the eight tables above — the vector workload
is entirely on Qdrant, so your Aiven instance is unaffected.

## 3. Configuration

Add to `.env` (never to `application.properties`):

```properties
APP_AI_ENABLED=true
APP_AI_DEFAULT_PROVIDER=openai

APP_AI_OPENAI_API_KEY=sk-...
APP_AI_OPENAI_BASE_URL=https://api.openai.com/v1
APP_AI_OPENAI_CHAT_MODEL=gpt-4.1
APP_AI_OPENAI_FAST_MODEL=gpt-4.1-mini

# Optional second provider — lets you answer "can we choose our sub-processor?"
# with yes in an enterprise security review.
APP_AI_ANTHROPIC_API_KEY=sk-ant-...
APP_AI_ANTHROPIC_CHAT_MODEL=claude-sonnet-4-6

APP_AI_QDRANT_URL=http://localhost:6333
APP_AI_QDRANT_API_KEY=

APP_AI_BUDGET_MONTHLY_TOKENS_PER_TENANT=5000000
```

Model names change frequently — check your provider's current catalogue rather
than trusting the values above.

Relaxed properties binding maps `APP_AI_OPENAI_CHAT_MODEL` to
`app.ai.openai.chat-model` automatically. Verify the key is loaded but never
log it: `LlmProviderRegistry` logs which providers are *configured*, not their
credentials.

## 4. First run

1. Boot. Watch for `[AI-PROMPT] seed complete | files=8 imported=8` and
   `[AI-INIT] vector store ready`.
2. `PUT /v1/ai/org-profile` — **this is the highest-leverage step in the whole
   setup.** Output quality is bounded by what this contains. Check
   `GET /v1/ai/org-profile/completeness` and aim above 80.
3. `POST /v1/ai/admin/corpus/ingest-policies` to index the existing library.
4. `POST /v1/ai/policies/draft` to generate.
5. `POST /v1/ai/admin/eval/run` to confirm the golden set passes before you
   start editing prompts.

## 5. Wiring the corpus hook

In `AuditPolicyService`, after save and after any status transition:

```java
policyCorpusHook.onPolicySaved(policy);
```

and on delete:

```java
policyCorpusHook.onPolicyDeleted(policy);
```

Both are async and cannot fail the save.

## 6. Securing the admin routes

`AiAdminController` edits prompts, which is behaviour editing for every
generation in the system. Put it behind a platform-admin authority in your
existing RBAC — add `@PreAuthorize` once you have chosen the permission code
rather than relying on the path prefix.

## 7. Framework text — read before ingesting anything

`ChunkSourceType.FRAMEWORK_TEXT` exists, and what you put in it matters legally:

- **NIST** (800-53, CSF, 800-171) — US Government work, public domain. Ingest freely.
- **CIS Controls** — free with registration, under licence terms. Read them.
- **ISO/IEC 27001, 27002** — copyrighted, sold by ISO and national bodies.
  **Do not ingest or redistribute the standard text.**
- **AICPA SOC 2 Trust Services Criteria** — copyrighted. Same restriction.

For the copyrighted ones, reference **clause identifiers only** (`A.9.4.2`,
`CC6.1` — identifiers are not protected) and write your own requirement
summaries. The shipped prompts already instruct the model to do this, but the
constraint is on your ingestion pipeline, not on the model.

Getting this wrong in a compliance product is a uniquely bad look.

## 8. Zero new dependencies

Nothing in this module adds a Maven dependency. Providers and Qdrant are
hand-rolled on `RestClient` (already present via `spring-boot-starter-web`) and
Jackson. For a GRC vendor whose customers audit its supply chain, that is a
shorter security questionnaire rather than a tidiness argument.
