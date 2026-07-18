# MovFS CloudStream Shortcode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the complete French-Stream and Movix repository installable in CloudStream by entering `MovFS`.

**Architecture:** Use CloudStream's built-in Cuttly shortcode resolution. Reserve `https://cutt.ly/MovFS` in an owner-controlled Cuttly account and redirect it to the existing public `repo.json`; no provider code changes are required.

**Tech Stack:** Cuttly, CloudStream repository JSON, GitHub raw content, PowerShell, GitHub Actions

## Global Constraints

- `MovFS` installs the complete repository containing French-Stream and Movix.
- The destination is exactly `https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json`.
- The full GitHub URL remains documented as a fallback.
- Cuttly account ownership remains with the repository owner.
- FrenchStreamProvider and Movix source code remain unchanged.

---

### Task 1: Reserve and Verify the Cuttly Shortcode

**Files:**
- Modify: none
- Test: public HTTP responses from Cuttly and GitHub

**Interfaces:**
- Consumes: owner-authenticated Cuttly account
- Produces: `https://cutt.ly/MovFS` returning an HTTP redirect to the public `repo.json`

- [ ] **Step 1: Open the Cuttly login page in a headed browser**

Run:

```powershell
agent-browser --headed open https://cutt.ly/login
agent-browser snapshot -i
```

Expected: the Cuttly login form is visible and the repository owner can authenticate without sharing credentials.

- [ ] **Step 2: Create the short link**

In the authenticated Cuttly dashboard, create a short link with this exact destination:

```text
https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
```

Then edit its custom alias to exactly:

```text
MovFS
```

Expected: the dashboard reports the public link as `https://cutt.ly/MovFS`.

- [ ] **Step 3: Verify the redirect without following it**

Run:

```powershell
curl.exe -sS -D - -o NUL --max-redirs 0 https://cutt.ly/MovFS
```

Expected: HTTP `301`, `302`, `307`, or `308`, with a `Location` header equal to the exact public `repo.json` URL.

- [ ] **Step 4: Verify both public plugins**

Run:

```powershell
$repo = Invoke-RestMethod https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
$plugins = Invoke-RestMethod $repo.pluginLists[0]
$plugins | Select-Object name, version, status
```

Expected: one entry named `FrenchStreamProvider` and one entry named `Movix`, both with status `3`.

### Task 2: Document and Publish the Shortcode

**Files:**
- Modify: `README.md`
- Test: Markdown content and public GitHub raw README

**Interfaces:**
- Consumes: verified `MovFS` redirect from Task 1
- Produces: installation instructions recommending `MovFS` with the full URL as fallback

- [ ] **Step 1: Update installation instructions**

Replace the current installation URL-only instructions with:

````markdown
1. Ouvrir CloudStream.
2. Aller dans **Paramètres > Extensions > Ajouter un dépôt**.
3. Dans le champ URL, saisir le code court :
   ```
   MovFS
   ```
4. Valider puis installer **French-Stream** et/ou **Movix**.

Si le code court est temporairement indisponible, utiliser l'URL complète :

```text
https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json
```
````

- [ ] **Step 2: Verify scope and formatting**

Run:

```powershell
git diff --check
git diff -- README.md
git status -sb
```

Expected: only `README.md` is modified, with no whitespace errors.

- [ ] **Step 3: Commit and push**

Run:

```powershell
git add README.md
git commit -m "Document MovFS repository shortcode"
git push origin main
```

Expected: `main` and `origin/main` point to the same new commit.

- [ ] **Step 4: Verify the public README and repository artifacts**

Run:

```powershell
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$readme = Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/README.md?x=$stamp"
$readme.Content | Select-String "MovFS"
$plugins = Invoke-RestMethod "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/builds/plugins.json?x=$stamp"
$plugins | Select-Object name, version, status
```

Expected: the public README contains `MovFS`, and public `plugins.json` still lists FrenchStreamProvider and Movix.
