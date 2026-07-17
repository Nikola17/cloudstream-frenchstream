# Movix API Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Movix movie and TV links by parsing the current Movix API player payloads.

**Architecture:** Add a pure JSON parser beside `MovixProvider`, then make `loadLinks` query FStream first and Movix fallbacks only when needed. Keep all catalog and French-Stream code unchanged.

**Tech Stack:** Kotlin, Android Gradle plugin, CloudStream SDK, `org.json`, JUnit 4

## Global Constraints

- Only Movix production behavior may change.
- API base is `https://api.movix.show`.
- Movix plugin version becomes 5.
- Publication requires commit, push, successful `Build and Publish`, and public artifact verification.

---

### Task 1: Regression parser tests

**Files:**
- Create: `Movix/src/test/kotlin/com/lagradost/MovixLinkParserTest.kt`
- Modify: `Movix/build.gradle.kts`

**Interfaces:**
- Consumes: representative Movix API JSON payloads.
- Produces: expectations for `MovixLinkParser.fstreamMovie`, `fstreamTv`, `customMovie`, `wiflixMovie`, and `wiflixTv`.

- [ ] Add JUnit and JVM `org.json` test dependencies.
- [ ] Write tests for movie groups, one TV episode, fallback payloads, malformed JSON, and duplicate URLs.
- [ ] Run `:Movix:testDebugUnitTest` and confirm failure because `MovixLinkParser` does not exist.

### Task 2: Parser and provider integration

**Files:**
- Create: `Movix/src/main/kotlin/com/lagradost/MovixLinkParser.kt`
- Modify: `Movix/src/main/kotlin/com/lagradost/MovixProvider.kt`

**Interfaces:**
- Consumes: `JSONObject` responses from current Movix endpoints.
- Produces: distinct HTTP player URLs passed to `loadExtractor`.

- [ ] Implement minimal parser methods needed by Task 1.
- [ ] Run `:Movix:testDebugUnitTest` and confirm all tests pass.
- [ ] Replace Frembed wrapper loading with FStream movie and TV requests.
- [ ] Add custom and Wiflix fallback requests when primary candidates yield no extractable link.
- [ ] Isolate endpoint and extractor failures with `runCatching`.

### Task 3: Version, verification, and publication

**Files:**
- Modify: `Movix/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Consumes: tested Movix source.
- Produces: public Movix version 5 `.cs3` package.

- [ ] Set Gradle `version` and Android `versionCode` to 5.
- [ ] Update README playback description.
- [ ] Run unit tests, Kotlin compilation, plugin packaging, and `git diff --check`.
- [ ] Confirm live movie and TV APIs still return player URLs.
- [ ] Commit only Movix files and these design records, then push `main`.
- [ ] Wait for `Build and Publish` and verify public `plugins.json` reports Movix version 5 and the public `.cs3` is reachable.
