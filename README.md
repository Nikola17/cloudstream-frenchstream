# FrenchStream Addon for CloudStream

Addon CloudStream permettant d'accéder aux films et séries de **French-Stream**.

## Fonctionnalités

- Films et Séries TV
- Recherche intégrée
- Fallback automatique d'URL (`french-stream.pink` → `fstream.info`)
- Episodes VF et VOSTFR pour les séries

## Installation

1. Ouvrir CloudStream
2. Aller dans **Paramètres > Extensions > Ajouter un dépôt**
3. Copier l'URL du fichier `repo.json` :
   ```
   https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
   ```

## Build

```bash
./gradlew :FrenchStreamProvider:build
```

## URLs supportées

- Primaire : `https://french-stream.pink`
- Fallback : `https://fstream.info`
