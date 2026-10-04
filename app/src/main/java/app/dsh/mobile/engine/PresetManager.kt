package app.dsh.mobile.engine

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PresetManager {

    private const val PRESET_ROOT = ".agent-presets"
    private const val PROFILE_PATCH = "profiles/web/cordis.patch.yml"
    private const val START_MARKER = "# dsh-mobile legacy presets begin"
    private const val END_MARKER = "# dsh-mobile legacy presets end"
    private const val MAX_FILE_BYTES = 2L shl 20
    private const val MAX_PACKAGE_BYTES = 5L shl 20
    private const val MAX_ENTRIES = 16
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val reservedIds = setOf("standard", "ptc", "minimal", "cordis")
    private val allowedEntries = setOf("manifest.json", "preset/agent.cordis.yml", "preset/preset.yml")

    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val sourceDshVersion: String,
        val composition: String,
        val metadata: String,
    )

    fun list(context: Context): List<Preset> {
        val root = presetRoot(context)
        return root.listFiles()
            ?.filter { it.isDirectory && idPattern.matches(it.name) && !Files.isSymbolicLink(it.toPath()) }
            ?.map(::readInstalled)
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun preview(packageFile: File): Preset {
        if (!packageFile.isFile || packageFile.length() > MAX_PACKAGE_BYTES) {
            throw IOException("Preset package is missing or exceeds the 5 MB size limit.")
        }
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > MAX_ENTRIES) throw IOException("Preset package contains too many entries.")
                val path = validateArchivePath(entry.name)
                if (entry.isDirectory) {
                    if (path != "preset") throw IOException("Preset package contains an unsupported directory.")
                    zip.closeEntry()
                    continue
                }
                if (path !in allowedEntries || path in entries) {
                    throw IOException("Preset package contains an unsupported or duplicate entry: $path")
                }
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > MAX_PACKAGE_BYTES || data.size().toLong() + read > MAX_FILE_BYTES) {
                        throw IOException("Preset package expands beyond the size limit.")
                    }
                    data.write(buffer, 0, read)
                }
                entries[path] = data.toByteArray()
                zip.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: throw IOException("Preset manifest is missing.")
        val compositionBytes = entries["preset/agent.cordis.yml"]
            ?: throw IOException("Preset Agent composition is missing.")
        val metadataBytes = entries["preset/preset.yml"] ?: throw IOException("Preset metadata is missing.")
        if (entries.keys != allowedEntries) throw IOException("Preset package must contain exactly the documented files.")
        val manifest = JSONObject(decodeUtf8(manifestBytes))
        if (manifest.optString("format") != "dsh-preset" || manifest.optInt("version", -1) != 1) {
            throw IOException("Unsupported DSH preset package format.")
        }
        val id = manifest.optString("id", "")
        if (!idPattern.matches(id) || id in reservedIds) throw IOException("Preset identifier is invalid or reserved.")
        val name = manifest.optString("name", "").trim()
        if (name.isBlank() || name.length > 128) throw IOException("Preset display name is invalid.")
        val description = manifest.optString("description", "").take(1024)
        val composition = decodeUtf8(compositionBytes)
        validateComposition(composition)
        val metadata = decodeUtf8(metadataBytes)
        if (metadata.isBlank() || metadata.length > MAX_FILE_BYTES) throw IOException("Preset metadata is invalid.")
        return Preset(
            id = id,
            name = name,
            description = description,
            sourceDshVersion = manifest.optString("sourceDshVersion", "unknown").take(64),
            composition = composition,
            metadata = metadata,
        )
    }

    fun install(context: Context, preset: Preset) {
        validatePreset(preset)
        val dshHome = EngineConfig.dshHome(context)
        val root = File(dshHome, PRESET_ROOT)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create Agent preset directory.")
        val destination = File(root, preset.id)
        if (destination.exists()) throw IOException("Preset '${preset.id}' is already installed.")
        val profileFile = File(dshHome, PROFILE_PATCH)
        val previous = if (profileFile.isFile) profileFile.readText() else ""
        val next = updateManagedSection(previous, preset.id, profileBlock(preset), remove = false)
        val stage = File(root, ".${preset.id}.${System.nanoTime()}.tmp")
        val patchStage = File(profileFile.parentFile!!.apply { if (!isDirectory && !mkdirs()) throw IOException("Cannot create profile directory.") },
            ".cordis.patch.${System.nanoTime()}.tmp")
        try {
            if (!stage.mkdir()) throw IOException("Cannot stage preset installation.")
            File(stage, "manifest.json").writeText(presetManifest(preset).toString(2))
            File(stage, "agent.cordis.yml").writeText(preset.composition)
            File(stage, "preset.yml").writeText(preset.metadata)
            patchStage.writeText(next)
            move(stage, destination)
            try {
                move(patchStage, profileFile, replaceExisting = true)
            } catch (e: Exception) {
                destination.deleteRecursively()
                throw e
            }
        } finally {
            stage.deleteRecursively()
            patchStage.delete()
        }
    }

    fun export(context: Context, id: String, destination: File) {
        if (!idPattern.matches(id) || id in reservedIds) throw IOException("Preset identifier is invalid.")
        val preset = readInstalled(File(presetRoot(context), id))
        val manifest = JSONObject()
            .put("format", "dsh-preset")
            .put("version", 1)
            .put("id", preset.id)
            .put("name", preset.name)
            .put("description", preset.description)
            .put("sourceDshVersion", preset.sourceDshVersion)
            .put("exportedAt", java.time.Instant.now().toString())
        val temporary = File(destination.parentFile ?: throw IOException("Missing preset destination directory"),
            ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                addEntry(zip, "manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
                addEntry(zip, "preset/agent.cordis.yml", preset.composition.toByteArray(Charsets.UTF_8))
                addEntry(zip, "preset/preset.yml", preset.metadata.toByteArray(Charsets.UTF_8))
            }
            move(temporary, destination, replaceExisting = true)
        } finally {
            temporary.delete()
        }
    }

    fun remove(context: Context, id: String) {
        if (!idPattern.matches(id) || id in reservedIds) throw IOException("Preset identifier is invalid.")
        val root = presetRoot(context)
        val directory = File(root, id)
        if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != root.canonicalFile) {
            throw IOException("Preset directory is invalid.")
        }
        if (!directory.isDirectory) throw IOException("Preset is not installed.")
        val preset = readInstalled(directory)
        val dshHome = EngineConfig.dshHome(context)
        val profileFile = File(dshHome, PROFILE_PATCH)
        val previous = if (profileFile.isFile) profileFile.readText() else ""
        val next = updateManagedSection(previous, id, profileBlock(preset), remove = true)
        val stagedPatch = File(profileFile.parentFile, ".cordis.patch.${System.nanoTime()}.tmp")
        val quarantine = File(root, ".${id}.${System.nanoTime()}.remove")
        try {
            stagedPatch.writeText(next)
            move(directory, quarantine)
            try {
                move(stagedPatch, profileFile, replaceExisting = true)
            } catch (e: Exception) {
                move(quarantine, directory)
                throw e
            }
            if (!quarantine.deleteRecursively()) throw IOException("Preset configuration was removed, but its source files remain in ${quarantine.name}.")
        } finally {
            stagedPatch.delete()
        }
    }

    private fun readInstalled(directory: File): Preset {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw IOException("Preset is not installed.")
        val composition = File(directory, "agent.cordis.yml")
        val metadata = File(directory, "preset.yml")
        if (!composition.isFile || !metadata.isFile) throw IOException("Installed preset is incomplete.")
        val id = directory.name
        if (!idPattern.matches(id) || id in reservedIds) throw IOException("Installed preset identifier is invalid.")
        val manifestFile = File(directory, "manifest.json")
        val manifest = if (manifestFile.isFile) JSONObject(manifestFile.readText()) else JSONObject()
        return Preset(
            id = id,
            name = manifest.optString("name").ifBlank { readMetadataName(metadata.readText()).ifBlank { id } },
            description = manifest.optString("description", ""),
            sourceDshVersion = manifest.optString("sourceDshVersion", "unknown"),
            composition = composition.readText(),
            metadata = metadata.readText(),
        )
    }

    private fun validatePreset(preset: Preset) {
        if (!idPattern.matches(preset.id) || preset.id in reservedIds) throw IOException("Preset identifier is invalid.")
        if (preset.name.isBlank() || preset.name.length > 128) throw IOException("Preset display name is invalid.")
        validateComposition(preset.composition)
        if (preset.metadata.isBlank() || preset.metadata.length > MAX_FILE_BYTES) throw IOException("Preset metadata is invalid.")
    }

    private fun validateComposition(composition: String) {
        if (composition.isBlank() || composition.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES ||
            composition.contains('\u0000')
        ) throw IOException("Preset composition is empty or too large.")
        if (composition.lineSequence().any { it.startsWith('\t') }) throw IOException("YAML indentation cannot use tabs.")
        val firstContent = composition.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') && it != "---" }
        if (firstContent == null || !firstContent.matches(Regex("-(?:\\s.*)?"))) {
            throw IOException("Agent composition must be a YAML plugin list.")
        }
    }

    private fun readMetadataName(metadata: String): String {
        val value = Regex("(?m)^\\s*name:\\s*(.+?)\\s*(?:#.*)?$")
            .find(metadata)?.groupValues?.get(1)?.trim()
            ?: return ""
        return value.trim('"', '\'').take(128)
    }

    internal fun profileBlock(preset: Preset): String =
        listOf(
            "- insert:",
            "    - id: preset-${preset.id}",
            "      name: '@deepseek-ai/dsh-agent-preset'",
            "      config:",
            "        id: ${JSONObject.quote(preset.id)}",
            "        name: ${JSONObject.quote(preset.name)}",
            "        description: ${JSONObject.quote(preset.description)}",
            "        plugins:",
        ).joinToString("\n") + "\n" +
            preset.composition.trimEnd().lineSequence().joinToString("\n") { line ->
                if (line.isEmpty()) "" else "          $line"
            }

    internal fun updateManagedSection(previous: String, id: String, block: String, remove: Boolean): String {
        val start = previous.indexOf(START_MARKER)
        val end = previous.indexOf(END_MARKER)
        if ((start == -1) != (end == -1) || (start != -1 && end < start)) {
            throw IOException("Managed preset section is incomplete.")
        }
        val before: String
        val after: String
        val existing: String
        if (start == -1) {
            before = previous
            after = ""
            existing = ""
        } else {
            before = previous.substring(0, start)
            existing = previous.substring(start + START_MARKER.length, end).trim()
            after = previous.substring(end + END_MARKER.length)
        }
        val entryPattern = Regex("(?ms)^# dsh-mobile preset ([a-z0-9-]+) begin\\n.*?^# dsh-mobile preset \\1 end$")
        val entryMatches = entryPattern.findAll(existing).toList()
        val residue = entryPattern.replace(existing, "").trim()
        if (residue.isNotEmpty()) throw IOException("Managed preset section contains unrecognized content.")
        val entries = entryMatches.map { it.groupValues[1] to it.value }
        if (remove && entries.none { it.first == id }) {
            throw IOException("Preset is not registered in the active profile.")
        }
        if (!remove && entries.any { it.first == id }) {
            throw IOException("Preset identifier '$id' already exists in the active profile.")
        }
        val updated = entries.filterNot { it.first == id }.toMutableList()
        if (!remove) updated += id to "# dsh-mobile preset $id begin\n$block\n# dsh-mobile preset $id end"
        val base = before + after
        val withoutEmptyFlow = if (base.trim() == "[]") {
            base.replace(Regex("(?m)^\\s*\\[\\]\\s*$"), "")
        } else {
            base
        }
        if (updated.isEmpty()) return withoutEmptyFlow.trimEnd().let { if (it.isEmpty()) "" else "$it\n" }
        val prefix = withoutEmptyFlow.trimEnd()
        return (if (prefix.isEmpty()) "" else "$prefix\n\n") + "$START_MARKER\n" +
            updated.joinToString("\n\n") { it.second } + "\n$END_MARKER\n"
    }

    private fun validateArchivePath(path: String): String {
        if (path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) {
            throw IOException("Preset package contains an unsafe path.")
        }
        val normalized = path.trimEnd('/')
        if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            if (path.endsWith('/')) return normalized
            throw IOException("Preset package contains an unsafe path.")
        }
        return normalized
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            throw IOException("Preset package contains invalid UTF-8.", e)
        }

    private fun addEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun presetRoot(context: Context): File = File(EngineConfig.dshHome(context), PRESET_ROOT)

    private fun presetManifest(preset: Preset): JSONObject =
        JSONObject()
            .put("format", "dsh-preset")
            .put("version", 1)
            .put("id", preset.id)
            .put("name", preset.name)
            .put("description", preset.description)
            .put("sourceDshVersion", preset.sourceDshVersion)

    private fun move(source: File, destination: File, replaceExisting: Boolean = false) {
        try {
            if (replaceExisting) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (replaceExisting) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source.toPath(), destination.toPath())
            }
        }
    }

}
