package cn.paperforge.app.system

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ScanTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(cn.paperforge.app.R.string.shortcut_scan)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val open = Runnable {
            if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(SystemEntrypoints.pending(this, SystemEntrypoints.SCAN))
            else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(SystemEntrypoints.intent(this, SystemEntrypoints.SCAN))
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }
}
