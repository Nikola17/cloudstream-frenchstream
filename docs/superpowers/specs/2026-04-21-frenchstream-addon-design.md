# Spécification — Addon CloudStream FrenchStream

> **Date :** 2026-04-21  
> **Projet :** Addon CloudStream pour French-Stream (films + séries TV)  
> **Auteur :** Nico + AI  

---

## 1. Objectif

Créer un addon CloudStream fonctionnel permettant de parcourir, rechercher et lire des contenus (films et séries TV) depuis le site **French-Stream**, avec un mécanisme de **fallback automatique d'URL**.

---

## 2. Architecture

L'addon est un **module d'extension Kotlin** standard CloudStream, structuré comme suit :

```
FrenchStreamProvider/
├── build.gradle.kts
├── src/main/AndroidManifest.xml
└── src/main/kotlin/com/lagradost/
    ├── FrenchStreamProviderPlugin.kt   # Point d'entrée (@CloudstreamPlugin)
    └── FrenchStreamProvider.kt         # Logique principale (MainAPI)
```

### Responsabilités des fichiers

| Fichier | Rôle |
|---------|------|
| `FrenchStreamProviderPlugin.kt` | Enregistre le provider auprès du framework CloudStream |
| `FrenchStreamProvider.kt` | Implémente `MainAPI` : home, search, load, loadLinks, fallback URL |

---

## 3. Data Flow

### 3.1 Page d'accueil (`getMainPage`)

1. Requête HTTP GET sur `$mainUrl`
2. Parsing HTML (Jsoup) des sections de contenu
3. Extraction des posters, titres, URLs, années, types (film/série)
4. Retour d'une `HomePageResponse` avec plusieurs `HomePageList`

### 3.2 Recherche (`search`)

1. Requête HTTP POST/GET sur `$mainUrl/?do=search&subaction=search&story=$query`
2. Parsing des résultats dans une liste de `SearchResponse`
3. Détection du type (Movie vs TvSeries) via le titre ou la structure HTML

### 3.3 Chargement du détail (`load`)

1. Requête sur l'URL du contenu
2. Extraction : titre, synopsis, poster, année, genres, durée
3. **Si film** → `MovieLoadResponse` avec l'URL comme `data`
4. **Si série** → `TvSeriesLoadResponse` avec liste d'épisodes (VF / VOSTFR)

### 3.4 Extraction des liens (`loadLinks`)

1. Requête sur la page de l'épisode ou du film
2. Parsing des liens des players (dood, upstream, voe, etc.)
3. Passage des URLs aux extracteurs natifs de CloudStream via `loadExtractor()`

---

## 4. Gestion du Fallback d'URL

### 4.1 URLs

- **Primaire :** `https://french-stream.pink`
- **Fallback :** `https://fstream.info`

### 4.2 Mécanisme

- Au premier appel réseau (construction de l'objet ou première requête), l'addon tente d'atteindre l'URL primaire.
- Si la réponse est un échec (HTTP 4xx/5xx, timeout, DNS error), l'addon bascule automatiquement sur l'URL fallback.
- Une fois le fallback activé, il reste actif pour la durée de vie de l'instance du provider.
- Le fallback est **transparent** pour l'utilisateur : aucune action manuelle requise.

### 4.3 Implémentation proposée

```kotlin
override var mainUrl = "https://french-stream.pink"
private val fallbackUrl = "https://fstream.info"
private var isUsingFallback = false

private suspend fun safeRequest(url: String): NiceResponse {
    val response = app.get(url)
    if (!response.isSuccessful && !isUsingFallback) {
        isUsingFallback = true
        mainUrl = fallbackUrl
        return app.get(url.replace("https://french-stream.pink", fallbackUrl))
    }
    return response
}
```

---

## 5. Parsing HTML

### 5.1 Outil

**Jsoup** (inclus dans le SDK CloudStream).

### 5.2 Stratégie

- Utiliser des sélecteurs CSS simples et robustes.
- Pour chaque élément clé (titre, poster, lien), prévoir un **sélecteur principal** et un **sélecteur de secours** au cas où la structure du site évolue légèrement.
- Exemple :
  - Titre principal : `h1#s-title` ou `div.fheader h1`
  - Poster : `div.fposter img[src]` ou `meta[property=og:image]`

### 5.3 Détection du type (Film vs Série)

- **Méthode 1 :** Le titre contient le mot "saison" (insensible à la casse) → TvSeries.
- **Méthode 2 :** La page contient une liste d'épisodes (`div.elink`, `.episode-container`) → TvSeries.
- Sinon → Movie.

---

## 6. Séries TV — Gestion des épisodes

### 6.1 Versions linguistiques

French-Stream propose généralement deux versions :
- **VF** (Version Française)
- **VOSTFR** (Version Originale Sous-Titrée)

### 6.2 Structure des données

- Chaque épisode est un objet `Episode` avec :
  - `data` : l'URL de l'épisode (ou un identifiant interne)
  - `name` : "Épisode X"
  - `season` : numéro de saison
  - `episode` : numéro d'épisode

### 6.3 Passage à `loadLinks`

L'URL stockée dans `Episode.data` pointe soit :
- vers une page intermédiaire listant les players,
- soit directement vers le player.
Dans `loadLinks`, on parse cette page pour extraire les liens finaux et on les passe à `loadExtractor()`.

---

## 7. Films — Gestion des liens

Pour un film, la `data` de `MovieLoadResponse` est l'URL de la page du film.
Dans `loadLinks` :
1. Requête sur cette URL.
2. Extraction des liens des players depuis la page.
3. Appel à `loadExtractor(url, subtitleCallback, callback)` pour chaque lien trouvé.

---

## 8. Error Handling

| Scénario | Comportement |
|----------|--------------|
| Site primaire down | Bascule automatique sur le fallback |
| Timeout réseau | Retry silencieux (1-2 tentatives max) |
| Structure HTML modifiée | Retourne une liste vide / null (pas de crash) |
| Aucun lien trouvé | `loadLinks` retourne `false` ou vide (CloudStream affiche "Aucun lien") |
| Extracteur indisponible | CloudStream gère nativement le cas |

---

## 9. Ce qui est hors scope (YAGNI)

- ❌ Sous-domaine anime/manga (`french-manga.net`)
- ❌ Paramètres utilisateur avancés (choix de qualité, langue par défaut)
- ❌ Cache persistant côté addon
- ❌ Gestion des sous-titres externes (le site n'en fournit pas nativement)
- ❌ Support du casting Chromecast (géré par l'app CloudStream)
- ❌ Téléchargement offline (géré par l'app CloudStream)

---

## 10. Dépendances

Aucune dépendance externe supplémentaire. L'addon s'appuie sur :
- Le SDK CloudStream (`com.lagradost.cloudstream3`)
- Jsoup (inclus)
- OkHttp (inclus)

---

## 11. Critères de succès

- [ ] La page d'accueil affiche les catégories de French-Stream (Derniers Films, Séries, etc.)
- [ ] La recherche retourne des résultats pertinents
- [ ] Les films se chargent et les liens s'extraient
- [ ] Les séries listent bien les épisodes VF et VOSTFR
- [ ] Le fallback fonctionne quand le domaine principal est injoignable
- [ ] Aucun crash si le site change légèrement de structure
