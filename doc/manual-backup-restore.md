# Manual ZIP backup and restore

RPClient's **Settings → Data & System → Backup & Restore** screen creates and restores a portable ZIP snapshot.

## Archive layout

```text
RPClient-Backup.zip
├── manifest.json
├── database/
│   └── primary.sqlite
├── settings/
│   └── shared_prefs/
│       └── *.xml
└── files/
    └── repository/
        └── <app-private files>
```

- `manifest.json` identifies the archive format and version and contains a SHA-256 checksum for `database/primary.sqlite`.
- `primary.sqlite` contains relational data such as characters, chats, stories, world books, provider settings, and their relationships.
- `settings/shared_prefs/` contains app preferences.
- `files/repository/` contains app-managed file assets. Temporary staging files are omitted.
- `request_logs.sqlite` is intentionally excluded.

## Editing and restoring

The ZIP is intentionally unencrypted so it can be inspected with ordinary ZIP and SQLite tools. You can edit the database with a SQLite editor and repack the archive, but keep the archive layout and update `manifest.json`'s `databaseSha256` if you change the database. Restore rejects unknown archive formats, unsupported versions, path traversal, oversized archives, a non-SQLite database, and a database checksum mismatch before replacing live data.

This first version restores a full snapshot; it does **not** convert arbitrary character/story/lorebook JSON files added to new folders into database records. Use RPClient's existing per-feature import flows for those portable JSON formats. Do not add unknown files expecting them to be imported automatically.

## Sensitive data warning

The archive may contain API keys, custom request headers, provider endpoints, conversations, and personal settings. Store it privately and do not share it. The ZIP is not encrypted. Restore replaces the current database, preferences, and app-managed repository files. The current files are kept in a temporary rollback snapshot while replacement is performed; the app restarts after a successful restore so Room and preferences are reopened from disk.
