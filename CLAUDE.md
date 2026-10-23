# CLAUDE.md — portal-commons

## Verify

- `./gradlew check`: unit tests, Android Lint, type-resolved detekt, ktlint via kotlinter (Gradle scripts too).
- Fix style: `./gradlew formatKotlin`.
- After changing dependencies: `./gradlew buildHealth` (CI fails on any advice).
- A compiler warning fails the build with only "warnings found and -Werror specified": rerun with `--info` to see it.

## Rules (every warning is an error)

- No baselines. Suppress inline only, each with a reason comment: Lint `@SuppressLint` / `tools:ignore` /
  `//noinspection`; detekt `@Suppress("RuleId")`.
- Write no TODO/FIXME/STOPSHIP comments: detekt rejects them.
- Lines ≤ 120 columns.
- ktlint is configured in `.editorconfig` only (ADR 0001 in portal-apps).

## Bump together

- kotlinter 5.7.0 ↔ bundled ktlint 1.8.0 ↔ ktlint CLI version pinned in `.githooks/pre-commit`.
- detekt Gradle plugin ↔ `DETEKT_VERSION` in `.githooks/pre-commit`.
- `config/detekt/detekt.yml`, `.githooks/pre-commit`, `.shellcheckrc` are identical in portal-commons, portal-wake and
  portal-assistant (portal-apps shares `.shellcheckrc`): change all copies together.

## CI

- `main` takes changes by pull request; branch protection requires jobs `build`, `shellcheck`, `build-health`, so keep
  those names.

Setting up hooks, installing detekt-cli, finding reports, or changing CI: read `docs/tooling.md`.

## Agent skills

- Issues: markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.
- Triage: a `Status:` line with needs-triage, needs-info, ready-for-agent, ready-for-human or wontfix. See
  `docs/agents/triage-labels.md`.
- Domain: one `GLOSSARY.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.
- Grilling (/grill-with-docs): ask one question, then stop and wait for the user's answer. Start implementation only on
  the user's explicit go-ahead.
