package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntryWithTime
import app.aaps.database.entities.interfaces.TraceableDBEntry
import java.util.TimeZone

@Entity(
    tableName = TABLE_APS_RESULTS,
    foreignKeys = [ForeignKey(
        entity = APSResult::class,
        parentColumns = ["id"],
        childColumns = ["referenceId"]
    )],
    indices = [Index("referenceId"), Index("timestamp")]
)
data class APSResult(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = null,
    override var timestamp: Long,
    override var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong(),
    var algorithm: Algorithm,
    var glucoseStatusJson: String?,
    var currentTempJson: String?,
    var iobDataJson: String?,
    var profileJson: String?,
    var autosensDataJson: String?,
    var mealDataJson: String?,
    var resultJson: String
) : TraceableDBEntry, DBEntryWithTime {

    enum class Algorithm {
        UNKNOWN,
        AMA,
        SMB,
        AUTO_ISF,

        /**
         * FUSE. Die Spalte speichert den NAMEN als Text (Converters.kt:
         * `fromAlgorithm = algorithm?.name`), deshalb braucht dieser Wert KEINE
         * Room-Migration — das Schema aendert sich nicht.
         *
         * Er wird trotzdem gebraucht: `LoopPlugin` persistiert JEDES APSResult,
         * und `toDb()` endet fuer unbekannte Werte in `error("Unsupported")`.
         * Ohne diesen Eintrag flöge der erste FUSE-Loop-Lauf aus
         * `LoopPlugin.invoke()` heraus — die Methode hat try/finally, aber kein
         * catch. Es kompiliert anstandslos; es knallt erst zur Laufzeit.
         *
         * ACHTUNG BEIM RUECKFLASH: ein Build ohne diesen Enum-Wert kann Zeilen
         * mit `algorithm = "FUSE"` nicht lesen (`Converters.toAlgorithm` nutzt
         * `valueOf`). Betroffen sind die rollenden Abfragen
         * (OpenAPS*Plugin.onStart 24 h, Graph-Worker) — es heilt sich also nach
         * einem Tag von selbst, aber innerhalb des Fensters trifft es den
         * Plugin-Start. Der aisf-Fork sollte denselben Wert kennen.
         */
        FUSE
    }
}