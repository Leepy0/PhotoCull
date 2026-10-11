package com.leepy0.photocull.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import java.io.IOException
import java.io.InputStream

/**
 * 폴더 승인(SAF) 트리로 카드에 접근. 파일 경로로 읽을 수 없는 기기용 대체 경로.
 * 폴더마다 자식 문서를 한 번의 query(필요한 열만)로 읽는다. DocumentFile의 항목별 속성 호출은 IPC가 많아 쓰지 않는다.
 * 트리는 볼륨 루트 또는 DCIM 폴더여야 한다([isUsableTree]).
 */
class SafCardSource(
    context: Context,
    private val treeUri: Uri,
    private val presence: () -> Boolean,
) : CardSource {

    private val resolver: ContentResolver = context.contentResolver
    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private data class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val lastModified: Long)

    /** 한 폴더의 자식 목록. 읽을 수 없으면 null */
    private fun children(parentDocId: String): List<Entry>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = try {
            resolver.query(uri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // 볼륨이 빠진 경우 provider가 IllegalArgument/IllegalState를 던진다
            null
        } ?: return null
        return cursor.use { c ->
            val out = ArrayList<Entry>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                out += Entry(docId, name, mime == Document.MIME_TYPE_DIR, size, modified)
            }
            out
        }
    }

    /** DCIM 폴더의 문서 id와 표시 이름 */
    private fun dcim(): Pair<String, String>? {
        val treePath = treeDocId.substringAfter(':', "").trim('/')
        if (treePath.equals(CardFileTypes.DCIM, ignoreCase = true)) return treeDocId to treePath
        val rootChildren = children(treeDocId) ?: return null
        val dir = rootChildren.firstOrNull { it.isDir && it.name.equals(CardFileTypes.DCIM, ignoreCase = true) } ?: return null
        return dir.docId to dir.name
    }

    override fun listTargetFiles(): List<CardFile> {
        val (dcimId, dcimName) = dcim() ?: run {
            if (children(treeDocId) == null) throw IOException("card tree not readable")
            return emptyList()
        }
        val out = ArrayList<CardFile>()
        walk(dcimId, dcimName, 0, out)
        out.sortBy { it.relPath }
        return out
    }

    private fun walk(docId: String, rel: String, depth: Int, out: MutableList<CardFile>) {
        val kids = children(docId) ?: if (depth == 0) throw IOException("DCIM not readable") else return
        for (e in kids) {
            if (CardFileTypes.isHidden(e.name)) continue
            if (e.isDir) {
                if (depth < CardSource.MAX_DEPTH) walk(e.docId, "$rel/${e.name}", depth + 1, out)
            } else if (CardFileTypes.isTarget(e.name)) {
                val location = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.docId).toString()
                out += CardFile("$rel/${e.name}", e.size, e.lastModified, location)
            }
        }
    }

    override fun hasTargetFile(maxEntries: Int): Boolean {
        val (dcimId, _) = dcim() ?: return false
        var budget = maxEntries
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(dcimId to 0)
        while (queue.isNotEmpty() && budget > 0) {
            val (id, depth) = queue.removeFirst()
            val kids = children(id) ?: continue
            for (e in kids) {
                if (--budget <= 0) return false
                if (CardFileTypes.isHidden(e.name)) continue
                if (e.isDir) {
                    if (depth < CardSource.MAX_DEPTH) queue.add(e.docId to depth + 1)
                } else if (CardFileTypes.isTarget(e.name)) {
                    return true
                }
            }
        }
        return false
    }

    override fun openInput(file: CardFile): InputStream =
        try {
            resolver.openInputStream(file.location.toUri()) ?: throw IOException("cannot open")
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("cannot open", e)
        }

    override fun delete(file: CardFile): Boolean =
        try {
            DocumentsContract.deleteDocument(resolver, file.location.toUri())
        } catch (e: Exception) {
            false
        }

    override fun exists(file: CardFile): Boolean =
        try {
            resolver.query(file.location.toUri(), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }

    override fun isPresent(): Boolean = runCatching(presence).getOrDefault(false)

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /** 외부 저장소 provider 문서 id "<볼륨 UUID>:<경로>"에서 볼륨 UUID */
        fun volumeIdOf(treeUri: Uri): String? =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?.substringBefore(':', "")?.takeIf { it.isNotBlank() }

        /** 볼륨 루트나 DCIM 폴더를 고른 트리만 쓸 수 있다(상대 경로가 "DCIM/..."로 맞아야 기록 대조가 된다). */
        fun isUsableTree(treeUri: Uri): Boolean {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return false
            val path = docId.substringAfter(':', "").trim('/')
            return path.isEmpty() || path.equals(CardFileTypes.DCIM, ignoreCase = true)
        }
    }
}
