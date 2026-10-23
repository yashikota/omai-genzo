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
    val rawUriString: String? = null,
    val jpgUriString: String? = null,
    val rawExtension: String = "",
    val jpgExtension: String = "",
    val fileSize: Long = 0L,
    val modifiedAt: Long = 0L,
    val fileType: PhotoType = PhotoType.STANDARD,
    var selectionState: SelectionState = SelectionState.PENDING,
    /** 0 = unrated, 1..[MAX_RATING] stars. Same scale as Lightroom's xmp:Rating. */
    val rating: Int = 0,
    var exifInfo: ExifInfo = ExifInfo(),
) {
    val displayFileName: String
        get() = if (rawExtension.isNotEmpty()) "$baseName.${rawExtension.lowercase()}" else "$baseName.${jpgExtension.lowercase()}"

    val displayBadge: String
        get() = if (rawExtension.isNotEmpty()) rawExtension.uppercase() else jpgExtension.uppercase()

    val primaryPath: String
        get() = rawPath ?: jpgPath ?: rawUriString ?: jpgUriString ?: ""

    val fastDisplayPath: String
        get() = jpgPath ?: rawPath ?: jpgUriString ?: rawUriString ?: ""

    fun isRawFile(): Boolean = rawPath != null || rawUriString != null

    fun hasJpegPreview(): Boolean = jpgPath != null || jpgUriString != null

    fun shouldUseRawRenderer(): Boolean = isRawFile() && !hasJpegPreview()

    companion object {
        const val MAX_RATING = 5
    }
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

    companion object {
        /** One pass over the session, instead of one `count {}` per state on every recomposition. */
        fun of(photos: List<PhotoItem>): SessionSummary {
            var accepted = 0
            var rejected = 0
            for (photo in photos) {
                when (photo.selectionState) {
                    SelectionState.ACCEPT -> accepted++
                    SelectionState.REJECT -> rejected++
                    SelectionState.PENDING -> Unit
                }
            }
            return SessionSummary(photos.size, accepted, rejected, photos.size - accepted - rejected)
        }
    }
}
