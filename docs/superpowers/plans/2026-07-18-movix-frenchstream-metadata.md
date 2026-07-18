# Movix and French-Stream Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reliable TMDB metadata, real season grouping, cast photos, card scores, and trustworthy quality badges to Movix and French-Stream without regressing playback.

**Architecture:** Keep metadata parsing in pure Kotlin helper objects covered by JVM tests. Providers perform network calls and map parsed metadata into CloudStream responses; existing extractor pipelines remain isolated. French-Stream resolves canonical series titles and merges sibling season pages, while Movix uses its existing TMDB IDs directly.

**Tech Stack:** Kotlin 2.3, CloudStream provider API, NiceHttp, Jsoup, `org.json`, kotlinx.coroutines, JUnit 4, Gradle 8.9, JDK 17.

## Global Constraints

- Both Movix and French-Stream are in scope.
- Scores come from TMDB `vote_average`, not IMDb ratings.
- Unknown or generic quality never creates a badge.
- VF and VOSTFR are playback variants, not seasons.
- Current extractor and packed-player behavior must remain functional.
- Both plugin versions must be incremented.
- Completion requires GitHub `Build and Publish` plus public artifact verification.

---

### Task 1: French-Stream Pure Metadata and Episode Parsers

**Files:**
- Create: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamMetadata.kt`
- Create: `FrenchStreamProvider/src/test/kotlin/com/lagradost/FrenchStreamMetadataTest.kt`
- Modify: `FrenchStreamProvider/build.gradle.kts`

**Interfaces:**
- Produces: `FrenchStreamMetadata.normalizeTitle(String): String`
- Produces: `FrenchStreamMetadata.seasonNumber(String): Int?`
- Produces: `FrenchStreamMetadata.quality(String?): SearchQuality?`
- Produces: `FrenchStreamMetadata.mergeEpisodePayload(Int, Map<String, List<String>>): String`
- Produces: `FrenchStreamMetadata.parseEpisodePayload(String): FrenchStreamEpisodePayload?`
- Produces: `FrenchStreamMetadata.isTmdbMatch(String, Int?, String, Int?): Boolean`

- [ ] **Step 1: Add JUnit and JSON test dependencies**

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 2: Write failing parser tests**

```kotlin
@Test fun stripsSeasonAndLanguageSuffixes() {
    assertEquals("Silo", FrenchStreamMetadata.normalizeTitle("Silo - Saison 3 [VF]"))
}

@Test fun mapsCameraBeforeHdTokens() {
    assertEquals(SearchQuality.Telesync, FrenchStreamMetadata.quality("TS VF HD"))
    assertEquals(SearchQuality.HdCam, FrenchStreamMetadata.quality("HDCAM 1080p"))
}

@Test fun roundTripsLanguageLinksInOneEpisode() {
    val encoded = FrenchStreamMetadata.mergeEpisodePayload(
        2,
        mapOf("VF" to listOf("https://vf.example/e2"), "VOSTFR" to listOf("https://vo.example/e2"))
    )
    assertEquals(listOf("https://vf.example/e2"), FrenchStreamMetadata.parseEpisodePayload(encoded)?.links?.get("VF"))
}
```

- [ ] **Step 3: Run tests and verify RED**

Run: `gradle :FrenchStreamProvider:testDebugUnitTest --tests com.lagradost.FrenchStreamMetadataTest`

Expected: compilation fails because `FrenchStreamMetadata` does not exist.

- [ ] **Step 4: Implement minimal pure helper**

Implement title normalization with case-insensitive season/language regexes, quality token precedence, JSON payload serialization, URL filtering, and normalized title/year candidate matching. Do not perform network calls in this file.

- [ ] **Step 5: Run tests and verify GREEN**

Run: `gradle :FrenchStreamProvider:testDebugUnitTest --tests com.lagradost.FrenchStreamMetadataTest`

Expected: all parser tests pass.

### Task 2: Movix Pure Quality and TMDB Parsers

**Files:**
- Create: `Movix/src/main/kotlin/com/lagradost/MovixMetadata.kt`
- Create: `Movix/src/test/kotlin/com/lagradost/MovixMetadataTest.kt`

**Interfaces:**
- Produces: `MovixMetadata.qualityFromJ1f(JSONObject): SearchQuality?`
- Produces: `MovixMetadata.score(JSONObject): Score?`
- Produces: `MovixMetadata.playerLabels(JSONObject): List<String>`

- [ ] **Step 1: Write failing quality tests**

```kotlin
@Test fun cameraLabelWinsOverHdResolution() {
    val root = JSONObject("""{"players":{"vf":[{"label":"TS VF HD"}]}}""")
    assertEquals(SearchQuality.Telesync, MovixMetadata.qualityFromJ1f(root))
}

@Test fun verifiedFhdMapsToHd() {
    val root = JSONObject("""{"players":{"vf":[{"label":"VF FHD"}]}}""")
    assertEquals(SearchQuality.HD, MovixMetadata.qualityFromJ1f(root))
}

