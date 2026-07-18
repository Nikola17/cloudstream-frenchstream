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

- French-Stream : `https://french-stream.pink`, `https://fstream.info`
- Movix : `https://movix.date`, `https://movix.show`
  - Si les domaines changent, le provider tente de récupérer l'adresse officielle depuis `https://movix.online/`.
  - Les catalogues Movix utilisent TMDB, comme le site Movix, et les lecteurs proviennent de l'API Movix actuelle : FStream en priorité, puis liens personnalisés/Wiflix en fallback.
