package com.example.snorelyzer.data.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

internal class DocumentAudioExportDestination(
    private val resolver: ContentResolver,
    private val uri: Uri
) : AudioExportDestination {
    override fun open(): OutputStream = resolver.openOutputStream(uri)
        ?: throw IOException("Could not open export destination")

    override fun discard() {
        if (!DocumentsContract.deleteDocument(resolver, uri)) {
            throw IOException("Could not remove incomplete export")
        }
    }
}
