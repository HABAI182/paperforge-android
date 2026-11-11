package cn.paperforge.app.imaging

/** Photo-pixel units of a retained tone input, independent of preview/export resolution. */
internal object ToneSourceScale {
    /** New explicit metadata takes precedence over the existing structure diagnostic. */
    fun recorded(explicit: Double? = null, diagnostic: Double? = null): Float? {
        fun valid(value: Double?) = value?.toFloat()?.takeIf { it.isFinite() && it > 0f }
        return valid(explicit) ?: valid(diagnostic)
    }

    /** The geometry grid has the dimensions at which the first tone pass ran. */
    fun atSize(
        recorded: Float?,
        gridWidth: Int,
        gridHeight: Int,
        renderedWidth: Int,
        renderedHeight: Int,
    ): Float? {
        if (recorded == null || !recorded.isFinite() || recorded <= 0f) return null
        val originalEdge = maxOf(gridWidth, gridHeight)
        val renderedEdge = maxOf(renderedWidth, renderedHeight)
        if (originalEdge <= 0 || renderedEdge <= 0) return null
        return recorded * renderedEdge.toFloat() / originalEdge
    }
}
