# GooseFlix app (fork of Wholphin)

Read **FORK.md** first: it lists everything this fork changes, where the work lives and how
releases reach TVs and Google Play.

## Starting a session

1. **`gooseflix` is the fork's main branch.** Start your session's branch from
   `origin/gooseflix`, not `main`, which is still upstream Wholphin's code.
   - Every push builds a release named after its branch. Scuffed installs the **`gooseflix`**
     release on the TVs, so a session branch's build is a test build nobody gets automatically.
   - **To ship**, merge or fast-forward your branch into `gooseflix`; it reaches every sideloaded TV
     within ~15 minutes of CI finishing. Do that when the owner asks to ship or deploy.
2. **Check open app issues** in the GooseFlix repo (titles start "App: "), via GitPilot or GitHub:
   `curl "https://pilot.grit.bot/api/issues?repo=GooseFlix&state=open" -H "Authorization: Bearer $GITPILOT_API_TOKEN"`.
3. GooseFlix's own conventions (deployment window, credentials, plan-first) are in the GooseFlix
   repo's `.claude/GooseFlix.md`, and they apply here too.

## Working

- Build and test with `-PforkBuild`, which sets `BuildConfig.GOOSEFLIX`. Run targeted tests
  (`--tests '*Name'`): the full suite is slow enough to time out.
- Don't use `pkill -f` with a pattern that matches your own shell command; it kills the shell.
- Keep fork code in its own files and gate small edits to upstream files on `BuildConfig.GOOSEFLIX`.
  New proto fields are numbered 500+. See FORK.md, Conventions.
- **When a fork feature lands, add it to FORK.md** in the same commit.

## Shipping

- A push builds a signed release in CI (about 15 minutes). The release title carries the version.
- Sideloaded TVs: Scuffed installs the `gooseflix` release and serves the update feed. Shipping is
  merging into `gooseflix`; there's no other deploy step for the app.
- Google Play: the owner uploads `app-appstore-release.aab` in Play Console (Closed testing → Alpha).
  Play needs minSdk 24 and the same signing key, both already set.
- Never put the Jellyfin API key, the review account's password or any token in a file or commit.
