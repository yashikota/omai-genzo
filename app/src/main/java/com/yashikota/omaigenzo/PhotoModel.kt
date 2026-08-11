package com.yashikota.omaigenzo

enum class SelectionState {
    PENDING,
    ACCEPT,
    REJECT,
}

enum class PhotoType {
    RAW,
    STANDARD,
    RAW_AND_JPEG,
}

enum class FilterCategory {
    ALL,
    ACCEPT,
    REJECT,
    PENDING,
}

data class PhotoItem(
    val id: String,
    val baseName: String,
    val rawPath: String? = null,
    val jpgPath: String? = null,
    val rawExtension: String = "",
    val jpgExtension: String = "",
    val fileSize: Long = 0L,
    val fileType: PhotoType = PhotoType.STANDARD,
    var selectionState: SelectionState = SelectionState.PENDING,
    var exifInfo: ExifInfo = ExifInfo(),
) {
    val displayFileName: String
        get() = if (rawExtension.isNotEmpty()) "$baseName.${rawExtension.lowercase()}" else "$baseName.${jpgExtension.lowercase()}"

    val displayBadge: String
        get() = if (rawExtension.isNotEmpty()) rawExtension.uppercase() else jpgExtension.uppercase()

    val primaryPath: String
        get() = rawPath ?: jpgPath ?: ""

    val fastDisplayPath: String
        get() = jpgPath ?: rawPath ?: ""

    fun isRawFile(): Boolean = rawPath != null
}

data class SessionSummary(
    val totalCount: Int,
    val acceptCount: Int,
    val rejectCount: Int,
    val pendingCount: Int,
) {
    val acceptPercentage: Int
        get() = if (totalCount > 0) (acceptCount * 100) / totalCount else 0

    val rejectPercentage: Int
        get() = if (totalCount > 0) (rejectCount * 100) / totalCount else 0
}
