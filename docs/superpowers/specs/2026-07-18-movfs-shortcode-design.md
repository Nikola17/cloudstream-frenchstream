# MovFS CloudStream Shortcode Design

## Goal

Allow users to add the complete CloudStream FR repository by entering `MovFS`
instead of the full GitHub `repo.json` URL. The repository continues to expose
both French-Stream and Movix.

## Resolution Flow

CloudStream treats a plain alphanumeric repository value as a Cuttly shortcode.
The resulting flow is:

1. The user enters `MovFS` in the repository URL field.
2. CloudStream requests `https://cutt.ly/MovFS` without following redirects.
3. Cuttly returns a redirect to the public repository manifest:
   `https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json`.
4. CloudStream parses that manifest and displays French-Stream and Movix.

No provider or CloudStream application code changes are required.

## Ownership

The `MovFS` alias must be created in a Cuttly account controlled by the repository
owner. This keeps the redirect recoverable and prevents dependence on an external
maintainer account. The free Cuttly plan supports custom aliases.

## Repository Changes

After the redirect is live, update `README.md` so installation recommends `MovFS`
and keeps the full `repo.json` URL as a fallback.

## Verification

Completion requires all of the following:

- `https://cutt.ly/MovFS` returns an HTTP redirect to the exact public `repo.json`.
- The destination manifest is reachable and contains the public `plugins.json` URL.
- Public `plugins.json` contains both FrenchStreamProvider and Movix.
- The README change is committed and pushed to `main`.

## Failure Handling

If `MovFS` cannot be reserved, use the first available case-sensitive fallback in
this order: `MovFSrepo`, then `MovFS26`. Do not replace the public GitHub URL; it
remains the permanent fallback if Cuttly is unavailable.
