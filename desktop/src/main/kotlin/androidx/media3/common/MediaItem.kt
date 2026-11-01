package androidx.media3.common

class MediaItem private constructor(val mediaId: String) {
    class Builder {
        private var id = ""
        fun setMediaId(id: String) = apply { this.id = id }
        fun build() = MediaItem(id)
    }
}
