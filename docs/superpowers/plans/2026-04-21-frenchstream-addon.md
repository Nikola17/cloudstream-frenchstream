# FrenchStream Addon — Plan d'Implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Créer un addon CloudStream fonctionnel pour French-Stream (films + séries) avec fallback URL automatique.

**Architecture:** Module Gradle Kotlin standard CloudStream avec un `MainAPI` implémentant la logique de parsing HTML (Jsoup), le fallback réseau, et l'extraction de liens vers les extracteurs natifs.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, CloudStream SDK, Jsoup

---

## Structure des fichiers

```
FrenchStreamAddon/
├── settings.gradle.kts
├── build.gradle.kts
└── FrenchStreamProvider/
    ├── build.gradle.kts
    ├── src/main/AndroidManifest.xml
    └── src/main/kotlin/com/lagradost/
        ├── FrenchStreamProviderPlugin.kt
        └── FrenchStreamProvider.kt
```

---

### Task 1: Fichiers de build et manifest

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `FrenchStreamProvider/build.gradle.kts`
- Create: `FrenchStreamProvider/src/main/AndroidManifest.xml`

- [ ] **Step 1: Créer `settings.gradle.kts`**

```kotlin
rootProject.name = "FrenchStreamAddon"
include("FrenchStreamProvider")
```

- [ ] **Step 2: Créer `build.gradle.kts` (racine)**

```kotlin
// Top-level build file
plugins {
    id("com.github.ben-manes.versions") version "0.51.0"
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

- [ ] **Step 3: Créer `FrenchStreamProvider/build.gradle.kts`**

```kotlin
version = 1

cloudstream {
    language = "fr"
    authors = listOf("Nico")
    description = "French-Stream provider for CloudStream"
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:-SNAPSHOT")
}
```

- [ ] **Step 4: Créer `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

---

### Task 2: Plugin Entry Point

**Files:**
- Create: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProviderPlugin.kt`

- [ ] **Step 1: Écrire le fichier plugin**

```kotlin
package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FrenchStreamProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FrenchStreamProvider())
    }
}
```

---

### Task 3: Provider — Structure, Fallback URL et Helpers

**Files:**
- Create: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`

- [ ] **Step 1: Écrire la structure de base avec le fallback**

```kotlin
package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FrenchStreamProvider : MainAPI() {
    override var mainUrl = "https://french-stream.pink"
    private val fallbackUrl = "https://fstream.info"
    override var name = "French-Stream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var isUsingFallback = false

    private suspend fun safeGet(url: String): NiceResponse {
        val response = app.get(url)
        if (!response.isSuccessful && !isUsingFallback) {
            isUsingFallback = true
            mainUrl = fallbackUrl
            val fallbackFullUrl = url.replace("https://french-stream.pink", fallbackUrl)
            return app.get(fallbackFullUrl)
        }
        return response
    }

    private fun toResult(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a.short-poster") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = element.selectFirst(".short-title")?.text()
            ?: anchor.attr("title")
            ?: return null
        var poster = fixUrlNull(element.selectFirst("img")?.attr("src"))
        if (poster == null || poster.startsWith("data:")) {
            poster = fixUrlNull(element.selectFirst("img")?.attr("data-src"))
        }
        val year = element.selectFirst(".date")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()

        val isSeries = title.contains("saison", ignoreCase = true)
                || href.contains("/series/", ignoreCase = true)
                || href.contains("/s-tv/", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }
}
```

---

### Task 4: Provider — Page d'accueil et Recherche

**Files:**
- Modify: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`

- [ ] **Step 1: Ajouter `mainPage` et `getMainPage`**

Ajouter à l'intérieur de la classe `FrenchStreamProvider` :

```kotlin
    override val mainPage = mainPageOf(
        "films" to "Derniers Films",
        "s-tv" to "Dernières Séries",
        "films/top-film" to "Top Films",
        "sries-du-moment" to "Séries du moment"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/${request.data}/page/$page"
        } else {
            "$mainUrl/${request.data}"
        }
        val doc = safeGet(url).document
        val items = doc.select("div.short").mapNotNull { toResult(it) }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }
```

- [ ] **Step 2: Ajouter `search`**

Ajouter à l'intérieur de la classe :

```kotlin
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=${query.replace(" ", "+")}"
        val doc = safeGet(url).document
        return doc.select("div.short").mapNotNull { toResult(it) }
    }
```

---

### Task 5: Provider — Chargement des Films

**Files:**
- Modify: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`

- [ ] **Step 1: Ajouter la branche film dans `load`**

Ajouter à l'intérieur de la classe :