@Test fun missingLabelsStayUnknown() {
    assertNull(MovixMetadata.qualityFromJ1f(JSONObject("""{"players":{}}""")))
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `gradle :Movix:testDebugUnitTest --tests com.lagradost.MovixMetadataTest`

Expected: compilation fails because `MovixMetadata` does not exist.

- [ ] **Step 3: Implement minimal parser**

Flatten `players` arrays, read explicit `label` values, parse CAM-family tokens before non-camera tokens, choose a verified non-camera result when one exists, and return null for absent/generic metadata.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `gradle :Movix:testDebugUnitTest --tests com.lagradost.MovixMetadataTest`

Expected: all Movix metadata tests pass.

### Task 3: Enrich Movix Provider

**Files:**
- Modify: `Movix/src/main/kotlin/com/lagradost/MovixProvider.kt`
- Modify: `Movix/build.gradle.kts`
- Test: `Movix/src/test/kotlin/com/lagradost/MovixMetadataTest.kt`

**Interfaces:**
- Consumes: `MovixMetadata.qualityFromJ1f`
- Consumes: CloudStream `Score`, `Actor`, `addActors`, `addTrailer`, `addImdbId`, and `addTMDbId`

- [ ] **Step 1: Extend tests for TMDB card/detail fields**

Add fixtures asserting score conversion, trailer selection, cast mapping inputs, status mapping, and episode metadata parsing.

- [ ] **Step 2: Verify new tests fail**

Run: `gradle :Movix:testDebugUnitTest`

Expected: new helper methods are missing.

- [ ] **Step 3: Add card score and bounded quality lookup**

Set `score = Score.from10(vote_average)` for every TMDB card. For movie cards only, query `/api/j1f/movie/{tmdbId}` with Movix headers, a short timeout, bounded batches, and TTL cache; set `quality` only when `MovixMetadata` returns a value.

- [ ] **Step 4: Add complete movie and TV metadata**

Request TMDB details with `credits,videos,recommendations,images,external_ids,release_dates` for movies and `credits,videos,recommendations,images,external_ids,content_ratings` for TV. Map optional values into CloudStream responses and fetch each non-zero TV season for detailed episodes.

- [ ] **Step 5: Run Movix tests**

Run: `gradle :Movix:testDebugUnitTest`

Expected: all tests pass, including existing packed-player tests.

### Task 4: Aggregate and Enrich French-Stream

**Files:**
- Modify: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`
- Create: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamTmdb.kt`
- Test: `FrenchStreamProvider/src/test/kotlin/com/lagradost/FrenchStreamMetadataTest.kt`

**Interfaces:**
- Consumes: Task 1 parser functions
- Produces: one real `TvSeriesLoadResponse` containing all discovered seasons
- Produces: language-tagged playback sources from one episode payload

- [ ] **Step 1: Add failing season aggregation fixtures**

Use HTML snippets for `Silo - Saison 1/2/3` and JSON snippets for VF/VOSTFR. Assert canonical deduplication, real season numbers, and one merged episode payload per episode number.

- [ ] **Step 2: Verify RED**

Run: `gradle :FrenchStreamProvider:testDebugUnitTest`

Expected: season discovery/merge parser methods are missing.

- [ ] **Step 3: Add TMDB resolver**

Search TMDB using normalized French title and optional year, reject incompatible candidates, cache accepted results, and fetch full details. Failures return null and preserve site data.

- [ ] **Step 4: Deduplicate cards and add score/quality**

Group series cards by normalized title, keep the newest season URL, set explicit site quality through the parser, and enrich accepted cards with TMDB score.

- [ ] **Step 5: Merge sibling seasons**

On TV load, search French-Stream for the canonical title, validate sibling titles, fetch each season page and `ep-data.php`, and build episodes with real season numbers. Merge VF/VOSTFR URLs into one payload.

- [ ] **Step 6: Preserve language during playback**

Parse merged payloads in `loadLinks`, process all language groups, and prefix emitted source names with `VF` or `VOSTFR`. Keep legacy movie/API payload paths supported.

- [ ] **Step 7: Add full TMDB detail metadata**

Map score, cast photos/roles, recommendations, trailer, logo, runtime, status, content rating, IMDb ID, and TMDB ID when the match is accepted.

- [ ] **Step 8: Run French-Stream tests**

Run: `gradle :FrenchStreamProvider:testDebugUnitTest`

Expected: all tests pass.

### Task 5: Version, Full Verification, and Publication

**Files:**
- Modify: `Movix/build.gradle.kts`
- Modify: `FrenchStreamProvider/build.gradle.kts`
- Modify: `README.md` only if visible feature documentation needs correction

- [ ] **Step 1: Increment versions**

Set Movix `version` and `versionCode` from 6 to 7. Set French-Stream from 4 to 5.

- [ ] **Step 2: Run complete local verification with JDK 17**

```powershell
gradle :Movix:testDebugUnitTest :FrenchStreamProvider:testDebugUnitTest
gradle makePluginsJson
```

Expected: `BUILD SUCCESSFUL`, Movix v7 and French-Stream v5 `.cs3` artifacts generated.

- [ ] **Step 3: Run live smoke checks**

Verify TMDB details, Movix J1F labels, Movix FStream playback payload, French-Stream season search, and French-Stream `ep-data.php` responses still match implemented schemas.

- [ ] **Step 4: Commit and push implementation**

```powershell
git add Movix FrenchStreamProvider README.md docs/superpowers/plans/2026-07-18-movix-frenchstream-metadata.md
git commit -m "feat: enrich Movix and French-Stream metadata"
git push origin main
```

- [ ] **Step 5: Verify GitHub publication**

Wait for `Build and Publish` to succeed. Verify public `builds/plugins.json` reports Movix version 7 and French-Stream version 5, then verify both raw `.cs3` URLs return HTTP 200 and changed hashes.
