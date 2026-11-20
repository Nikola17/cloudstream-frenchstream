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

- French-Stream : `https://fs26.lol`, `https://fs16.lol`, `https://french-stream.pink`, `https://fstream.info`
  - Le domaine change souvent (`fsNN.lol`) : si tous les miroirs connus échouent, le provider lit « l'URL éphémère du moment » sur le portail `https://fstream.info/` et bascule automatiquement.
  - Les films encore au cinéma (badge CAM/TS/HDCAM) sont masqués : ce ne sont que des captations en salle.
- Movix : `https://movix.miami`, `https://movix.date`, `https://movix.show`, `https://movix.cash`
  - Si les domaines changent, le provider tente de récupérer l'adresse officielle depuis `https://movix.online/`.
  - Les catalogues Movix utilisent TMDB, comme le site Movix, et les lecteurs proviennent de l'API Movix (`api.movix.show`, `api.movix.miami`) : FStream en priorité (VFF/VF d'abord, meilleure qualité d'abord), puis liens personnalisés, puis Wiflix en fallback. Les liens média directs (mp4/m3u8) et les hébergeurs packés (fsvid, kokoflix, trakx, playmogo, vidara, voe…) sont résolus directement.
  - Les films encore au cinéma (sortie récente sans sortie digitale/physique selon TMDB) sont retirés du catalogue.
- French-Stream TV : `https://fstv.lol`, `https://fstv.rest` (adresse à jour récupérée sur `https://fstream.info/` via le lien « Regarder La TV » si besoin)
  - Sources de secours BZH-Sport (ex Volkastream / OhQuelbut) : `https://bzzh.lat`, `https://bzh.lat`, `https://volkamax.club`, et WITV : `https://witv.online`, `https://witv.channel`, `https://witv.team`, `https://witv.football`, `https://witv.space`. Leurs liens sont ajoutés après ceux de French-Stream TV pour la même chaîne (sources FR d'abord, puis meilleure qualité).
  - Si ces domaines tombent, l'adresse à jour est récupérée sur `https://ewenethan22bzh.chatango.com/` puis `https://rentry.co/alpagang`.
  - Les sections « Événements sport » (en direct, aujourd'hui, demain) listent les matchs de BZH-Sport, les plus populaires en premier (Ligue des Champions, Ligue 1, F1, Premier League, équipe de France, gros clubs…).
  - Chaque événement est illustré par le visuel de son sport (`assets/sports/`, générés par `tools/generate_sport_posters.py` à partir des pictogrammes [Twemoji](https://github.com/jdecked/twemoji), licence CC-BY 4.0).
- French-Manga : `https://w16.french-manga.net`, `https://french-manga.net`

## Priorités de lecture

Sur tous les addons : **VF (doublage français) d'abord**, puis VFQ, puis VOSTFR, et **meilleure résolution en premier** dans chaque langue.
