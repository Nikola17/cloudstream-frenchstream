# Addons CloudStream FR

Repo CloudStream contenant des providers FR, dont **French-Stream** et **Movix**.

## Fonctionnalités

- Films et Séries TV
- Recherche intégrée
- Catalogues par catégories
- Fallback automatique d'URL pour les miroirs supportés
- Episodes VF et VOSTFR pour les séries

## Installation

1. Ouvrir CloudStream.
2. Aller dans **Paramètres > Extensions > Ajouter un dépôt**.
3. Dans le champ URL, saisir le code court :
   ```
   MovFS
   ```
4. Valider puis installer **French-Stream** et/ou **Movix**.

Si le code court est temporairement indisponible, utiliser l'URL complète :

```
https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
```

## Build

```bash
./gradlew :FrenchStreamProvider:build
./gradlew :Movix:build
```

## URLs supportées

- French-Stream : `https://fs26.lol`, `https://fs016.lol`, `https://fs16.lol`, `https://french-stream.pink`, `https://french-stream.net`, `https://fstream.info`
  - Le domaine change souvent (`fsNN.lol`) : si tous les miroirs connus échouent, le provider lit « l'URL éphémère du moment » sur le portail `https://fstream.info/` et bascule automatiquement.
  - Les films encore au cinéma (badge CAM/TS/HDCAM) sont masqués : ce ne sont que des captations en salle.
- Movix : `https://movix.tokyo`, `https://movix.miami`, `https://movix.date`, `https://movix.show`, `https://movix.cash`
  - Si les domaines changent, le provider récupère l'adresse officielle depuis `https://movix.online/` (mise en cache 30 min).
  - Les catalogues Movix utilisent TMDB (films, séries, plateformes Netflix/Disney+/Prime Video/Apple TV+, animes), et les lecteurs proviennent de l'API Movix (`api.movix.show`, `api.movix.miami`) : résolution parallèle FStream en priorité (VFF/VF d'abord, meilleure qualité d'abord), puis liens personnalisés et Wiflix en fallback. Les liens média directs (mp4/m3u8), les flux XOR `fsvid` et les hébergeurs packés/directs (`vidzy`, `uqload`, `kokoflix`, `trakx`, `playmogo`, `vidara`, `voe`…) sont résolus en filtrant les fausses vidéos `/troll/`.
  - Les films encore au cinéma (sans sortie digitale/physique TMDB ou uniquement en CAM) sont retirés du catalogue.
- French-Stream TV : événements/matchs via **BZH-Sport** (`https://bzzh.lat`, `https://bzh.lat`, `https://volkamax.club`), chaînes TV via **WITV** (`https://witv.channel`, `https://witv.online`, `https://witv.team`, `https://witv.football`, `https://witv.space`).
  - Accueil : Matchs en direct, Matchs aujourd'hui, Matchs demain, **Matchs à venir** (BZH-Sport, foot en premier, triés par proximité live & intérêt), puis Toutes les chaînes et les 9 catégories WITV (182 posters studio HD `960×540` dans `assets/channels/`).
  - Si le domaine BZH-Sport tombe, l'adresse à jour est récupérée sur `https://ewenethan22bzh.chatango.com/` puis `https://rentry.co/alpagang`.
  - Chaque événement est illustré par le visuel de son sport (`assets/sports/`, générés par `tools/generate_sport_posters.py` à partir des pictogrammes [Twemoji](https://github.com/jdecked/twemoji), licence CC-BY 4.0).
- French-Manga : `https://w16.french-manga.net`, `https://french-manga.net` (résolution parallèle VF > VOSTFR, meilleure qualité d'abord, filtre anti-`/troll/`, recommandations et cache d'épisodes).

## Priorités de lecture

Sur tous les addons : **VF (doublage français) d'abord**, puis VFQ, puis VOSTFR, et **meilleure résolution en premier** dans chaque langue.
