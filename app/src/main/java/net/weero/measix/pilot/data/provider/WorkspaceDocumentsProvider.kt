package net.weero.measix.pilot.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceDocumentUiModel
import java.io.FileNotFoundException

interface WorkspaceDocumentsDependencies {
    val workspaceCommands: WorkspaceApplicationService
    val workspaceQueries: WorkspaceQueryService
}

/** Presents registered shared Workspaces through their existing command and query owners. */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private val dependencies get() = requireNotNull(context?.applicationContext as? WorkspaceDocumentsDependencies)
    private val commands get() = dependencies.workspaceCommands
    private val queries get() = dependencies.workspaceQueries

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
        val ctx = requireNotNull(context)
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            val target = parseDocId(documentId)
            if (target.isRoot) newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            } else runBlocking { queries.document(target.root, target.path) }?.let { addFileRow(this, it) }
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            val parent = parseDocId(parentDocumentId)
            val documents = runBlocking {
                if (parent.isRoot) queries.documentRoots() else queries.documentChildren(parent.root, parent.path)
            }
            documents.forEach { addFileRow(this, it) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.path.isNotEmpty()) { "Cannot open a Workspace root" }
        val job = Job()
        var opened: ParcelFileDescriptor? = null
        var failure: Throwable? = null
        signal?.setOnCancelListener { job.cancel() }
        try {
            runBlocking(job) { opened = commands.openDocument(target.root, target.path, ParcelFileDescriptor.parseMode(mode)) }
            signal?.throwIfCanceled()
            return requireNotNull(opened).also { opened = null }
        } catch (cancelled: CancellationException) {
            throw OperationCanceledException().also { it.initCause(cancelled); failure = it }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            signal?.setOnCancelListener(null)
            job.cancel()
            try { opened?.close() } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot)
        val path = runBlocking { commands.createDocument(parent.root, parent.path, displayName, mimeType == Document.MIME_TYPE_DIR) }
        notifyChange(parentDocumentId)
        return buildDocId(parent.root, path)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.path.isNotEmpty())
        try { runBlocking { commands.deleteDocument(target.root, target.path) } }
        finally { notifyChange(target.parentId) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.path.isNotEmpty())
        val path = try { runBlocking { commands.renameDocument(target.root, target.path, displayName) } }
        finally { notifyChange(target.parentId) }
        return buildDocId(target.root, path)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String =
        transfer(sourceDocumentId, targetParentDocumentId, move = false)

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String?, targetParentDocumentId: String): String {
        val source = parseDocId(sourceDocumentId)
        require(sourceParentDocumentId == source.parentId) { "Source parent does not match document" }
        return transfer(sourceDocumentId, targetParentDocumentId, move = true)
    }

    private fun transfer(sourceDocumentId: String, targetParentDocumentId: String, move: Boolean): String {
        val source = parseDocId(sourceDocumentId)
        val target = parseDocId(targetParentDocumentId)
        require(!source.isRoot && source.path.isNotEmpty() && !target.isRoot)
        val path = try {
            runBlocking { commands.transferDocument(source.root, source.path, target.root, target.path, move) }
        } finally {
            notifyChange(targetParentDocumentId)
            if (move) notifyChange(source.parentId)
        }
        return buildDocId(target.root, path)
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot) return Document.MIME_TYPE_DIR
        return mimeOf(runBlocking { queries.document(target.root, target.path) } ?: throw FileNotFoundException(documentId))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (child.isRoot || parent == child) return false
        return runBlocking {
            if (queries.document(child.root, child.path) == null) false
            else if (parent.isRoot) true
            else if (parent.root != child.root || queries.document(parent.root, parent.path)?.entry?.isDirectory != true) false
            else parent.path.isEmpty() || child.path.startsWith(parent.path + "/")
        }
    }

    private fun addFileRow(cursor: MatrixCursor, model: WorkspaceDocumentUiModel) {
        val file = model.entry
        val flags = if (file.path.isEmpty()) Document.FLAG_DIR_SUPPORTS_CREATE else
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE or
                if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(model.root, file.path))
            add(Document.COLUMN_DISPLAY_NAME, if (file.path.isEmpty()) model.workspaceName else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(model))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.sizeBytes)
            add(Document.COLUMN_LAST_MODIFIED, file.updatedAt)
        }
    }

    private fun mimeOf(model: WorkspaceDocumentUiModel): String = if (model.entry.isDirectory) Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(model.entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(true, "", "")
        require(documentId.startsWith(DOC_PREFIX)) { "Invalid document ID" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val root = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        require(root.matches(Regex("[A-Za-z0-9._-]+")) && root != "." && root != "..") { "Invalid Workspace root" }
        require('/' !in rest || path.isNotEmpty()) { "Invalid document path" }
        require(path.isEmpty() || path.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.none { it == '\u0000' }
        }) { "Invalid document path" }
        return DocId(false, root, path)
    }

    private fun buildDocId(root: String, path: String) = if (path.isEmpty()) "$DOC_PREFIX$root" else "$DOC_PREFIX$root/$path"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = requireNotNull(context)
        ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(ctx.packageName + ".documents", parentDocumentId), null)
    }

    private data class DocId(val isRoot: Boolean, val root: String, val path: String) {
        val parentId get() = "ws/$root" + path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" }
    }

    companion object {
        private const val ROOT_ID = "rikkahub_workspaces"
        private const val ROOT_DOC_ID = "root"
        private const val DOC_PREFIX = "ws/"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
