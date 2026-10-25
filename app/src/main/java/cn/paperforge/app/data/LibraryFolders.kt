package cn.paperforge.app.data

/** Virtual library paths, independent of Android file locations. */
fun libraryFolder(value: String) =
    value
        .replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")

fun libraryTags(value: String) =
    value
        .split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .joinToString(", ")
