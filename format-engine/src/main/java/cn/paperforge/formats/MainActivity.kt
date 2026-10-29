package cn.paperforge.formats

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 32)
                addView(
                    TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        textSize = 20f
                        text =
                            "PaperForge 格式引擎\n\nOFD / DjVu · 本机版面转换\nXPS / SVG / CBZ / FB2 · MuPDF\n邮件 EML · PDF 容器 CAJ / KDH\nLaTeX · 常用正文与数学\nPDF/A · veraPDF 完整规则校验\n\n已安装，可回到PaperForge选择文件。"
                    }
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "开源许可"
                        setOnClickListener {
                            setContentView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(
                                        Button(this@MainActivity).apply {
                                            text = "返回"
                                            setOnClickListener { showHome() }
                                        }
                                    )
                                    addView(
                                        ScrollView(this@MainActivity).apply {
                                            addView(
                                                TextView(this@MainActivity).apply {
                                                    textSize = 14f
                                                    setPadding(24, 24, 24, 24)
                                                    setTextIsSelectable(true)
                                                    text =
                                                        assets
                                                            .open("layout/licenses.txt")
                                                            .bufferedReader()
                                                            .use { it.readText() } +
                                                            "\n\n" +
                                                            assets
                                                                .open("archive/notices.txt")
                                                                .bufferedReader()
                                                                .use { it.readText() } +
                                                            "\n\n" +
                                                            assets
                                                                .open("archive/MPL-2.0.txt")
                                                                .bufferedReader()
                                                                .use { it.readText() } +
                                                            "\n\n" +
                                                            assets
                                                                .list("native")
                                                                .orEmpty()
                                                                .sorted()
                                                                .joinToString("\n\n") { name ->
                                                                    assets
                                                                        .open("native/$name")
                                                                        .bufferedReader()
                                                                        .use { it.readText() }
                                                                }
                                                }
                                            )
                                        },
                                        LinearLayout.LayoutParams(-1, 0, 1f),
                                    )
                                }
                            )
                        }
                    }
                )
            }
        )
    }
}
