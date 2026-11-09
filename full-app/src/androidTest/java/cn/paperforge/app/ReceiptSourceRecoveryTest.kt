package cn.paperforge.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.model.*
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic cached OCR checks plumbing and source frames; it does not claim real receipt accuracy.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptSourceRecoveryTest {
    @Test
    fun cachedFieldsKeepPageGeometryAndLegacyLedgerUpgradesWithItemSheet() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()
        val repo = app.repository
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        fun main(block: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        main {
            vm =
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[
                    MainViewModel::class.java]
        }
        val doc = DocumentRow(UUID.randomUUID().toString(), "Synthetic receipt source")
        val csv = ReceiptLedger.csvFile(repo)
        val xlsx = ReceiptLedger.xlsxFile(repo)
        val oldCsv = csv.takeIf { it.exists() }?.readBytes()
        val oldXlsx = xlsx.takeIf { it.exists() }?.readBytes()
        try {
            repo.dao.putDocument(doc)
            val texts =
                listOf(
                    listOf("星河超市", "2026-11-01", "苹果 2 5.00 10.00"),
                    listOf("香蕉 1 20.00 20.00", "合计 ￥30.00", "税额 ￥1.00", "发票号码：0123456789"),
                )
            texts.forEachIndexed { index, lines ->
                val file = repo.captureFile(doc.id)
                val bitmap =
                    Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                    }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val page =
                    PageRow(
                        UUID.randomUUID().toString(),
                        doc.id,
                        index,
                        file.path,
                        filter = "ORIGINAL",
                    )
                val layout =
                    OcrLayout(
                        600,
                        800,
                        lines.mapIndexed { n, text ->
                            val y = .1f + .1f * n
                            OcrLine(
                                text,
                                listOf(
                                    PointN(.1f, y),
                                    PointN(.8f, y),
                                    PointN(.8f, y + .05f),
                                    PointN(.1f, y + .05f),
                                ),
                                confidence = .92f,
                            )
                        },
                        engineId = "synthetic-cached",
                        sourceRevision = 0,
                        frame = OcrFrame.bind(page),
                    )
                repo.dao.putPage(page.copy(ocrText = layout.text, ocrLayout = layout.encode()))
            }
            val record = vm.receiptDraft(doc)!!
            assertEquals("30.00", record.amount)
            assertEquals("CNY", record.currency)
            assertEquals(2, record.items.size)
            assertEquals("2", record.items.first().quantity)
            assertEquals("5.00", record.items.first().unitPrice)
            assertTrue(ReceiptFields.consistency(record)!!.contains("一致"))
            assertTrue(
                ReceiptFields.consistency(record.copy(amount = "31.00"))!!.contains("差额 1.00")
            )
            val source = record.sources.getValue("amount")
            assertEquals(1, source.page)
            assertEquals("合计 ￥30.00", source.text)
            assertEquals("synthetic-cached", source.engine)
            assertEquals(.92f, source.confidence!!, .001f)
            assertEquals(OcrFrame.signature(repo.dao.pages(doc.id)[1]), source.geometrySignature)
            csv.writeText(
                "\uFEFF日期,商家,金额,税额,发票号,分类,置信度,来源\r\n2026-10-01,旧商家,12.00,,00001234,购物,0.50,旧票据\r\n",
                Charsets.UTF_8,
            )
            ReceiptLedger.appendAll(repo, listOf(record to doc.title))
            ReceiptLedger.append(repo, record, doc.title)
            val rows = ReceiptLedger.readcsv(csv)
            assertEquals(3, rows.size)
            assertEquals(ReceiptLedger.HEADERS, rows[0])
            assertEquals("00001234", rows[1][4])
            assertEquals("", rows[1][8])
            assertEquals("CNY", rows[2][8])
            assertEquals(doc.id, rows[2][11])
            assertEquals(1, JSONObject(rows[2][10]).getJSONObject("amount").getInt("page"))
            ZipFile(ReceiptLedger.exportXlsx(repo)).use { zip ->
                val summary =
                    zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                        .bufferedReader()
                        .readText()
                val details =
                    zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml"))
                        .bufferedReader()
                        .readText()
                assertTrue(summary.contains("<v>30.00</v>"))
                assertTrue(details.contains("苹果"))
                assertTrue(details.contains("香蕉"))
                assertTrue(details.contains("<v>5.00</v>"))
                assertTrue(summary.contains("00001234"))
            }
            assertEquals("$", ReceiptFields.parse("Shop\nTotal $12.00").currency)
            assertEquals("", ReceiptFields.parse("商家\n合计 12.00").currency)
        } finally {
            repo.deleteDocument(doc)
            if (oldCsv == null) csv.delete() else csv.writeBytes(oldCsv)
            if (oldXlsx == null) xlsx.delete() else xlsx.writeBytes(oldXlsx)
            main { store.clear() }
        }
    }
}
