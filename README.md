<div align="center">
  <img src="app/src/main/res/drawable/app_logo.png" alt="RPClient Logo" width="112" />

  # RPClient

  [简体中文](README_ZH.md) | English

  A local-first AI role-playing chat client for Android.

  Supports character cards, lorebooks, one-on-one chats, group chats, long-term summary memory, prompt inspection, story writing, portable full-data backup, and multiple LLM APIs.

  <p>
    <a href="https://play.google.com/store/apps/details?id=me.kafuuneko.rpclient">
      <img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" alt="Get it on Google Play" height="80" />
    </a>
    <a href="https://f-droid.org/packages/me.kafuuneko.rpclient/">
      <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80" />
    </a>
  </p>

  [Overview](#overview) · [Screenshots](#screenshots) · [Features](#features) · [Quick Start](#quick-start) · [Build](#build-from-source) · [Backup & Restore](#backup--restore) · [Contributing](#contributing)
</div>

## Overview

RPClient is built with Kotlin and Jetpack Compose to provide a complete and controllable AI role-playing experience on Android devices. Chat histories, characters, lorebooks, and app settings are stored locally by default, while model requests are sent directly to the model service selected by the user.

The project draws on SillyTavern's ecosystem for character cards, lorebooks, prompts, and Regex scripts, and provides corresponding import, export, and compatibility features. RPClient is not an Android port of SillyTavern, and some advanced fields and runtime behaviors may differ.

> [!WARNING]
> This project is under active development. Data structures, UI, and compatibility behavior may change. Keep original copies of important imported resources and make regular backups.

## Screenshots

<p align="center">
  <img src="metadata/images/home.jpg" alt="RPClient home screen" width="45%" />
  <img src="metadata/images/chat.jpg" alt="RPClient chat screen" width="45%" />
</p>
<p align="center">
  <img src="metadata/images/story.jpg" alt="RPClient story editor screen" width="45%" />
  <img src="metadata/images/settings.jpg" alt="RPClient settings screen" width="45%" />
</p>

## Features

### Conversations and images

- One-on-one character chats and multi-character group chats
- Streaming responses, stop generation, regenerate, and continue generation
- Impersonate generation from the user's perspective and create conversation branches from a selected message
- Markdown message rendering and collapsible reasoning content
- Image-only and text-plus-image messages, image viewer with zoom and swipe navigation, and save-image support
- Configure image processing (automatic, original, or custom size/dimension limits); model/provider image support varies
- Import/export chat archives, including supported embedded image attachments
- Automatic or manual long-term summary memory generation and per-chat lorebook entry selection
- Configurable prompt-history limit to reduce long-conversation processing; this does not delete stored chat history

### Characters and lorebooks

- Create, edit, search, and manage characters
- Import Character Card V1/V2 JSON and PNG character cards; export Character Card V2 JSON or PNG with embedded metadata
- Character avatars, multiple greetings, example dialogues, and extension fields
- Create, edit, import, and export SillyTavern-style lorebooks
- Keyword scanning, recursive activation, probability, priority, depth, token budgets, and related settings

### Models, prompts, and usage

- OpenAI-compatible API, Google Gemini API, and Anthropic Messages API
- Built-in configuration templates for ChatGPT, Gemini, Claude, DeepSeek, Grok, and OpenRouter
- Custom service URLs, models, request headers, and generation parameters; clone model configurations
- Prompt presets, macro expansion, and protocol-specific message post-processing
- Prompt Inspector for final messages, token budgets, lorebook matches, image handling, Regex processing, and omitted content
- Optional local debug logging of raw request and response JSON
- Token-usage dashboard with time filters, charts, model/host breakdowns, request details, and clear-history controls
- Prefer provider-reported usage when enabled and available; local tokenizer estimates may be used for missing counts

### Story writing

- Continuous manuscript editor with debounced local autosave and revision-conflict protection
- Chapter continuation with persistent per-chapter guidance and session undo/redo
- Story Memory, Author's Note, per-story character activation, lorebook matching, and Prompt Inspector
- Streaming generation with stop and recoverable partial output
- Markdown editor preview with navigation controls
- TXT/Markdown import and export, plus portable `.rpstory.json` archives

### Regex scripts

- Compatible with commonly used SillyTavern Regex script JSON
- Global and Character scopes; Source, Markdown, and Prompt execution modes
- Script ordering, duplication, testing, import, and export
- Scripts embedded in character cards are not automatically granted permission to run

### Portable full-data backup and restore

- Export a portable ZIP archive from **Settings → Data & System → Backup & Restore**
- Includes characters, stories, single/group chats, lorebooks, Regex scripts and authorizations, provider configurations/API keys, prompts/settings, token usage, request logs, attachment files and image references, database table data, preferences, and app-private files
- Archive contents are organized into readable JSON/JSONL documents and attachments; a manifest records file sizes and SHA-256 hashes
- Restore validates archive paths, hashes, SQLite integrity, and foreign-key consistency before replacing live data
- Supports the legacy version-1 JSONL snapshot format and the original SQLite snapshot format

> [!CAUTION]
> Restore is a **full replacement**, not a merge. It replaces existing app data, settings, attachments, and private files, then restarts RPClient. Export a backup of the current state before restoring if you may need it later.
>
> Backup ZIP files are **not encrypted**. They may contain API keys, custom headers, prompts, private conversations, raw request/response logs, and attachments. Store them securely and do not share them publicly.

### General

- Local-first data storage with Material 3 and dynamic colors
- System, light, and dark theme options
- Interface languages: Simplified Chinese, Traditional Chinese, English, Japanese, Korean, German, French, Russian, Portuguese (Brazil), Italian, Vietnamese, and Indonesian
- Android 8.0 (API 26) or later

## Quick Start

1. Install RPClient from [Google Play](https://play.google.com/store/apps/details?id=me.kafuuneko.rpclient), [F-Droid](https://f-droid.org/packages/me.kafuuneko.rpclient/), or [GitHub Releases](https://github.com/KafuuNeko/RPClient/releases).
2. Open **Settings → Model Configurations** and select a template or create a configuration.
3. Enter the API key, model name, and service URL. Test the connection and enable the configuration.
4. Create a character or import a JSON/PNG character card.
5. Import a lorebook and associate it with a character if needed.
6. Start a one-on-one chat or group chat, or open **Story Writing** to create a manuscript.
7. To protect your data, open **Settings → Data & System → Backup & Restore** and export a portable ZIP backup.

API keys are stored on the device but are sent to the configured model service with requests. Use only endpoints and proxy services you trust.

## Backup & Restore

For the archive layout, compatibility notes, and security details, see [Portable full-data backup and restore](doc/manual-backup-restore.md).

1. Open **Settings → Data & System → Backup & Restore**.
2. Choose **Backup** and select a destination for `RPClient-Portable-Data.zip`.
3. To restore, select a ZIP archive and carefully confirm the full-replacement warning.
4. RPClient validates the archive, restores the data, and restarts the app.

Keep the archive until you have confirmed that the restored data is correct. Do not edit or extract-and-repackage the ZIP unless you understand its manifest and data format.

## Build from source

### Requirements

- Git
- JDK 21 (the CI workflows use Eclipse Temurin 21)
- Android SDK and the SDK/build-tools required by the Gradle Android plugin
- Network access for Gradle dependency resolution

### Build a release APK

Clone the repository, then run:

```bash
git clone https://github.com/KafuuNeko/RPClient.git
cd RPClient
./gradlew --no-daemon assembleRelease
```

The APK is produced under `app/build/outputs/apk/release/`. Release builds enable code minification and resource shrinking. If release signing properties/environment variables are not supplied, Gradle may produce an unsigned or locally signed artifact depending on the build environment; configure signing before distribution.

For a debug build, run:

```bash
./gradlew --no-daemon assembleDebug
```

Run local unit tests with:

```bash
./gradlew --no-daemon test
```

Instrumented tests require a configured Android emulator or device:

```bash
./gradlew --no-daemon connectedAndroidTest
```

### GitHub Actions builds

The repository includes manually triggered workflows under `.github/workflows/`:

- **Build Signed Release APK** (`build-apk-debug.yml`): builds and uploads `RPClient-release-signed.apk`.
- **Build Signed RPClient Fork Release APK** (`build-apk-alt.yml`): builds with `-PrpclientFork=true`, uses the fork application ID/label, verifies the APK signature, and uploads `RPClient-Fork-release-signed.apk`.

Both workflows require signing credentials configured as GitHub Actions secrets. The standard workflow expects `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. The fork workflow expects the corresponding `FORK_RELEASE_*` secrets. The workflows use JDK 21 and retain uploaded artifacts for 14 days. Never commit keystores, passwords, or API keys to the repository.

## AI Coding Guide

AI-assisted changes should start with [doc/coding-guidelines.md](doc/coding-guidelines.md). It describes the project's Feature + MVI structure, Room data layer, Koin/Kotpref usage, and domain rules for character cards, lorebooks, prompts, Regex scripts, LLM requests, and request logs.

Read the relevant topic guides before editing code and run verification appropriate to the change.

## Contributing

Issues and pull requests are welcome. Review the [AI Coding Guide](doc/coding-guidelines.md) before AI-assisted changes and ensure the project builds before submitting.

When reporting compatibility issues, include sanitized character cards, lorebooks, or Regex JSON where possible, along with the API protocol, model name, and reproduction steps. Do not disclose API keys, private conversations, or character resources without authorization.

## Privacy and Disclaimer

- RPClient does not provide model services. Users are responsible for API usage fees and generated content.
- Request content is sent to the model service selected by the user. Review the provider's privacy policy before use.
- Token-usage statistics are stored locally as metadata and may be estimated; they are not billing statements and do not contain request or response bodies.
- Portable backup archives are not encrypted and can contain credentials and private data. Protect exported files accordingly.
- This project is not affiliated with or officially partnered with SillyTavern, OpenAI, Google, Anthropic, DeepSeek, xAI, or OpenRouter.
- Copyright and usage permissions for imported character cards, lorebooks, and other content are the responsibility of their providers and users.

## Contact

- GitHub: [KafuuNeko/RPClient](https://github.com/KafuuNeko/RPClient)
- Email: kafuuneko@gmail.com