```kotlin
    override suspend fun load(url: String): LoadResponse {
        val doc = safeGet(url).document
        val title = doc.selectFirst("h1#s-title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown"

        var poster = fixUrlNull(doc.selectFirst("div.fposter img")?.attr("src"))
        if (poster == null) {
            val posterRegex = Regex("""url\((https?://\S+)\)""")
            poster = posterRegex.find(doc.toString())?.groupValues?.get(1)
        }

        val description = doc.selectFirst("div.fdesc")?.text()
            ?: doc.selectFirst("#s-desc")?.ownText()
            ?: ""

        val year = doc.selectFirst("ul.flist-col li")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.release")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()

        val tags = doc.select("ul.flist-col li a").mapNotNull { it.text() }

        val isSeries = title.contains("saison", ignoreCase = true)
                || doc.select("div.elink").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        // TODO: séries dans la Task 6
        throw IllegalStateException("Séries non encore implémentées")
    }
```

---

### Task 6: Provider — Chargement des Séries

**Files:**
- Modify: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`

- [ ] **Step 1: Remplacer le `throw` par l'implémentation série dans `load`**

Remplacer le bloc `// TODO: séries...` par :

```kotlin
        val episodeLists = doc.select("div.elink")
        val vfEpisodes = mutableListOf<Episode>()
        val vostfrEpisodes = mutableListOf<Episode>()

        // VF — premier bloc
        episodeLists.firstOrNull()?.select("a")?.forEachIndexed { index, a ->
            val epNum = a.text().substringAfter("Episode").trim().toIntOrNull() ?: (index + 1)
            val data = fixUrl(url).plus("-episodenumber:$epNum")
            vfEpisodes.add(newEpisode(data) {
                name = "Épisode $epNum VF"
                episode = epNum
                season = 1
            })
        }

        // VOSTFR — deuxième bloc s'il existe
        if (episodeLists.size > 1) {
            episodeLists[1].select("a").forEachIndexed { index, a ->
                val epNum = a.text().substringAfter("Episode").trim().toIntOrNull() ?: (index + 1)
                val data = fixUrl(url).plus("-episodenumber:$epNum")
                vostfrEpisodes.add(newEpisode(data) {
                    name = "Épisode $epNum VOSTFR"
                    episode = epNum
                    season = 2
                })
            }
        }

        val allEpisodes = (vfEpisodes + vostfrEpisodes).filter { it.data.isNotBlank() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addSeasonNames(listOf("VF", "VOSTFR"))
        }
```

---

### Task 7: Provider — Extraction des Liens

**Files:**
- Modify: `FrenchStreamProvider/src/main/kotlin/com/lagradost/FrenchStreamProvider.kt`

- [ ] **Step 1: Ajouter `loadLinks` complet**

Ajouter à l'intérieur de la classe :

```kotlin
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isEpisode = data.contains("-episodenumber:")
        val pageUrl = if (isEpisode) data.split("-episodenumber:")[0] else data
        val doc = safeGet(pageUrl).document

        val links = if (isEpisode) {
            val wantedEpisode = data.split("-episodenumber:")[1]
            val episodeId = if (wantedEpisode == "1") "episode1" else "episode${wantedEpisode.toInt() + 32}"
            val divSelector = if (wantedEpisode == "1") "> div.tabs-sel " else ""
            doc.select("div#$episodeId > div.selink > ul.btnss $divSelector> li a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
        } else {
            doc.select("nav#primary_nav_wrap > ul > li > ul > li > a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
        }

        if (links.isEmpty()) {
            // Fallback de parsing si la structure change légèrement
            val altLinks = doc.select("div.selink a, div.fsctab a, .fplayer a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
            altLinks.forEach { loadExtractor(it, subtitleCallback, callback) }
            return altLinks.isNotEmpty()
        }

        links.forEach { loadExtractor(it, subtitleCallback, callback) }
        return links.isNotEmpty()
    }
```

---

### Task 8: Vérification Finale

**Files:**
- Test: compilation Gradle

- [ ] **Step 1: Vérifier la syntaxe et la cohérence**

Ouvrir le fichier `FrenchStreamProvider.kt` complet et s'assurer que :
1. Toutes les accolades sont fermées
2. Les imports sont présents en haut du fichier
3. Il n'y a pas de variables/types non définis

- [ ] **Step 2: Lancer le build Gradle**

Run:
```bash
./gradlew :FrenchStreamProvider:build
```

Expected: `BUILD SUCCESSFUL` (ou des warnings acceptables, mais pas d'erreurs de compilation Kotlin).

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "feat: add FrenchStream provider with fallback URL"
```

---

## Self-Review Checklist

- [ ] **Spec coverage :** Toutes les sections du spec (home, search, load film, load série, fallback, loadLinks) sont couvertes par une task.
- [ ] **Placeholder scan :** Aucun "TODO", "TBD", ou "fill in details" dans le code fourni.
- [ ] **Type consistency :** Les signatures `load`, `search`, `getMainPage`, `loadLinks` respectent l'interface `MainAPI` du SDK CloudStream. Les noms de variables (`mainUrl`, `fallbackUrl`, `isUsingFallback`) sont cohérents.

---

**Plan terminé.**
