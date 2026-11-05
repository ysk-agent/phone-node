# Build sessions on GitHub Actions

Why: a GitHub-hosted runner is created empty for every job, so a
normal CI build pays the environment setup (JDK, Android SDK, Gradle
dependencies, ~110 MB of pinned binary assets) every time. During
active development that setup dominates the round trip. A build
session pays it once and then accepts further checkouts of this
project for a bounded window.

## How it works

- `.github/workflows/build-session.yml` is dispatched manually
  (`workflow_dispatch`, input `session_minutes`, default 30). One
  session at a time (Actions concurrency group). Job timeout is
  60 minutes; the session never restarts or extends itself.
- Setup happens once per session: JDK 17, Android SDK platform 35,
  Gradle (with the shared `actions/cache`-backed Gradle home and the
  pinned asset inputs in `ci-dl/`, keyed by `ci/assets.txt`), and
  staging of the binary assets into a template app tree
  (`ci/fetch-assets.sh`, `ci/stage-assets.sh` — every input is pinned
  by SHA-256 in `ci/assets.txt`).
- `ci/session-loop.sh` then creates a prerelease named
  `session-<run-id>` with a `session.json` asset. The existence of
  that release is the ready signal. The loop polls the
  `build/inbox` branch every 15 seconds; the commit that was already
  there when the session started is not built.
- Each new inbox commit is built in a detached worktree with the
  staged assets applied (`ci/apply-staged.sh`, `ci/build.sh`). The
  Gradle daemon stays alive inside the job, so repeat builds also
  skip JVM and daemon startup. Results are uploaded to the session
  release as assets: `phonenode-app-<short>.apk`,
  `phonenode-helper-<short>.apk`, `build-<short>.log` and
  `result-<short>.json` (status, timings, APK sizes and SHA-256).
- Checkouts are accepted for `session_minutes` minutes from
  readiness. A build still running at the deadline finishes (bounded
  by the per-build timeout), then the loop uploads
  `session-closed.json` and exits.
- Signing: APKs are re-signed with the debug keystore stored as the
  `SIGNING_KEYSTORE_B64` repository secret (plus `SIGNING_STORE_PASS`,
  `SIGNING_KEY_PASS`, `SIGNING_KEY_ALIAS`), so a session-built APK
  installs as an update over a manually built one.

## Client

`ci/request-build.py` performs the whole round trip from the Muse
VM: it dispatches a session if none is active, waits for the ready
signal, snapshots the publishable files of the working tree onto
`build/inbox`, waits for `result-<short>.json`, downloads the APKs
and verifies their SHA-256 against the result file.

    python3 ci/request-build.py [--out DIR] [--session-minutes 30]

## Boundaries

- The inbox branch is an input queue, not a source branch: it is
  force-pushed by the client and its history has no meaning.
- Only accounts with write access can push the inbox branch, so a
  public repository does not mean public build submission.
- Sessions are for this project's own builds. There is deliberately
  no auto-restart: keeping a runner alive indefinitely would turn CI
  into a general-purpose server, which is outside the intended use.
- Session releases accumulate; delete old `session-*` prereleases
  and their APK assets when they are no longer needed.
