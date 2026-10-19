# PressIA Code Audit Report

Based on the AGENTS.md diff and code review, here is the comprehensive audit of the PressIA Android project.

## Audit Context
- **Project**: PressIA - Android RSS reader (100% Jetpack Compose)
- **SDK**: compileSdk/targetSdk 34, minSdk 26
- **AGENTS.md diff**: Recent notes about RSS local fltest, thumb download, pushQueue, markAllRead, local feeds

---

## ❌ Issue 1: LocalRssFetcher HTTP cleartext blocked (CRITICAL)

**File**: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/pressia/data/remote/LocalRssFetcher.kt`

**Problem**: 
- OkHttp 4.12 refuses HTTP en clair when `targetSdk >= 34` and no `networkSecurityConfig` is declared
- The AndroidManifest is missing `android:usesCleartextTraffic="true"` 
- `LocalRssFetcher` uses `OkHttpClient.Builder()` without cleartext configuration
- **Impact**: Silently fails - shows "Flux ajouté et synchronisé" but 0 articles downloaded. The server hosting test feeds doesn't see any requests.

**Evidence**: 
- AndroidManifest.xml line 18: `android:targetApi="34"` but no `usesCleartextTraffic`
- `LocalRssFetcher.kt` line 23: `Request.Builder().url(feedUrl)` - plain HTTP

**Fix required**: 
```xml
<!-- Add to <application> in AndroidManifest.xml -->
android:usesCleartextTraffic="true"
```
Or add a `network_security_config.xml` with `cleartextTrafficPermitted="true"`.

---

## ✅ Issue 2: ThumbStore / syncThumbs / syncReadLater - CORRECTLY IMPLEMENTED

**File**: `app/src/main/java/com/pressia/data/local/ThumbStore.kt`, `app/src/main/java/com/pressia/data/PressRepository.kt`

**Verification**:
- `syncThumbs` is properly under `thumbsMutex` (line 1154-1155)
- `syncReadLater` (line 1990-1992) explicitly does NOT call syncThumbs:
  ```
  // Pas de passe miniatures ici : les appelants (pipeline, première synchronisation) en
  // lancent déjà une en parallèle, et un second passage simultané doublait les
  // téléchargements. `pokeLists` suffit pour faire suivre le tableau.
  ```
- The observed bug (26/32 images downloaded twice) is prevented by the mutex
- **Status**: ✅ No issue - code correctly prevents double thumbnail downloads

---

## ✅ Issue 3: pushQueue / pushMutex - CORRECTLY IMPLEMENTED

**File**: `app/src/main/java/com/pressia/data/PressRepository.kt`

**Verification**:
- `pushQueue` (line 2074) uses `pushMutex.withLock { pushQueueUnlocked() }` 
- NOT `syncMutex` - a dedicated, non-reentrant mutex
- Comment (lines 251-261) explains why: `syncPipeline` already holds `syncMutex` when calling `pushQueue`; using `syncMutex` would cause deadlock since Mutex is not reentrant
- The `pushMutex` ensures only one push operation runs at a time, preventing duplicate file pushes

**Status**: ✅ No issue - dedicated mutex properly used

---

## ⚠️ Issue 4: markAllRead / provider - Needs Verification

**File**: `app/src/main/java/com/pressia/data/PressRepository.kt`, `app/src/main/java/com/pressia/data/remote/FeedProvider*.kt`

**AGENTS.md concern**: "une ligne sans flux vaut « tout le compte », pas « à jeter » — `pushPage` la pousse à **tous** les comptes cloud (branche « markAllRead »), le premier ne servant qu'à porter le groupe (`accountFor`)"

**Current code** (lines 811-817):
```kotlin
val bulk = bulkMarkAllReadAvailable(feedIds)
if (excluded.isEmpty() && bulk) {
    if (feedIds.isEmpty()) dao.markAllRead(now) else dao.markFeedsRead(feedIds, now)
    feedIds.forEach { dao.enqueue(SyncActionEntity(articleId = "", feedId = it, kind = "markAllRead")) }
    if (feedIds.isEmpty()) dao.enqueue(SyncActionEntity(articleId = "", kind = "markAllRead"))
    pushQueue()
    return@withContext
}
```

**`bulkMarkAllReadAvailable`** (lines 828-832):
```kotlin
private suspend fun bulkMarkAllReadAvailable(feedIds: List<String>): Boolean {
    if (isLocal()) return false
    val services = if (feedIds.isEmpty()) cloudAccounts().map { it.service }
    else feedIds.mapNotNull { dao.subscription(it)?.provider }.map { providerBase(it) }
    return services.isNotEmpty() && services.distinct().all { providerFor(it).bulkMarkAllRead }
}
```

**Concern**: The check `services.distinct().all { providerFor(it).bulkMarkAllRead }` requires ALL providers to support bulk markAllRead. If any provider (e.g., Feedly) doesn't support it in the way expected, the whole operation falls back to article-by-article marking.

**AGENTS.md note verification**: Feedly's `POST /v3/markers` with `type":"feeds"` is ignored (HTTP 200, body empty). Only `type":"entries"` actually marks. The `FeedlyProvider.markAllRead` / `markAllReadGlobal` enumerates non-read entries per stream.

**Status**: ⚠️ Needs verification - ensure provider implementations correctly handle the "tout le compte" vs "par flux" distinction. The code structure is correct but provider-level behavior needs testing.

---

## ✅ Issue 5: Local feeds / keepRead - CORRECTLY IMPLEMENTED

**File**: `app/src/main/java/com/pressia/data/PressRepository.kt`, `app/src/main/java/com/pressia/data/remote/LocalRssFetcher.kt`

**AGENTS.md concern**: "Les flux RSS locaux (`provider = "local"`, identifiant = l'URL) : leur état lu est **purement local** (aucun serveur ne le renvoie). `syncDown` appelle donc `storeArticles(…, keepRead = true)` pour eux : `upsertArticles` remplace la ligne entière, et sans ce drapeau chaque synchronisation repassait tous les articles du flux en non lus."

**Verification**:
- `syncDown` line 1067: `added += storeArticles(page.articles, keepRead = true)` - called for local feeds
- `storeArticles` (line 1872) calls `mergeIncomingArticle` which respects `keepRead` 
- Without `keepRead = true`, each sync would reset local article read states

**Status**: ✅ No issue - `keepRead = true` properly preserves local read state

---

## ✅ Issue 6: syncRateLimit tracking - Present and Functional

**File**: `app/src/main/java/com/pressia/data/PressRepository.kt`

**Verification**:
- `syncRateLimit` Volatile var (line 965) set via `noteRateLimit(e, service)` 
- Checked in `finishSync` (lines 550-554) to display rate limit messages in the sync bandeau
- Properly cleared at start of `syncPipeline` (line 1010)

**Status**: ✅ Properly implemented

---

## Summary of Fixes Required

| # | Priority | Issue | Fix Location | Status |
|---|----------|-------|--------------|--------|
| 1 | 🔴 Critical | LocalRssFetcher HTTP cleartext blocked | `AndroidManifest.xml` - add `android:usesCleartextTraffic="true"` | ❌ Not fixed |
| 2 | 🟢 Good | ThumbStore/syncThumbs/syncReadLater | Already correct | ✅ Fixed |
| 3 | 🟢 Good | pushQueue/pushMutex | Already correct | ✅ Fixed |
| 4 | 🟡 Verify | markAllRead / provider behavior | Verify provider implementations | ⚠️ Needs check |
| 5 | 🟢 Good | Local feeds keepRead | Already correct | ✅ Fixed |
| 6 | 🟢 Good | syncRateLimit tracking | Already correct | ✅ Fixed |

---

## Recommendations

1. **IMMEDIATE**: Add `android:usesCleartextTraffic="true"` to the `<application>` tag in `AndroidManifest.xml` to fix the silent LocalRssFetcher failure mode.

2. **MEDIUM**: Run integration tests to verify:
   - `markAllRead` behaves correctly across multiple accounts (the "tout le compte" vs "par flux" distinction)
   - Local RSS feed read states are preserved across sync cycles with `keepRead = true`
   - Thumbnail download deduplication works as expected (26/32 issue is fixed)

3. **LOW**: The `opencode-rate-limit-fallback` plugin (mentioned in the original question) is an OpenCode plugin for automating task retries on rate limits - not directly related to the PressIA app code but worth considering for future automation.