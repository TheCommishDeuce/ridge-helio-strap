package app.strap

import android.app.Application
import app.strap.api.ApiClient
import app.strap.api.BackupServer
import app.strap.api.LocalReader
import app.strap.api.Reader
import app.strap.pairing.KeyVault
import app.strap.store.LocalStore
import app.strap.sync.AlarmKeeper
import app.strap.sync.Background
import app.strap.sync.Owner
import app.strap.sync.PhoneScience
import app.strap.sync.Restore
import app.strap.sync.SyncRunner
import app.strap.sync.ZoneLog

/** Process-wide singletons. Small enough that a DI framework would be ceremony. */
class StrapApp : Application() {
    val store: LocalStore by lazy { LocalStore(this) }
    val vault: KeyVault by lazy { KeyVault(this) }
    val alarms: AlarmKeeper by lazy { AlarmKeeper(this) }
    val background: Background by lazy { Background(this) }
    val zones: ZoneLog by lazy { ZoneLog(this) }

    /** The backup server, if one is set up (D34: it is optional). */
    val server: () -> BackupServer? = { vault.loadServer()?.let(::ApiClient) }
    val owner: Owner by lazy { Owner(store, server, zones) }
    val science: PhoneScience by lazy { PhoneScience(store, server, owner) }
    val restore: Restore by lazy { Restore(store, server, owner, science).also { r -> science.backfill = r::backfill } }

    /** What the screens read: the numbers this phone works out (D34). */
    fun reader(): Reader = LocalReader(science, store, owner)

    /** Set up with no server (D34 S5): the phone keeps everything itself. */
    val withoutServer: Boolean get() = store.metaValue(NO_SERVER) == "1"

    fun setUpWithoutServer() {
        store.setMetaValue(NO_SERVER, "1")
        owner.ensureBaseZone()
    }

    override fun onCreate() {
        super.onCreate()
        zones.record() // a backstop for a change the broadcast missed (D31)
        background.scheduleBackup(vault.loadServer() != null)
        restore // wires the one-time backfill of the server's history into the science
        app.strap.ui.widget.WidgetUpdater.watch(this)
    }
    val syncRunner: SyncRunner by lazy { SyncRunner(this, store, vault, alarms) }

    private companion object {
        const val NO_SERVER = "set_up_without_server"
    }
}
