# Portable full-data backup and restore

RPClient's **Settings → Data & System → Backup & Restore** screen creates `RPClient-Portable-Data.zip`.

## Archive layout

```text
RPClient-Portable-Data.zip
├── manifest.json
├── settings.json
├── settings/shared_prefs/*.xml
├── database/
│   ├── primary/schema.json
│   ├── primary/tables/*.jsonl
│   ├── request-logs/schema.json
│   └── request-logs/tables/*.jsonl
├── attachments/repository/
└── app-files/
```

Every table from the main Room database is exported to JSONL, including characters, provider configurations and API keys, provider associations, single chats and messages, Group Chat sessions/members/messages/summaries, stories/volumes/chapters/relations, World Books and entries, regex scripts and authorizations, file references, message-image references, and token usage. The separate request-log database is also included. Binary repository assets and app files are copied, and original preference XML is retained alongside a readable `settings.json` index/content representation.

`manifest.json` records format/version, included components, file sizes, and SHA-256 checksums. The restore flow validates archive paths, checksums, size and SQLite integrity before replacing current data. Existing legacy `rpclient-backup` ZIP snapshots remain supported for restore.

## Full restore behavior

Restore is **full replacement**, not merge. It replaces the primary database, request-log database, app preferences, repository assets, and app files, then restarts RPClient. If file replacement fails, the app attempts to restore a rollback snapshot. Keep a separate backup before restoring if the current installation contains data you may want later.

## Security

The ZIP is not encrypted. It may contain API keys, custom headers, provider endpoints, raw request/response logs, personal prompts, conversations, and image/character assets. Anyone who obtains the ZIP can inspect it. Store it securely and do not share it publicly.
