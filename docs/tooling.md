# Tooling details

Reference moved out of `CLAUDE.md`; the hard rules stay there.

## Hooks

- Enable once per clone: `git config core.hooksPath .githooks`.
- `pre-commit` runs the `ktlint` and `detekt-cli` CLIs on staged `.kt`/`.kts`, and `shellcheck` on staged shell scripts
  (`*.sh`, `*.command`, `.githooks/*`). It rejects on findings and never auto-fixes. detekt runs there without type
  resolution (about a second); the type-resolved `detektMain`/`detektTest` run in `check`.
- `pre-push` runs `./gradlew check`. `--no-verify` is the deliberate escape hatch.
- Installing `detekt-cli`: Homebrew only ships detekt 1.x. Unzip
  `https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip` and put its `bin/` on
  `PATH`.

## Android Lint

- Runs in both modules (`com.android.lint` on the pure-JVM `:commons`), debug and release variants, with
  `warningsAsErrors` and `abortOnError`.
- `AndroidGradlePluginVersion`, `GradleDependency` and `NewerVersionAvailable` are disabled: they query the network and
  fail whenever upstream ships a release.

## detekt

- `dev.detekt` 2.0.0-alpha.6: alpha because detekt 1.x doesn't support AGP 9. Quality-tooling ticket 08 moves it to
  stable.
- Config is detekt's defaults plus `config/detekt/detekt.yml` (coroutine opt-ins, forbidden TODO-style comments,
  `@Composable` exempt from `FunctionNaming`, warnings as errors).
- Runs in its own process (`detekt.use.worker.api=true` in `gradle.properties`): in the Gradle daemon its type
  resolution kept reading `:commons-android`'s rebuilt classes jar as it was on the first run, so
  `:commons-android:detektDebugUnitTest` analysed tests against stale main classes and only warned "N compiler errors
  found during analysis" while `check` passed (`:commons` reads class directories and wasn't affected).

## Reports

- Lint: `*/build/reports/lint-results*.html`
- detekt: `*/build/reports/detekt/`
- Dependency analysis: `build/reports/dependency-analysis/build-health-report.txt`

## CI (`.github/workflows/ci.yml`)

- Pull request to `main`: `build` (`./gradlew check assembleDebug`), `shellcheck` (every tracked shell script, runner's
  preinstalled shellcheck) and `build-health` (`./gradlew buildHealth`, the `com.autonomousapps.dependency-analysis`
  plugin; fails on unused, used-but-undeclared or wrongly-configured dependencies). shellcheck and buildHealth are not
  part of `check`.
- Push to `main` or `workflow_dispatch`: only `build`, as `./gradlew assembleDebug test`.
- When `build` passes on `main`, it sends `repository_dispatch` (`portal-commons-green`) to re-run CI in `portal-wake`
  and `portal-assistant`, which build against `portal-commons@main`. Needs the `APP_DISPATCH_TOKEN` secret: a
  fine-grained PAT with Actions and Contents write on both app repos.
