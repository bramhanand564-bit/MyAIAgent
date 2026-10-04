package com.myaiagent.folder

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.myaiagent.model.UploadItem
import java.util.UUID

object FolderVideoImporter {
    private val videoExtensions = setOf("mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi")

    fun importVideos(context: Context, treeUri: android.net.Uri): List<UploadItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<UploadItem>()
        scan(root, result)
        return result
    }

    private fun scan(file: DocumentFile, result: MutableList<UploadItem>) {
        if (file.isFile) {
            val name = file.name ?: return
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension in videoExtensions) {
                result += UploadItem(
                    id = UUID.randomUUID().toString(),
                    uri = file.uri.toString(),
                    fileName = name
                )
            }
            return
        }

        file.listFiles().forEach { child -> scan(child, result) }
    }
}
