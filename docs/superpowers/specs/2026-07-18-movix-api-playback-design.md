# Movix API Playback Design

## Goal

Restore Movix movie and TV playback in CloudStream by using the real player URLs returned by the current Movix API instead of the JavaScript-only Frembed wrapper.

## Scope

- Change only the Movix plugin.
- Keep catalog, search, metadata, and French-Stream behavior unchanged.
- Use `https://api.movix.show` with Movix `Origin`, `Referer`, and browser `User-Agent` headers.
- Bump the Movix plugin to version 5 so CloudStream refreshes the package.

## Data Flow

For movies, request `/api/fstream/movie/{tmdbId}` and parse every `url` under `players.VFQ`, `players.VFF`, `players.VOSTFR`, and `players.Default`.

For TV episodes, request `/api/fstream/tv/{tmdbId}/season/{season}` and parse every `url` under `episodes.{episode}.languages`.

Pass each distinct player URL to CloudStream's `loadExtractor`. If no FStream player is extractable, use Movix-owned fallback responses: `/api/links/movie/{tmdbId}` plus `/api/wiflix/movie/{tmdbId}` for movies, and `/api/wiflix/tv/{tmdbId}/{season}` for TV episodes.

## Error Handling

Each API request and each extractor call is isolated. One unavailable endpoint or unsupported host must not stop remaining candidates. `loadLinks` returns `true` only when at least one extractor emits a link.

## Tests

Keep JSON parsing in a small pure Kotlin object. Unit tests cover FStream movies, FStream TV episodes, custom movie links, Wiflix movies, malformed payloads, and URL deduplication. Final verification includes the unit test task, Kotlin compilation, live API checks, GitHub Action success, and public `plugins.json` plus `Movix.cs3` version checks.
