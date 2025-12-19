package com.facelapse.app.ui.project

import android.net.Uri
import java.io.File

data class ExportResult(
    val file: File,
    val uri: Uri,
    val mimeType: String
)
