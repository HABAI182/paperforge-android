package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.data.ReceiptLedger
import cn.paperforge.app.model.ReceiptFields
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #26: device flow for the receipt MVP, on `emulator-5554`.
 *
 * Synthetic OCR text is put on a real scan document's page row (the same place the offline engine
 * caches it), then: extract -> edit -> export CSV -> export again -> rebuild XLSX -> read the XLSX
 * back as a zip + XML. The owner's real receipts were not provided, so nothing here claims real
 * photo accuracy; the sample is constructed inside the test.
 *
 * Existing ledger rows on the device are backed up and restored, so the row counts are exact.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptExportFlowTest {
    private val syntheticText =
        """
        星河超市
        发票
        2026-09-29
        苹果 10.00
        合计 ￥128.00
        发票号码：0123456789
        税额 ￥12.80
        价税合计 ￥140.80
        """
            .trimIndent()

    private fun image(file: File) {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun extractEditExportCsvAppendAndReadableXlsx() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
            val repo = app.repository
            val csv = ReceiptLedger.csvFile(repo)
            val xlsx = ReceiptLedger.xlsxFile(repo)
            val csvBackup = if (csv.isFile) csv.readBytes() else null
            val xlsxBackup = if (xlsx.isFile) xlsx.readBytes() else null
            val doc = repo.newScan()
            val proof = File(app.filesDir, "receipts").apply { mkdirs() }
            try {
                csv.delete()
                xlsx.delete()

                // A real document row with the OCR text the engine would have cached.
                val file = repo.captureFile(doc.id)
                image(file)
                val page =
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        0,
                        file.path,
                        filter = "ORIGINAL",
                    )
                repo.dao.putPage(page)
                val stored = repo.dao.pages(doc.id).first()
                repo.dao.putPage(stored.copy(ocrText = syntheticText))

                val text = ReceiptLedger.sourceText(repo, doc)
                assertEquals("页面缓存的 OCR 文本没有读到", syntheticText, text)

                val extracted = ReceiptFields.parse(text)
                assertEquals("2026-09-29", extracted.date)
                assertEquals("星河超市", extracted.merchant)
                assertEquals("140.80", extracted.amount)
                assertEquals("12.80", extracted.tax)
                assertEquals("0123456789", extracted.invoiceNo)
                assertEquals(ReceiptFields.Category.SHOPPING, extracted.category)
                assertTrue(extracted.confidence > .8f)
                assertTrue(extracted.evidence.isNotEmpty())

                // The user fixes two fields in the editable form before exporting.
                val edited =
                    extracted.copy(
                        merchant = "星河超市（已改）",
                        amount = "256.00",
                        category = ReceiptFields.Category.FOOD,
                    )

                val first = ReceiptLedger.append(repo, edited, doc.title)
                assertEquals("CSV 未写到既有导出目录", csv.path, first.path)
                assertTrue("CSV 文件不存在", first.isFile)
                val afterFirst = ReceiptLedger.readcsv(first)
                assertEquals("表头 + 1 行", 2, afterFirst.size)
                assertEquals(ReceiptLedger.HEADERS, afterFirst[0])
                val row = afterFirst[1]
                assertEquals(8, row.size)
                assertEquals("2026-09-29", row[0])
                assertEquals("星河超市（已改）", row[1])
                assertEquals("256.00", row[2])
                assertEquals("12.80", row[3])
                assertEquals("0123456789", row[4])
                assertEquals("餐饮", row[5])
                assertEquals(edited.confidence, row[6].toFloat(), .005f)

                // Exporting the same record again appends instead of overwriting.
                ReceiptLedger.append(repo, edited, doc.title)
                val afterSecond = ReceiptLedger.readcsv(first)
                assertEquals("第二次导出应追加为 2 行数据", 3, afterSecond.size)
                assertEquals(afterSecond[1], afterSecond[2])

                // XLSX is rebuilt from the CSV ledger and must be a readable workbook.
                val workbook = ReceiptLedger.exportXlsx(repo)
                assertTrue("XLSX 文件不存在", workbook.isFile)
                assertTrue("XLSX 文件为空", workbook.length() > 0L)
                val zip = ZipFile(workbook)
                try {
                    val names = zip.entries().asSequence().map { it.name }.toSet()
                    assertTrue(names.contains("[Content_Types].xml"))
                    assertTrue(names.contains("xl/workbook.xml"))
                    assertTrue(names.contains("xl/worksheets/sheet1.xml"))
                    val sheetXml =
                        zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                    val document =
                        DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                            .parse(ByteArrayInputStream(sheetXml.toByteArray(Charsets.UTF_8)))
                    val rows = document.getElementsByTagName("row")
                    assertEquals("表头 + 2 行数据", 3, rows.length)
                    val cells = document.getElementsByTagName("c")
                    val headerCells =
                        (0 until cells.length)
                            .map { cells.item(it) }
                            .filter { it.attributes?.getNamedItem("r")?.nodeValue?.endsWith("1") == true }
                    assertEquals(ReceiptLedger.HEADERS.size, headerCells.size)
                    assertTrue(
                        "工作表里找不到改后的商家",
                        sheetXml.contains("星河超市（已改）")
                    )
                    assertTrue("金额没有写成数值单元格", sheetXml.contains("<v>256.00</v>"))
                    val workbookXml =
                        zip.getInputStream(zip.getEntry("xl/workbook.xml"))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                    assertTrue(workbookXml.contains(ReceiptLedger.SHEET_NAME))
                } finally {
                    zip.close()
                }

                first.copyTo(File(proof, "票据记录.csv"), overwrite = true)
                workbook.copyTo(File(proof, "票据记录.xlsx"), overwrite = true)
                val result =
                    JSONObject()
                        .put("issue", 26)
                        .put("synthetic", true)
                        .put("source", "测试内构造的合成票据文本，不是真实照片")
                        .put("csvPath", first.path)
                        .put("xlsxPath", workbook.path)
                        .put("csvSize", first.length())
                        .put("xlsxSize", workbook.length())
                        .put("extracted", json(extracted))
                        .put("edited", json(edited))
                        .put(
                            "csvRows",
                            JSONArray().apply { afterSecond.forEach { put(JSONArray(it)) } },
                        )
                        .put("csvRowsAfterFirstExport", afterFirst.size)
                        .put("csvRowsAfterSecondExport", afterSecond.size)
                        .put("xlsxRows", 3)
                        .put("xlsxReadable", true)
                        .put("sheetName", ReceiptLedger.SHEET_NAME)
                File(proof, "device-receipt.json").writeText(result.toString(2))
            } finally {
                csv.delete()
                xlsx.delete()
                csvBackup?.let { csv.parentFile?.mkdirs(); csv.writeBytes(it) }
                xlsxBackup?.let { xlsx.parentFile?.mkdirs(); xlsx.writeBytes(it) }
                repo.dao.document(doc.id)?.let { repo.deleteDocument(it) }
            }
        }

    private fun json(record: ReceiptFields.Record) =
        JSONObject()
            .put("date", record.date)
            .put("merchant", record.merchant)
            .put("amount", record.amount)
            .put("tax", record.tax)
            .put("invoiceNo", record.invoiceNo)
            .put("category", record.category.name)
            .put("confidence", record.confidence.toDouble())
            .put("evidence", JSONArray(record.evidence))
}
