package me.kafuuneko.rpclient.libs.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import java.io.File
import java.io.StringWriter
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.writeJsonLine
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFiles.writeText
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.TABLE_ROWS_KEY
import me.kafuuneko.rpclient.libs.backup.BackupArchiveFormat.VERSION
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.readRows
import me.kafuuneko.rpclient.libs.backup.BackupDatabaseCodec.tableRows
import me.kafuuneko.rpclient.libs.character.CharacterCardMapper
import me.kafuuneko.rpclient.libs.character.LorebookCodec
import me.kafuuneko.rpclient.libs.chat.ChatArchive
import me.kafuuneko.rpclient.libs.chat.ChatArchiveCodec
import me.kafuuneko.rpclient.libs.chat.ChatArchiveImage
import me.kafuuneko.rpclient.libs.chat.ChatArchiveMessage
import me.kafuuneko.rpclient.libs.chat.ChatArchiveMessageRole
import me.kafuuneko.rpclient.libs.chat.ChatArchiveSummary
import me.kafuuneko.rpclient.libs.room.entity.Character
import me.kafuuneko.rpclient.libs.room.entity.Lorebook
import me.kafuuneko.rpclient.libs.room.entity.LorebookEntry
import me.kafuuneko.rpclient.libs.regex.RegexScript
import me.kafuuneko.rpclient.libs.regex.RegexScriptCodec
import org.json.JSONArray
import org.json.JSONObject

