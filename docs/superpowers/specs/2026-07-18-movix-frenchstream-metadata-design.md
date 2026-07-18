# Movix and French-Stream Metadata Enrichment Design

## Scope

Enrich both CloudStream providers without replacing their existing catalogs or playback pipelines.

Required behavior:

- show TMDB scores on home and search cards and on detail pages;
- show cast members with profile photos and character names;
- expose trailers, recommendations, runtime, status, content rating, logos, and external IDs when available;
- keep every TV season on one CloudStream detail page;
- populate episode title, image, overview, date, runtime, and score;
- expose HD/CAM-family card badges only when source metadata supports them;
- keep current Movix and French-Stream playback behavior working.

## Architecture

Each plugin keeps its own provider and playback implementation. Metadata code is split into focused, testable parser/model helpers rather than coupled to extractor code.

Movix already uses TMDB IDs as catalog identifiers. It can enrich cards directly from TMDB list responses and detail pages from TMDB detail, credits, videos, recommendations, images, external IDs, release/content ratings, and season endpoints.

French-Stream uses site URLs. A resolver normalizes titles by removing season and language suffixes, searches TMDB, and accepts only a title/year-compatible match. Failure to match never blocks loading: site metadata remains the fallback.

## Cards

All successful TMDB mappings set `SearchResponse.score` with `Score.from10`. CloudStream controls whether the score is visible through its own display setting.

French-Stream cards use the site's explicit quality label when present. Movix movie cards may use a bounded, cached source-label lookup. Quality parsing follows this precedence:

1. explicit CAM/HDCAM/HDTS/TS labels map to their camera-release quality;
2. explicit 1080/FHD/HD/WEB-DL/WEBRip/BluRay/4K labels map to the matching non-camera quality;
3. if at least one verified non-camera HD source exists, use that best quality;
4. if only camera sources exist, show the camera-release badge;
5. unknown or generic source data produces no badge.

Lookups have short timeouts, bounded concurrency, and in-memory TTL caching. Metadata lookup failure returns the original card without delaying the catalog indefinitely.

French-Stream home and search results are deduplicated by normalized series title. The selected URL remains a real site URL so existing navigation remains compatible.

## Detail Pages

Both providers add:

- localized poster, backdrop, and logo;
- TMDB score;
- cast photos and character names;
- genres, plot, year, runtime, show status, and French content rating when available;
- YouTube trailer;
- TMDB recommendations;
- IMDb and TMDB sync IDs.

All fields are optional. Missing TMDB data must not prevent the original site detail page from loading.

## Seasons and Episodes

Movix fetches every non-special TMDB season and returns all episodes in one `TvSeriesLoadResponse`. Each episode carries its real season and episode numbers plus available metadata.

French-Stream treats titles such as `Silo - Saison 1`, `Silo - Saison 2`, and `Silo - Saison 3` as one series. Opening any season searches the site for sibling season pages, validates the normalized title, fetches their `ep-data.php` payloads, and merges them into one response.

French-Stream no longer models VF and VOSTFR as season 1 and season 2. One CloudStream episode represents one real season/episode. Its serialized playback payload contains available language-specific links. During playback, emitted source names include the language so users can choose VF or VOSTFR.

If sibling-season discovery fails, the opened season still loads with its real season number.

## Performance and Failure Handling

- TMDB and quality results use bounded in-memory TTL caches.
- Season requests run concurrently with a conservative limit.
- Per-card source probes use short timeouts and fail open.
- TMDB matching uses normalized title plus year checks; ambiguous matches are rejected.
- Extractor and packed-player logic remains separate from metadata changes.
- No inferred quality is derived from release date, popularity, rating, or video resolution alone.

## Additional Features Selected from Reference Repositories

The implementation adopts only features relevant to these providers: detailed episode metadata, cast photos, trailer, recommendations, logos, status, duration, content rating, sync IDs, bounded concurrency, caching, and graceful metadata fallback.

Large multi-provider source registries, account systems, watch parties, debrid configuration, and unrelated settings are out of scope because they would increase fragility without serving this request.

## Testing

Unit tests cover:

- French title normalization and season extraction;
- French-Stream season-result deduplication and sibling validation;
- TMDB candidate matching and ambiguous-match rejection;
- VF/VOSTFR episode merge and serialized playback payload parsing;
- quality precedence for CAM, HDCAM, TS, HD, FHD, WebRip, BluRay, and unknown labels;
- Movix TMDB card score mapping and detailed season episode mapping;
- existing Movix packed-player parsing and French-Stream playback parsing regressions.

Verification includes both plugin test suites, plugin builds with JDK 17, live metadata/API smoke checks, GitHub Action publication, and public `builds/plugins.json` plus raw `.cs3` artifact checks.

## Publication

Both plugin versions are incremented. Changes are committed and pushed to `main`. Completion requires the `Build and Publish` workflow to succeed and public plugin metadata/artifacts to expose the new versions.
