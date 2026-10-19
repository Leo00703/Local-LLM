package com.druk.lmplayground.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.druk.lmplayground.models.ModelInfoProvider
import java.net.URLDecoder

class StorageRepository(
    private val context: Context,
    private val prefs: StoragePreferences
) {

    companion object {
        private const val TAG = "StorageRepository"
    }

    /**
     * Check if storage folder is configured.
     * User MUST select a folder before using the app.
     */
    fun isStorageConfigured(): Boolean {
        return prefs.modelStorageUri != null
    }

    /**
     * Get the configured storage URI.
     */
    fun getStorageUri(): Uri? = prefs.modelStorageUri

    /**
     * Set the storage folder.
     */
    fun setStorageFolder(uri: Uri) {
        prefs.modelStorageUri = uri
    }

    /** One child of the model folder as the provider reports it. */
    private class Child(val docId: String, val name: String, val sizeBytes: Long)

    /**
     * The folder's children with name and size in ONE provider query. DocumentFile asks the
     * provider again for every property of every file (a query for each name, another for
     * each length), which for a folder of a few dozen files is a hundred round trips, and
     * this is read every time the model picker opens. Null when the query cannot be made, so
     * callers can fall back to DocumentFile.
     */
    private fun queryChildren(treeUri: Uri): List<Child>? {
        return try {
            val parent = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val out = ArrayList<Child>()
            context.contentResolver.query(
                parent,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out.add(Child(id, name, if (c.isNull(2)) 0L else c.getLong(2)))
                }
            } ?: return null
            out
        } catch (e: Exception) {
            Log.w(TAG, "queryChildren failed, falling back to DocumentFile: ${e.message}")
            null
        }
    }

    /**
     * Get list of model files from SAF storage.
     */
    fun getModelFiles(): List<ModelFile> {
        val treeUri = prefs.modelStorageUri ?: return emptyList()

        queryChildren(treeUri)?.let { children ->
            return children
                .filter { it.name.endsWith(".gguf") }
                .map { child ->
                    ModelFile(
                        name = child.name,
                        displayName = ModelInfoProvider.getDisplayName(child.name),
                        sizeBytes = child.sizeBytes,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                    )
                }
        }

        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        if (documentFile == null) {
            Log.d(TAG, "getModelFiles() - DocumentFile.fromTreeUri returned null")
            return emptyList()
        }
        
        Log.d(TAG, "getModelFiles() - DocumentFile exists: ${documentFile.exists()}, canRead: ${documentFile.canRead()}")
        
        val files = documentFile.listFiles()
        Log.d(TAG, "getModelFiles() - found ${files.size} files total")
        
        return files
            .filter { it.name?.endsWith(".gguf") == true }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                Log.d(TAG, "getModelFiles() - found model: $name")
                ModelFile(
                    name = name,
                    displayName = ModelInfoProvider.getDisplayName(name),
                    sizeBytes = file.length(),
                    uri = file.uri
                )
            }
    }

    fun getStorageInfo(): StorageInfo {
        val treeUri = prefs.modelStorageUri
        if (treeUri == null) {
            return StorageInfo(
                path = "Not configured",
                usedBytes = 0,
                totalBytes = 0,
                availableBytes = 0,
                isCustomFolder = true
            )
        }
        
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        
        // Parse display path from URI
        val decodedPath = try {
            URLDecoder.decode(treeUri.path ?: "", "UTF-8")
        } catch (e: Exception) {
            treeUri.path ?: ""
        }
        val displayPath = decodedPath
            .removePrefix("/tree/")
            .replace(":", "/")
            .replace("primary", "Internal Storage")
        
        if (documentFile == null) {
            return StorageInfo(
                path = displayPath,
                usedBytes = 0,
                totalBytes = 0,
                availableBytes = 0,
                isCustomFolder = true
            )
        }
        
        val usedBytes = getModelFiles().sumOf { it.sizeBytes }

        // Get storage stats from external storage as approximation
        val externalDir = context.getExternalFilesDir(null)
        val statFs = if (externalDir != null && externalDir.exists()) {
            try {
                StatFs(externalDir.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return StorageInfo(
            path = displayPath,
            usedBytes = usedBytes,
            totalBytes = statFs?.totalBytes ?: 0,
            availableBytes = statFs?.availableBytes ?: 0,
            isCustomFolder = true
        )
    }

    fun deleteModel(fileName: String): Boolean {
        val treeUri = prefs.modelStorageUri ?: return false
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        val file = documentFile?.findFile(fileName)
        val deleted = file?.delete() == true
        Log.d(TAG, "deleteModel() - fileName: $fileName, deleted: $deleted")
        return deleted
    }

    /**
     * Result of opening a model file.
     * Contains the "fd:N" path for native code and keeps the ParcelFileDescriptor alive.
     * 
     * IMPORTANT: The ParcelFileDescriptor MUST be kept alive while the model is loaded!
     * Native code uses dup() to create copies of the fd, but the original must stay open.
     * Call close() when done with the model.
     */
    class ModelFileHandle(
        /**
         * Legacy `fd:N` path string referring to [pfd] in the *app* process.
         * Still used by code paths that resolve the file in-process. Once the
         * service moves to `:llama` (step 6) this string is no longer valid
         * cross-process — pass [pfd] directly to AIDL instead.
         */
        val path: String,
        /**
         * The open file descriptor for the model. Pass this across the
         * binder when calling the inference service so the service can
         * dup the FD into its own process and build its own `fd:N` string.
         */
        val pfd: ParcelFileDescriptor,
    ) {
        fun close() {
            try {
                pfd.close()
                Log.d(TAG, "ModelFileHandle closed")
            } catch (e: Exception) {
                Log.e(TAG, "ModelFileHandle.close() failed: ${e.message}")
            }
        }
    }
    
    /**
     * Open a model file and return a handle for native code.
     * 
     * Returns "fd:N" path format which our native ggml_fopen/llama_open overrides
     * will handle using dup() + fdopen()/open().
     * 
     * IMPORTANT: Keep the returned handle alive until the model is unloaded!
     */
    fun openModelFile(fileName: String): ModelFileHandle? {
        val treeUri = prefs.modelStorageUri
        if (treeUri == null) {
            Log.e(TAG, "openModelFile() - storage not configured")
            return null
        }
        
        // One query for the lookup; DocumentFile.findFile reads every child's name.
        val fileUri: Uri? = queryChildren(treeUri)
            ?.firstOrNull { it.name == fileName }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId) }
            ?: DocumentFile.fromTreeUri(context, treeUri)?.findFile(fileName)?.uri

        if (fileUri == null) {
            Log.e(TAG, "openModelFile() - file not found: $fileName")
            return null
        }

        return try {
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
            if (pfd != null) {
                val fd = pfd.fd
                val fdPath = "fd:$fd"
                
                Log.d(TAG, "openModelFile() - fileName: $fileName, fd: $fd, path: $fdPath")
                ModelFileHandle(fdPath, pfd)
            } else {
                Log.e(TAG, "openModelFile() - openFileDescriptor returned null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "openModelFile() - failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve a paired mmproj (a SAF sibling of a model) to an app-private real
     * filesystem PATH, copying it out of SAF on first use and caching the copy.
     * Returns the absolute path, or null on failure.
     *
     * A real path is required because the CLIP projector loader reads its tensor
     * weights with a raw `std::ifstream(fname)` (only the GGUF header goes through
     * the fd-aware ggml_fopen), so an "fd:N" sentinel or content:// URI won't
     * work. The copy is cached (reused when name + size match) and only the
     * active projector is kept to bound disk use.
     */
    fun resolveMmprojToPath(mmprojFilename: String): String? =
        resolveSiblingToPath(mmprojFilename, "mmproj")

    /**
     * Same for the MTP drafter of a Gemma 4 model: the native loader reads it by plain path,
     * so it is copied out of SAF once into its own folder and only the active drafter is
     * kept there (a different model's drafter replaces it).
     */
    fun resolveDrafterToPath(drafterFilename: String): String? =
        resolveSiblingToPath(drafterFilename, "drafter")

    private fun resolveSiblingToPath(mmprojFilename: String, subdir: String): String? {
        val treeUri = prefs.modelStorageUri ?: return null
        val documentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val src = documentFile.findFile(mmprojFilename)
        if (src == null) {
            Log.e(TAG, "resolveSiblingToPath - $subdir file not found: $mmprojFilename")
            return null
        }
        val srcLen = src.length()
        val dir = java.io.File(context.filesDir, subdir)
        val dest = java.io.File(dir, mmprojFilename)
        if (dest.exists() && srcLen > 0 && dest.length() == srcLen) {
            return dest.absolutePath // cached copy is current
        }
        if (!dir.exists()) dir.mkdirs()
        // Keep only the active projector.
        dir.listFiles()?.forEach { if (it.name != mmprojFilename) it.delete() }
        return try {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            } ?: run {
                Log.e(TAG, "resolveMmprojToPath - openInputStream returned null")
                return null
            }
            if (srcLen <= 0 || dest.length() == srcLen) {
                dest.absolutePath
            } else {
                Log.e(TAG, "resolveMmprojToPath - size mismatch after copy")
                dest.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveMmprojToPath - copy failed: ${e.message}")
            dest.delete()
            null
        }
    }

    fun hasValidPermission(): Boolean {
        val uri = prefs.modelStorageUri ?: return false
        return try {
            val persistedUris = context.contentResolver.persistedUriPermissions
            persistedUris.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        } catch (e: Exception) {
            false
        }
    }
}
