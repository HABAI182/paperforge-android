package cn.paperforge.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.CapturePreset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsProfilesTest {
    @Test
    fun groupedProfilesRoundTripWithoutDevicePathsOrUnrelatedChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = SettingsProfiles(context)
        val settings = UserSettings.exportJson()
        val prefs = context.getSharedPreferences("profiles", 0)
        val original = prefs.all
        try {
            prefs.edit().clear().commit()
            UserSettings.set("theme", "DARK")
            UserSettings.set("quality", 83f)
            profiles.apply(SettingsProfiles.modes.first { it.id == "id" })
            assertEquals("DARK", UserSettings.string("theme", "SYSTEM"))
            assertEquals(83f, UserSettings.number("quality", 0f), .001f)
            assertEquals("COLOR", UserSettings.string("filter", "AUTO"))
            assertEquals(1.5851852f, CapturePreset.current().aspect, .001f)
            val saved = profiles.save("证件专用", setOf("scan"))
            profiles.copy(saved)
            val exported = profiles.export()
            exported
                .getJSONArray("profiles")
                .getJSONObject(0)
                .getJSONObject("values")
                .put("modelPath", "/data/user/0/old/secret")
                .put("quality", 77f)
            // A portable profile can contain other declared keys; an unknown device path is
            // discarded.
            prefs.edit().clear().commit()
            assertEquals(2, profiles.importJson(exported.toString()))
            assertTrue(profiles.saved().none { it.values.has("modelPath") })
            assertFalse(profiles.saved()[1].values.has("quality"))
            assertEquals(0, profiles.importJson(exported.toString()))
            UserSettings.set("quality", 64f)
            profiles.apply(profiles.saved()[1])
            assertEquals(64f, UserSettings.number("quality", 0f), .001f)
            profiles.defaults(setOf("scan"))
            assertEquals("DARK", UserSettings.string("theme", "SYSTEM"))
            assertEquals(64f, UserSettings.number("quality", 0f), .001f)
            assertEquals("AUTO", UserSettings.string("filter", "NONE"))
            assertEquals(0f, UserSettings.number("scanAspect", -1f), .001f)
            val old =
                JSONObject()
                    .put("format", 1)
                    .put("preferences", JSONObject().put("filter", "BW").put("theme", "LIGHT"))
            profiles.importJson(old.toString())
            profiles.apply(profiles.saved().last())
            assertEquals("LIGHT", UserSettings.string("theme", "SYSTEM"))
            assertEquals("BW", UserSettings.string("filter", "NONE"))
        } finally {
            UserSettings.importJson(settings)
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> if (value is String) edit.putString(key, value) }
            edit.commit()
        }
    }
}
