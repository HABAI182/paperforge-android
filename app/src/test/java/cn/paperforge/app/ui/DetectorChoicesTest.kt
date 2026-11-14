package cn.paperforge.app.ui

import cn.paperforge.app.SettingKey
import cn.paperforge.app.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorChoicesTest {
    private val key = SettingKeys.all.first { it.name == "detector" } as SettingKey.Text

    @Test
    fun everyStoredValueKeepsAnOptionAndTheDefaultComesFirst() {
        val values = DetectorChoices.labels.map { it.first }
        // Values are what users have stored; renaming the labels must not drop or change any of them.
        assertEquals(setOf("PAGENET_V2", "HYBRID", "STANDARD", "INDEPENDENT"), values.toSet())
        assertEquals(key.choices, values.toSet())
        assertEquals(values.size, values.toSet().size)
        assertEquals(key.default, values.first())
        assertTrue(DetectorChoices.labels.first().second.contains("推荐"))
        assertEquals(values.toSet(), DetectorChoices.notes.keys)
    }

    @Test
    fun labelsNameTheRealModels() {
        val labels = DetectorChoices.labels.toMap()
        assertTrue(labels.getValue("PAGENET_V2").startsWith("MobileNetV3 ×1.5"))
        assertTrue(labels.getValue("HYBRID").startsWith("PageNet"))
        // The edge detector is classical, not a network: no model name is invented for it.
        assertEquals("传统边缘检测", labels.getValue("STANDARD"))
        assertTrue(labels.getValue("INDEPENDENT").startsWith("DocQuadNet"))
    }
}
