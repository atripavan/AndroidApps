# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workspace layout

This is a **multi-app Android workspace**. Each app is a self-contained Gradle project in its
own top-level directory; the repo root holds only shared planning docs and git config.

| Directory | What it is |
|-----------|------------|
| `PAtriFileFinder/` | Personal on-device document indexer/searcher (Kotlin, Compose, Hilt, Room). Has its own `CLAUDE.md` with full architecture and build commands. |

More apps will be added as sibling directories over time. When you start work, **`cd` into the
relevant app directory** — that's where each app's `gradlew`, `settings.gradle.kts`, and its
own `CLAUDE.md` live. Claude Code automatically loads a subdirectory's `CLAUDE.md` when you
work on files inside it, so app-specific guidance (build/test commands, architecture, gotchas)
belongs in that app's file, not here.

## Conventions for apps in this workspace

- **Kotlin + Jetpack Compose (Material 3)** with MVVM + Repository and Hilt DI is the default
  stack; follow the existing app's patterns when adding a new one.
- **Gradle version catalog**: pin dependency versions in each app's
  `gradle/libs.versions.toml` and reference them via `libs.*`. Prefer **KSP** over kapt for
  annotation processing.
- **On-device / privacy first**: these are personal apps not intended for the Play Store, and
  at least one (PAtriFileFinder) has a hard rule that no user data ever leaves the device.
  Don't introduce network, analytics, or cloud dependencies without an explicit reason — check
  the app's own `CLAUDE.md` for its constraints before adding anything outbound.

## Adding a new app

Create a new top-level directory for it, give it its own Gradle wrapper and `CLAUDE.md`
(documenting its build commands and architecture), and add a row to the table above.
