package cn.paperforge.app.data

import androidx.room.*
import org.json.JSONObject

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "pdf_search")
data class PdfLibraryText(
    val documentId: String,
    val page: Int,
    val text: String,
    val tokens: String,
)

@Entity(tableName = "pdf_index")
data class PdfIndexRow(
    @PrimaryKey val documentId: String,
    val fingerprint: String,
    val pageCount: Int,
    val textPages: Int,
    val annotations: Int,
    val updated: Long = System.currentTimeMillis(),
)

@Entity(tableName = "document_outputs", indices = [Index("sourceDocumentId")])
data class OutputRow(
    @PrimaryKey val path: String,
    val sourceDocumentId: String?,
    val sourceTitle: String,
    val format: String,
    val created: Long = System.currentTimeMillis(),
    val externalUri: String? = null,
    val outputDocumentId: String? = null,
) {
    fun encode() =
        JSONObject()
            .put("path", path)
            .put("sourceDocumentId", sourceDocumentId ?: JSONObject.NULL)
            .put("sourceTitle", sourceTitle)
            .put("format", format)
            .put("created", created)
            .put("externalUri", externalUri ?: JSONObject.NULL)
            .put("outputDocumentId", outputDocumentId ?: JSONObject.NULL)

    companion object {
        fun decode(j: JSONObject) =
            OutputRow(
                j.getString("path"),
                j.optString("sourceDocumentId").takeUnless { it.isBlank() || it == "null" },
                j.optString("sourceTitle"),
                j.optString("format"),
                j.optLong("created"),
                j.optString("externalUri").takeUnless { it.isBlank() || it == "null" },
                j.optString("outputDocumentId").takeUnless { it.isBlank() || it == "null" },
            )
    }
}

data class LibrarySearchHit(
    val documentId: String,
    val page: Int,
    val text: String,
    val pageId: String?,
)

data class LibraryFacts(
    val documentId: String,
    val pageCount: Int,
    val recognizedPages: Int,
    val hasExport: Boolean,
    val annotations: Int,
)
