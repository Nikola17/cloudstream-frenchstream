# Addons CloudStream FR

Repo CloudStream contenant des providers FR, dont **French-Stream** et **FilmoFlix**.

## Fonctionnalités

- Films et Séries TV
- Recherche intégrée
- Catalogues par catégories
- Fallback automatique d'URL pour les miroirs supportés
- Episodes VF et VOSTFR pour les séries

## Installation

1. Ouvrir CloudStream
2. Aller dans **Paramètres > Extensions > Ajouter un dépôt**
3. Copier l'URL du fichier `repo.json` :
   ```
   https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
   ```
4. Rechercher **FilmoFlix** dans les extensions CloudStream.

## Build

```bash
./gradlew :FrenchStreamProvider:build
./gradlew :FilmoFlix:build
```

## URLs supportées

- French-Stream : `https://french-stream.pink`, `https://fstream.info`
- FilmoFlix : `https://filmoflix.support`, `https://filmoflix.markets`, `https://filmoflix.money`, `https://filmoflix.lifestyle`
