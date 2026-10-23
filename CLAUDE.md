# CLAUDE.md — portal-commons

## Verification and hooks

Verify with `./gradlew check` (unit tests + Android Lint + detekt + ktlint via kotlinter, including the Gradle scripts).
Fix style with `./gradlew formatKotlin`. ktlint config is `.editorconfig` only (ADR 0001 in portal-apps); kotlinter
5.7.0 bundles ktlint 1.8.0, and `.githooks/pre-commit` refuses any other CLI version, so bump both together.

Android Lint runs in both modules (`com.android.lint` on the pure-JVM `:commons`) with `warningsAsErrors` and
`abortOnError`, so any warning fails `check`, which lints the debug and release variants. There is no
`lint-baseline.xml`: suppress inline only (`@SuppressLint`, `tools:ignore`, `//noinspection`), each with a reason
comment. The dependency-freshness checks (`AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`)
are disabled, because they query the network and fail whenever upstream ships a release. Reports:
`*/build/reports/lint-results*.html`.

detekt: `check` also runs detekt 2.0.0-alpha.6 (`dev.detekt`; still alpha, because detekt 1.x doesn't support AGP 9;
quality-tooling ticket 08 moves it to stable) with type resolution: `detektMain` and `detektTest`, every variant. The
config is detekt's defaults plus `config/detekt/detekt.yml`, identical in portal-commons, portal-wake and
portal-assistant (change all three together): the coroutine opt-ins (`GlobalCoroutineUsage`, `InjectDispatcher`,
`SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`), any TODO/FIXME/STOPSHIP comment forbidden, `@Composable`
exempt from `FunctionNaming`, and warnings as errors. There is no baseline: suppress inline only
(`@Suppress("RuleId")`), each with a reason comment. Lines are capped at 120: detekt's default `MaxLineLength`, and
`max_line_length = 120` in `.editorconfig` for ktlint. Reports: `*/build/reports/detekt/`.

With KGP 2.4, a compiler warning (`allWarningsAsErrors`) fails the build with only "warnings found and -Werror
specified"; run the build with `--info` to see the warnings themselves.

Hooks: `git config core.hooksPath .githooks` once per clone. `pre-commit` runs the `ktlint` and `detekt-cli` CLIs on
staged `.kt`/`.kts` files and `shellcheck` on staged shell scripts (`*.sh`, `*.command`, `.githooks/*`); it rejects on
findings and never auto-fixes. detekt runs there without type resolution (about a second); the type-resolved tasks run
in `check`. The hook refuses a `detekt-cli` other than the pinned `DETEKT_VERSION` (the Gradle plugin's version, so bump
both together). Homebrew only ships detekt 1.x: install the CLI by unzipping
`https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip` and putting its `bin/`
on your `PATH`. `pre-push` runs `./gradlew check`. `--no-verify` is the deliberate escape hatch. `.githooks/pre-commit`
and `.shellcheckrc` are identical copies in portal-commons, portal-wake and portal-assistant (portal-apps has the same
`.shellcheckrc`); change all of them together.

## CI

`.github/workflows/ci.yml` runs every check on a pull request to `main`: the `build` job runs `./gradlew check
assembleDebug`, plus the `shellcheck` and `build-health` jobs below. A push to `main` (a merge) or `workflow_dispatch`
runs only the `build` job, as `./gradlew assembleDebug test`. Branch protection on `main` requires a pull request
with `shellcheck`, `build-health` and `build` passing, so keep those job names. When `build` passes on `main`, it sends a `repository_dispatch` (`portal-commons-green`) that
re-runs CI in `portal-wake` and `portal-assistant`, because both build against `portal-commons@main`. This needs the
`APP_DISPATCH_TOKEN` secret: a fine-grained PAT with Actions and Contents write on the two app repos.

A `shellcheck` job lints every tracked shell script with the runner's preinstalled shellcheck. shellcheck isn't
part of `./gradlew check`: the hook and this job cover it.

A separate `build-health` job runs `./gradlew buildHealth` (the `com.autonomousapps.dependency-analysis` plugin)
and fails on any dependency advice: unused, used-but-undeclared or wrongly-configured dependencies. It is not part
of `check` or the hooks, because the advice changes rarely and the analysis is slow. Run `./gradlew buildHealth`
yourself after changing dependencies; the report is at `build/reports/dependency-analysis/build-health-report.txt`.

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Grilling (/grill-with-docs)

Ask one question at a time, then STOP and wait for the user's answer. Don't answer your own questions, don't generate the next round ahead of the answer, and don't begin implementation without the user's explicit permission.