/** 单次导出的功能文档写入器，只持有当前数据库快照和暂存目录。 */
internal class PortableBackupDocumentWriter(
    private val mDatabase: SupportSQLiteDatabase,
    private val mTables: List<String>,
    private val mRoot: File,
    private val mRepository: File
) {
    private val mOwnedTables = mutableSetOf<String>()
    private fun own(vararg names: String) {
        names.filter { it in mTables }.forEach { mOwnedTables.add(it) }
    }
    private fun rows(table: String): JSONArray = if (table in mTables) readRows(mDatabase, table) else JSONArray()
    private fun filtered(table: String, column: String, value: Any?): JSONArray =
        if (table in mTables) readRows(mDatabase, table, column, value) else JSONArray()

    /** 按功能输出完整数据行，并将剩余表写入兼容文档。 */
    fun writeAll() {
        writeCharacters()
        writeStories()
        writeChats()
        writeGroups()
        writeWorldbooks()
        writeProviders()
        writeRegex()
        writeUsageAndFiles()
        writeRemainingTables()
    }

    /** 保存可直接由角色卡导入器读取的 Character Card V2 JSON。 */
    private fun writeCharacters() {
        val gson = Gson()
        val mapper = CharacterCardMapper(gson)
        val characterRows = rows("character")
        val lorebooks = rows("lorebooks").objects()
        val lorebookEntries = rows("lorebook_entries").objects()
        own("character")
        characterRows.objects().forEach { row ->
            val character = gson.fromJson(row.toString(), Character::class.java)
            val boundBook = lorebooks.firstOrNull {
                it.optLong("id") == character.characterLorebookId
            }?.let { gson.fromJson(it.toString(), Lorebook::class.java) }
            val boundEntries = if (boundBook == null) emptyList() else lorebookEntries
                .filter { it.optLong("lorebookId") == boundBook.id }
                .map { gson.fromJson(it.toString(), LorebookEntry::class.java) }
            val card = JSONObject(mapper.toV2Json(character, boundBook, boundEntries))
                .put(TABLE_ROWS_KEY, tableRows("character" to JSONArray().put(row)))
            writeText(File(mRoot, "characters/character-" + character.id + ".json"), card.toString(2))
        }
    }

    /** 保存故事结构和无损关系扩展，维持 V2 故事归档格式。 */
    private fun writeStories() {
        // 章节按故事和分卷整理，关系行仍按原表保存以支持精确恢复。
        val storyRows = rows("stories")
        own("stories")
        val volumeRows = rows("story_volumes")
        val chapterRows = rows("story_chapters")
        val storyCharacterRows = rows("story_characters")
        val storyLorebookRows = rows("story_lorebook_entries")
        own("story_volumes", "story_chapters", "story_characters", "story_lorebook_entries")
        storyRows.objects().forEach { story ->
            val id = story.optLong("id")
            val volumes = volumeRows.objects().filter { it.optLong("storyId") == id }
                .sortedBy { it.optInt("sortOrder") }
            val chapters = chapterRows.objects().filter { it.optLong("storyId") == id }
                .sortedBy { it.optInt("sortOrder") }
            val characters = storyCharacterRows.objects().filter { it.optLong("storyId") == id }
                .sortedBy { it.optInt("sortOrder") }
            val loreStates = storyLorebookRows.objects().filter { it.optLong("storyId") == id }
            fun archivedChapter(chapter: JSONObject) = JSONObject()
                .put("title", chapter.optString("title"))
                .put("content", chapter.optString("content"))
                .put("continuationGuidance", chapter.optString("continuationGuidance"))
            val volumeDocs = JSONArray()
            volumes.forEach { volume ->
                val volumeId = volume.optLong("id")
                volumeDocs.put(JSONObject()
                    .put("title", volume.optString("title"))
                    .put("chapters", JSONArray().apply {
                        chapters.filter { !it.isNull("volumeId") && it.optLong("volumeId") == volumeId }
                            .forEach { put(archivedChapter(it)) }
                    }))
            }
            val ungrouped = JSONArray()
            chapters.filter { it.isNull("volumeId") }.forEach { ungrouped.put(archivedChapter(it)) }
            val archivedStory = JSONObject()
                .put("title", story.optString("title"))
                .put("memory", story.optString("memory"))
                .put("summary", story.optString("summary"))
                .put("authorNote", story.optString("authorNote"))
                .put("includeUserPersona", story.optBoolean("includeUserPersona"))
                .put("ungroupedChapters", ungrouped)
                .put("volumes", volumeDocs)
            val tableData = tableRows(
                "stories" to JSONArray().put(story),
                "story_volumes" to JSONArray().apply { volumes.forEach { put(it) } },
                "story_chapters" to JSONArray().apply { chapters.forEach { put(it) } },
                "story_characters" to JSONArray().apply { characters.forEach { put(it) } },
                "story_lorebook_entries" to JSONArray().apply { loreStates.forEach { put(it) } }
            )
            writeText(File(mRoot, "stories/story-" + id + ".rpstory.json"),
                JSONObject()
                    .put("format", "rpclient_story")
                    .put("version", 2)
                    .put("story", archivedStory)
                    .put("characterHints", JSONArray())
                    .put("lorebookHints", JSONArray())
                    .put("characterRelations", JSONArray().apply { characters.forEach { put(it) } })
                    .put("worldBookEntryStates", JSONArray().apply { loreStates.forEach { put(it) } })
                    .put(TABLE_ROWS_KEY, tableData)
                    .toString(2))
        }
    }

    /** Write standard SillyTavern-compatible JSONL with lossless rows in ignorable extensions. */
    private fun writeChats() {
        val codec = ChatArchiveCodec(Gson())
        val chatSessions = rows("chat_sessions")
        val allMessages = rows("chat_messages").objects()
        val imageRows = rows("message_images").objects()
        val fileRows = rows("files").objects().associateBy { it.optString("uuid") }
        val characters = rows("character").objects().associateBy { it.optLong("id") }
        own("chat_sessions", "chat_messages")
        chatSessions.objects().forEach { session ->
            val sessionId = session.optLong("id")
            val sessionMessages = allMessages.filter { it.optLong("sessionId") == sessionId }
            val summaryRow = sessionMessages.firstOrNull {
                it.optString("source").equals("Summary", ignoreCase = true)
            }
            val messages = sessionMessages.filterNot {
                it.optString("source").equals("Summary", ignoreCase = true)
            }.sortedWith(compareBy<JSONObject> { it.optLong("createTime") }.thenBy { it.optLong("id") })
            val summary = summaryRow?.let { row ->
                val coveredId = if (row.isNull("coveredMessageId")) 0L else row.optLong("coveredMessageId")
                val coveredIndex = if (coveredId == 0L) -1 else
                    messages.indexOfFirst { it.optLong("id") == coveredId }
                ChatArchiveSummary(
                    content = row.optString("content"),
                    createTime = row.optLong("createTime"),
                    coveredMessageIndex = coveredIndex
                )
            }
            val character = characters[session.optLong("characterId")]
            val archive = ChatArchive(
                title = session.optString("title"),
                createTime = session.optLong("createTime"),
                latestTime = session.optLong("latestTime"),
                userName = session.optString("userName"),
                userDescription = session.optString("userDescription"),
                userNote = session.optString("userNote"),
                creatorNotes = if (session.isNull("creatorNotes")) null else session.optString("creatorNotes"),
                lorebookEntrySet = session.optString("lorebookEntrySet", "[]"),
                worldInfoStateJson = session.optString("worldInfoStateJson", "{}"),
                autoSummaryPaused = session.optBoolean("autoSummaryPaused"),
                characterNameHint = character?.optString("name").orEmpty(),
                characterFingerprint = null,
                messages = emptyList(),
                summary = summary
            )
            val destination = File(mRoot, "chats/conversation-" + sessionId + ".jsonl")
            destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                val headerWriter = StringWriter()
                codec.encodeHeader(archive, headerWriter)
                val header = JSONObject(headerWriter.toString().trim())
                val headerRows = mutableListOf("chat_sessions" to JSONArray().put(session))
                if (summaryRow != null) {
                    headerRows += "chat_messages" to JSONArray().put(summaryRow)
                }
                header.put(TABLE_ROWS_KEY, tableRows(*headerRows.toTypedArray()))
                writeJsonLine(header, writer)

                val writtenHashes = mutableSetOf<String>()
                messages.forEach { row ->
                    val role = when (row.optString("source")) {
                        "User" -> ChatArchiveMessageRole.User
                        "System" -> ChatArchiveMessageRole.Narrator
                        else -> ChatArchiveMessageRole.Character
                    }
                    val attachments = imageRows.filter {
                        it.optLong("messageId") == row.optLong("id") &&
                            it.optString("messageType").equals("single", ignoreCase = true)
                    }.sortedBy { it.optInt("position") }
                    val images = attachments.mapNotNull { image ->
                        val file = fileByUuid[image.optString("imageUuid")] ?: return@mapNotNull null
                        val asset = File(mRepository, file.optString("hash"))
                        if (!asset.isFile) return@mapNotNull null
                        ChatArchiveImage(
                            mimeType = file.optString("mimeType").takeIf { it.isNotBlank() } ?: "image/png",
                            data = java.util.Base64.getEncoder().encodeToString(asset.readBytes())
                        )
                    }
                    val message = ChatArchiveMessage(
                        createTime = row.optLong("createTime"),
                        role = role,
                        content = row.optString("content"),
                        images = images
                    )
                    val lineWriter = StringWriter()
                    codec.encodeMessage(archive, message, lineWriter, writtenHashes)
                    val json = JSONObject(lineWriter.toString().trim())
                    json.put(TABLE_ROWS_KEY, tableRows("chat_messages" to JSONArray().put(row)))
                    writeJsonLine(json, writer)
                }
            }
        }
    }

    /** 保存群聊成员、消息与摘要覆盖关系。 */
    private fun writeGroups() {
        // 群成员与消息快照保持独立原始行，避免展示结构丢失关联信息。
        val groupSessions = rows("group_chat_sessions")
        own("group_chat_sessions")
        groupSessions.objects().forEach { session ->
            val id = session.optLong("id")
            val members = filtered("group_chat_members", "sessionId", id).objects()
                .sortedBy { it.optInt("sortOrder") }.toJsonArray()
            val messages = filtered("group_chat_messages", "sessionId", id).objects()
                .sortedWith(compareBy<JSONObject> { it.optLong("createTime") }.thenBy { it.optLong("id") })
            val summaries = filtered("group_chat_summaries", "sessionId", id).objects()
                .sortedWith(compareBy<JSONObject> { it.optLong("createTime") }.thenBy { it.optLong("id") })
                .toJsonArray()
            own("group_chat_members", "group_chat_messages", "group_chat_summaries")
            val headerRows = tableRows(
                "group_chat_sessions" to JSONArray().put(session),
                "group_chat_members" to members,
                "group_chat_summaries" to summaries
            )
            File(mRoot, "group-chats/group-" + id + ".jsonl").bufferedWriter(Charsets.UTF_8).use { writer ->
                writeJsonLine(JSONObject()
                    .put("type", "header")
                    .put("format", "rpclient-group-chat")
                    .put("session", session)
                    .put("members", members)
                    .put("summaries", summaries)
                    .put(TABLE_ROWS_KEY, headerRows), writer)
                messages.forEach { message ->
                    writeJsonLine(JSONObject()
                        .put("type", "message")
                        .put("message", message)
                        .put(TABLE_ROWS_KEY, tableRows("group_chat_messages" to JSONArray().put(message))), writer)
                }
            }
        }
    }

    /** 保存与独立世界书导入器兼容的 SillyTavern JSON。 */
    private fun writeWorldbooks() {
        val gson = Gson()
        val codec = LorebookCodec(gson)
        val worldbooks = rows("lorebooks")
        own("lorebooks")
        val worldbookEntries = rows("lorebook_entries")
        own("lorebook_entries")
        worldbooks.objects().forEach { row ->
            val book = gson.fromJson(row.toString(), Lorebook::class.java)
            val entries = worldbookEntries.objects()
                .filter { it.optLong("lorebookId") == book.id }
                .sortedWith(compareBy<JSONObject> { it.optInt("order") }.thenBy { it.optLong("id") })
                .map { gson.fromJson(it.toString(), LorebookEntry::class.java) }
            val entryRows = JSONArray().apply {
                worldbookEntries.objects().filter { it.optLong("lorebookId") == book.id }
                    .sortedWith(compareBy<JSONObject> { it.optInt("order") }.thenBy { it.optLong("id") })
                    .forEach { put(it) }
            }
            val document = JSONObject(codec.toLorebookJson(book, entries))
                .put(TABLE_ROWS_KEY, tableRows(
                    "lorebooks" to JSONArray().put(row),
                    "lorebook_entries" to entryRows
                ))
            writeText(File(mRoot, "worldbooks/worldbook-" + book.id + ".json"), document.toString(2))
        }
    }

    /** 保存模型配置和角色关联，归档明确包含凭据。 */
    private fun writeProviders() {
        // 供应商凭据属于明文归档内容，入口和文档已提示此边界。
        val associations = rows("character_llm_provider_associations")
        own("character_llm_provider_associations")
        val providerRows = rows("llm_providers")
        own("llm_providers")
        writeText(File(mRoot, "providers/provider-configurations.json"),
            JSONObject()
                .put("format", "rpclient-provider-configurations")
                .put("version", VERSION)
                .put("providers", providerRows)
                .put("characterAssociations", associations)
                .put(TABLE_ROWS_KEY, tableRows(
                    "llm_providers" to providerRows,
                    "character_llm_provider_associations" to associations
                )).toString(2))
    }

    /** Export regex scripts in SillyTavern's standard RegexScriptData array format. */
    private fun writeRegex() {
        val gson = Gson()
        val codec = RegexScriptCodec(gson)
        val scriptRows = rows("regex_scripts")
        own("regex_scripts")
        fun toPortableScript(row: JSONObject): RegexScript = RegexScript(
            id = row.optString("scriptId").ifBlank { row.optLong("rowId").toString() },
            scriptName = row.optString("scriptName"),
            findRegex = row.optString("findRegex"),
            replaceString = row.optString("replaceString"),
            trimStrings = JSONArray(row.optString("trimStringsJson", "[]")).let { array ->
                (0 until array.length()).map { array.optString(it) }
            },
            placement = JSONArray(row.optString("placementJson", "[]")).let { array ->
                (0 until array.length()).map { array.optInt(it) }
            },
            disabled = row.optBoolean("disabled"),
            markdownOnly = row.optBoolean("markdownOnly"),
            promptOnly = row.optBoolean("promptOnly"),
            runOnEdit = row.optBoolean("runOnEdit"),
            substituteRegex = row.optInt("substituteRegex"),
            minDepth = if (row.isNull("minDepth")) null else row.optInt("minDepth"),
            maxDepth = if (row.isNull("maxDepth")) null else row.optInt("maxDepth"),
            rawJson = row.optString("rawJson", "{}")
        )
        val globalRows = scriptRows.objects().filter { it.isNull("characterId") }
            .sortedBy { it.optInt("sortOrder") }
        writeText(File(mRoot, "regex/global-scripts.json"),
            JSONObject()
                .put("format", "rpclient-regex-scripts")
                .put("version", VERSION)
                .put("scripts", JSONArray(codec.toJson(globalRows.map(::toPortableScript), pretty = true)))
                .put(TABLE_ROWS_KEY, tableRows("regex_scripts" to JSONArray().apply {
                    globalRows.forEach { put(it) }
                }))
                .toString(2))

        rows("character").objects().map { it.optLong("id") }.sorted().forEach { characterId ->
            val characterScripts = scriptRows.objects().filter {
                !it.isNull("characterId") && it.optLong("characterId") == characterId
            }.sortedBy { it.optInt("sortOrder") }
            writeText(File(mRoot, "regex/character-scripts/character-" + characterId + ".json"),
                JSONObject()
                    .put("format", "rpclient-regex-scripts")
                    .put("version", VERSION)
                    .put("characterId", characterId)
                    .put("scripts", JSONArray(codec.toJson(characterScripts.map(::toPortableScript), pretty = true)))
                    .put(TABLE_ROWS_KEY, tableRows("regex_scripts" to JSONArray().apply {
                        characterScripts.forEach { put(it) }
                    }))
                    .toString(2))
        }
        val authorizations = rows("regex_character_authorizations")
        own("regex_character_authorizations")
        writeText(File(mRoot, "regex/character-authorizations.json"),
            JSONObject()
                .put("format", "rpclient-regex-character-authorizations")
                .put("version", VERSION)
                .put("characterIds", JSONArray().apply {
                    authorizations.objects().forEach { put(it.optLong("characterId")) }
                })
                .put(TABLE_ROWS_KEY, tableRows("regex_character_authorizations" to authorizations))
                .toString(2))
    }

    /** 保存用量、图片引用和文件索引。 */
    private fun writeUsageAndFiles() {
        // 附件内容另行复制，此处保存引用关系和用量记录。
        val fileRows = rows("files")
        own("files")
        val tokenRows = rows("llm_token_usage_records")
        own("llm_token_usage_records")
        writeText(File(mRoot, "app-data/token-usage.json"),
            JSONObject()
                .put("format", "rpclient-token-usage")
                .put("version", VERSION)
                .put("records", tokenRows)
                .put(TABLE_ROWS_KEY, tableRows("llm_token_usage_records" to tokenRows))
                .toString(2))

        val images = rows("message_images")
        own("message_images")
        writeText(File(mRoot, "app-data/message-images.json"),
            JSONObject()
                .put("format", "rpclient-message-images")
                .put("version", VERSION)
                .put("images", images)
                .put(TABLE_ROWS_KEY, tableRows("message_images" to images))
                .toString(2))

        writeText(File(mRoot, "app-data/files-index.json"),
            JSONObject()
                .put("format", "rpclient-files-index")
                .put("version", VERSION)
                .put("files", fileRows)
                .put(TABLE_ROWS_KEY, tableRows("files" to fileRows))
                .toString(2))
    }

    /** 保留未单独映射的表，兼容目标新增的背景与搜索数据。 */
    private fun writeRemainingTables() {
        // 显式归属之外的表也必须导出，兼容新增搜索和页面背景数据。
        File(mRoot, "app-data/other-tables.jsonl").bufferedWriter(Charsets.UTF_8).use { writer ->
            (mTables - mOwnedTables).sorted().forEach { table ->
                readRows(mDatabase, table).objects().forEach { row ->
                    writeJsonLine(JSONObject()
                        .put("table", table)
                        .put("row", row)
                        .put(TABLE_ROWS_KEY, tableRows(table to JSONArray().put(row))), writer)
                }
            }
        }
    }
}
