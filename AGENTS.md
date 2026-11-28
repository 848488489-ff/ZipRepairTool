# Agent Rules

This file governs coding agents working in this repository. Any change that
violates these rules is void and must be reverted.

## Version and release control

1. **The maintainer owns the version number.** An agent must never decide the
   version number, tag name, or release contents on its own.
2. **When the maintainer explicitly asks to release**, the agent may update
   `version.json` to the specified version, build, commit, create the
   annotated tag, cut the GitHub Release, and upload the JAR — but only with
   the version number the maintainer gave.
3. **Never release without an explicit maintainer request.** Do not bump the
   version, tag, or publish a Release on your own initiative.
4. **The docs site version is dynamic.** `docs/app.js` fetches the latest
   release version from the GitHub API (`releases/latest`) and updates the
   download link and footer; an agent must **not hardcode a release version**
   in `docs/index.html` / `docs/index_zh.html`. When releasing, update the
   `VERSION` constant and rebuild the JAR only — the page version follows the
   API automatically.

## Other prohibited operations (never without an explicit maintainer instruction)

1. Create or delete branches, including release branches (e.g. gh-pages) and
   feature branches.
2. Modify release configuration — GitHub Pages source, release assets,
   workflows, or any other release-related setting.
3. Decide the release cadence.

## Principles

- Execute the assigned task; do not expand scope, decide versions, or publish
  on your own.
- When in doubt, ask the maintainer instead of deciding for them.
