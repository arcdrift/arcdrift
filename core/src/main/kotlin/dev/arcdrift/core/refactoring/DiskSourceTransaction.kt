package dev.arcdrift.core.refactoring

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lightweight transactional file-write helper for multi-file disk refactorings.
 * Snapshots original bytes (or non-existence) and newly created ancestor directories before mutating
 * each file, supports atomic case-only renames on case-insensitive filesystems (macOS APFS / Windows NTFS),
 * and rolls back all changes if an exception occurs before [commit].
 */
internal class DiskSourceTransaction {

    private val snapshots = linkedMapOf<File, ByteArray?>()
    private val caseRenamePairs = mutableListOf<Pair<File, File>>() // (originalCaseFile, newCaseFile)
    private val createdDirs = linkedSetOf<File>()
    private var committed = false

    fun writeText(file: File, content: String) {
        snapshotIfNeeded(file)
        ensureParentDirsTracked(file.parentFile)
        file.writeText(content, Charsets.UTF_8)
    }

    /**
     * Safely renames [srcFile] to [destFile] with updated [content], including case-only renames
     * (`OrderDTO.java` -> `OrderDto.java`) on case-insensitive filesystems where `Files.isSameFile` is true.
     */
    fun moveOrRenameWithContent(srcFile: File, destFile: File, content: String) {
        val isSameParentCaseRename = srcFile.exists() &&
            srcFile.parentFile?.canonicalPath == destFile.parentFile?.canonicalPath &&
            srcFile.name.equals(destFile.name, ignoreCase = true) &&
            srcFile.name != destFile.name

        if (isSameParentCaseRename) {
            val destIsDistinctExisting = destFile.exists() && !runCatching {
                Files.isSameFile(srcFile.toPath(), destFile.toPath())
            }.getOrDefault(false)
            if (destIsDistinctExisting) {
                error("Target file '${destFile.name}' already exists.")
            }
            snapshotIfNeeded(srcFile)
            val tempSibling = File(srcFile.parentFile, ".${srcFile.name}.${System.nanoTime()}.arcdrift-tmp")
            try {
                srcFile.writeText(content, Charsets.UTF_8)
                Files.move(srcFile.toPath(), tempSibling.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(tempSibling.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                caseRenamePairs.add(srcFile to destFile)
            } finally {
                if (tempSibling.exists()) {
                    tempSibling.delete()
                }
            }
            return
        }

        writeText(destFile, content)
        val sameInode = runCatching {
            srcFile.exists() && destFile.exists() && Files.isSameFile(srcFile.toPath(), destFile.toPath())
        }.getOrDefault(destFile.canonicalPath == srcFile.canonicalPath)
        if (!sameInode && destFile.canonicalPath != srcFile.canonicalPath) {
            deleteFile(srcFile)
        }
    }

    fun deleteFile(file: File) {
        snapshotIfNeeded(file)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun ensureParentDirsTracked(dir: File?) {
        if (dir == null || dir.exists()) return
        val missingChain = mutableListOf<File>()
        var curr: File? = dir.absoluteFile
        while (curr != null && !curr.exists()) {
            missingChain.add(curr)
            curr = curr.parentFile
        }
        for (missing in missingChain.asReversed()) {
            if (missing.mkdir()) {
                createdDirs.add(missing)
            }
        }
    }

    private fun snapshotIfNeeded(file: File) {
        val key = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
        if (key !in snapshots) {
            snapshots[key] = if (key.exists() && key.isFile) key.readBytes() else null
        }
    }

    fun commit() {
        committed = true
        snapshots.clear()
        caseRenamePairs.clear()
        createdDirs.clear()
    }

    fun rollback(primaryError: Throwable? = null) {
        if (committed) return
        for ((origFile, newCaseFile) in caseRenamePairs.asReversed()) {
            try {
                if (newCaseFile.exists()) {
                    val tempSibling = File(newCaseFile.parentFile, ".${origFile.name}.${System.nanoTime()}.arcdrift-rb")
                    Files.move(newCaseFile.toPath(), tempSibling.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(tempSibling.toPath(), origFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (rbErr: Throwable) {
                primaryError?.addSuppressed(rbErr)
            }
        }
        for ((file, originalBytes) in snapshots.entries.reversed()) {
            try {
                if (originalBytes == null) {
                    if (file.exists()) file.delete()
                } else {
                    file.parentFile?.mkdirs()
                    file.writeBytes(originalBytes)
                }
            } catch (rbErr: Throwable) {
                primaryError?.addSuppressed(rbErr)
            }
        }
        for (dir in createdDirs.toList().asReversed()) {
            try {
                if (dir.exists() && dir.isDirectory && dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
            } catch (rbErr: Throwable) {
                primaryError?.addSuppressed(rbErr)
            }
        }
        snapshots.clear()
        caseRenamePairs.clear()
        createdDirs.clear()
    }

    companion object {
        inline fun <T> execute(block: (DiskSourceTransaction) -> T): T {
            val tx = DiskSourceTransaction()
            return try {
                val result = block(tx)
                tx.commit()
                result
            } catch (t: Throwable) {
                tx.rollback(t)
                throw t
            }
        }
    }
}
