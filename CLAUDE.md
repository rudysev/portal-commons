# CLAUDE.md — portal-commons

## Verification and hooks

Verify with `./gradlew check` (unit tests + ktlint via kotlinter, including the Gradle scripts). Fix style with
`./gradlew formatKotlin`. ktlint config is `.editorconfig` only (ADR 0001 in portal-apps); kotlinter 5.7.0 bundles
ktlint 1.8.0, and `.githooks/pre-commit` refuses any other CLI version, so bump both together.

Hooks: `git config core.hooksPath .githooks` once per clone. `pre-commit` runs the `ktlint` CLI on staged
`.kt`/`.kts` files and `shellcheck` on staged shell scripts (`*.sh`, `*.command`, `.githooks/*`); it rejects on
findings and never auto-fixes. `pre-push` runs `./gradlew check`. `--no-verify` is the deliberate escape hatch.
`.githooks/pre-commit` and `.shellcheckrc` are identical copies in portal-commons, portal-wake and portal-assistant
(portal-apps has the same `.shellcheckrc`); change all of them together.

## CI

`.github/workflows/ci.yml` runs `./gradlew check assembleDebug` on every push to `main` (and on
`workflow_dispatch`). When it passes on `main`, it sends a `repository_dispatch` (`portal-commons-green`) that
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
