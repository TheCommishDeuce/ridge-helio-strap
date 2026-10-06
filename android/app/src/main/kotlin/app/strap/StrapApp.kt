package app.strap

import android.app.Application
import app.strap.pairing.KeyVault
import app.strap.store.LocalStore
import app.strap.sync.AlarmKeeper
import app.strap.sync.Background
import app.strap.sync.SyncRunner
import app.strap.sync.ZoneLog

/** Process-wide singletons. Small enough that a DI framework would be ceremony. */
class StrapApp : Application() {
    val store: LocalStore by lazy { LocalStore(this) }
    val vault: KeyVault by lazy { KeyVault(this) }
    val alarms: AlarmKeeper by lazy { AlarmKeeper(this) }
    val background: Background by lazy { Background(this) }
    val zones: ZoneLog by lazy { ZoneLog(this) }

    override fun onCreate() {
        super.onCreate()
        zones.record() // a backstop for a change the broadcast missed (D31)
    }
    val syncRunner: SyncRunner by lazy { SyncRunner(this, store, vault, alarms) }
}
