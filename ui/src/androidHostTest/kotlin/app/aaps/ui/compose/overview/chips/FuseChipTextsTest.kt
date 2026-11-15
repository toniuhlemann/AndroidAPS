package app.aaps.ui.compose.overview.chips

import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.RatioMode
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale

/**
 * Fork (Toni 15.11.2026): the texts of the nine tiles of the FUSE chip. Synthetic numbers only.
 *
 * The texts are the real ones from the string resources. The numbers are formatted the German way, as on the device.
 */
internal class FuseChipTextsTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private lateinit var texts: FuseChipTexts

    @BeforeEach
    fun setUp() {
        val formatter = mock<DecimalFormatter>()
        whenever(formatter.to0Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.0f", it.arguments[0] as Double) }
        whenever(formatter.to1Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.1f", it.arguments[0] as Double) }
        whenever(formatter.to2Decimal(any())).thenAnswer { String.format(Locale.GERMANY, "%.2f", it.arguments[0] as Double) }
        texts = FuseChipTexts(generatedTextResolver(), formatter)
    }

    private fun chip(
        cycleState: CycleState = CycleState.FREE,
        drive: Double? = 1.2,
        expected: Double? = null,
        insulinReq: Double? = 0.8,
        ratio: Double? = 0.25,
        mode: RatioMode? = RatioMode.RAMP,
        ramp: Int? = 50,
        smb: FuseOverviewSource.ChipSmb? = null,
        wanted: Double? = null,
        lastSmb: FuseOverviewSource.ChipSmb? = null,
        limit: String? = null,
        room: Double? = null,
        channel: FuseOverviewSource.ChannelState? = null,
        guard: Double? = 18.0,
        guardWithMeal: Double? = null,
        tail: Double? = 25.0,
        tailWithMeal: Double? = null,
        meal: FuseOverviewSource.ChipMeal? = null
    ) = FuseOverviewSource.Chip(
        timestamp = now - minute,
        cycleState = cycleState,
        driveMgdlPerMin = drive,
        expectedMealDriveMgdlPerMin = expected,
        insulinReqU = insulinReq,
        smbRatio = ratio,
        ratioMode = mode,
        rampPercent = ramp,
        smb = smb,
        smbWantedU = wanted,
        lastSmb = lastSmb,
        limit = limit,
        roomU = room,
        channel = channel,
        guardMarginMgdl = guard,
        guardWithMealMarginMgdl = guardWithMeal,
        tailMarginMgdl = tail,
        tailWithMealMarginMgdl = tailWithMeal,
        meal = meal
    )

    private fun meal(
        status: AccountStatus? = AccountStatus.AKTIV,
        rest: Double? = 3.2,
        withoutBudget: Boolean = false,
        windowEnd: Long = now + 85 * minute,
        scale: Double? = 1.4,
        calculator: Boolean = false,
        balance: Double? = null
    ) = FuseOverviewSource.ChipMeal(
        status = status, restU = rest, withoutBudget = withoutBudget, windowEndTs = windowEnd, scale = scale, calculator = calculator, balanceU = balance
    )

    private fun tile(chip: FuseOverviewSource.Chip?, tile: ChipTile) = texts.tiles(chip, now).single { it.tile == tile }

    @Test
    fun `the nine tiles come in the order of the dosing chain, then protection and meal`() {
        val tiles = texts.tiles(chip(), now)

        assertThat(tiles.map { it.tile }).containsExactlyElementsIn(ChipTile.entries).inOrder()
        assertThat(tiles.map { it.label })
            .containsExactly("Störung r", "Bedarf", "Anteil", "SMB", "Grenze", "Kanal", "Guard", "Schwanz", "Mahlzeit").inOrder()
    }

    @Test
    fun `before the first cycle every tile shows a dash and never a zero`() {
        val tiles = texts.tiles(null, now)

        assertThat(tiles).hasSize(ChipTile.entries.size)
        tiles.forEach { tile ->
            assertThat(tile.value).isEqualTo(FuseChipTexts.NONE)
            assertThat(tile.pinValue).isEqualTo(FuseChipTexts.NONE)
            assertThat(tile.sub).isEmpty()
            assertThat(tile.tone).isEqualTo(FuseChipTone.NEUTRAL)
        }
    }

    // ---- disturbance, demand, share ----------------------------------------------------------------------------

    @Test
    fun `the disturbance carries its sign, and the expected meal effect stands under it`() {
        val tile = tile(chip(drive = 1.2, expected = 0.9), ChipTile.DRIVE)

        assertThat(tile.value).isEqualTo("+1,20 /min")
        assertThat(tile.sub).isEqualTo("erwartet +0,90")
        assertThat(tile.pinLabel).isEqualTo("r")
        assertThat(tile.pinValue).isEqualTo("+1,20")
        assertThat(tile(chip(drive = -0.4), ChipTile.DRIVE).value).isEqualTo("-0,40 /min")
        assertThat(tile(chip(drive = 1.2), ChipTile.DRIVE).sub).isEmpty()
    }

    @Test
    fun `a value that shows as zero has no sign`() {
        assertThat(tile(chip(drive = 0.004), ChipTile.DRIVE).value).isEqualTo("0,00 /min")
        assertThat(tile(chip(drive = -0.004), ChipTile.DRIVE).value).isEqualTo("0,00 /min")
        assertThat(tile(chip(guard = -0.3), ChipTile.GUARD).value).isEqualTo("0 mg/dl")
        assertThat(tile(chip(guard = 0.3), ChipTile.GUARD).pinValue).isEqualTo("0")
    }

    @Test
    fun `a disturbance that cannot be estimated is a dash`() {
        val tile = tile(chip(drive = null), ChipTile.DRIVE)

        assertThat(tile.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(tile.pinValue).isEqualTo(FuseChipTexts.NONE)
    }

    @Test
    fun `the demand shows its amount, and a dash when it was not calculated`() {
        val tile = tile(chip(insulinReq = 0.8), ChipTile.DEMAND)
        assertThat(tile.value).isEqualTo("0,80 U")
        assertThat(tile.pinLabel).isEqualTo("Bedarf")
        assertThat(tile.pinValue).isEqualTo("0,80 U")

        val none = tile(chip(insulinReq = null), ChipTile.DEMAND)
        assertThat(none.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(none.value).doesNotContain("0")
        // A calculated zero is a zero.
        assertThat(tile(chip(insulinReq = 0.0), ChipTile.DEMAND).value).isEqualTo("0,00 U")
    }

    @Test
    fun `the share names the rule it comes from`() {
        val ramp = tile(chip(ratio = 0.25, mode = RatioMode.RAMP, ramp = 50), ChipTile.RATIO)
        assertThat(ramp.value).isEqualTo("0,25")
        assertThat(ramp.sub).isEqualTo("Rampe 50 %")

        assertThat(tile(chip(mode = RatioMode.CORRECTION, ramp = 80), ChipTile.RATIO).sub).isEqualTo("Korrektur")
        assertThat(tile(chip(mode = RatioMode.REBOUND, ramp = 80), ChipTile.RATIO).sub).isEqualTo("Rebound")
        // On the ramp, but r is unknown: no made up percentage.
        assertThat(tile(chip(mode = RatioMode.RAMP, ramp = null), ChipTile.RATIO).sub).isEmpty()

        val none = tile(chip(ratio = null, mode = null, ramp = null), ChipTile.RATIO)
        assertThat(none.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(none.sub).isEmpty()
    }

    // ---- SMB ---------------------------------------------------------------------------------------------------

    @Test
    fun `an SMB of this cycle shows where it came from`() {
        val tile = tile(chip(smb = FuseOverviewSource.ChipSmb(now - minute, 0.2, SmbSource.EXPECTATION)), ChipTile.SMB)

        assertThat(tile.value).isEqualTo("0,20 U")
        assertThat(tile.sub).isEqualTo("Mahlzeitkonto")
        assertThat(tile(chip(smb = FuseOverviewSource.ChipSmb(now, 0.1, SmbSource.NORMAL)), ChipTile.SMB).sub).isEqualTo("Normalweg")
    }

    @Test
    fun `an SMB that a limit cut shows what FUSE wanted`() {
        val cut = tile(chip(smb = FuseOverviewSource.ChipSmb(now - minute, 0.2, SmbSource.NORMAL), wanted = 0.45), ChipTile.SMB)
        assertThat(cut.value).isEqualTo("0,20 U")
        assertThat(cut.sub).isEqualTo("gewollt 0,45 U")

        // Nothing went out at all: still what was wanted, not the last SMB.
        val blocked = tile(chip(wanted = 0.45, lastSmb = FuseOverviewSource.ChipSmb(now - 5 * minute, 0.3, SmbSource.NORMAL)), ChipTile.SMB)
        assertThat(blocked.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(blocked.sub).isEqualTo("gewollt 0,45 U")
    }

    @Test
    fun `without an SMB in this cycle the last one stands under the dash, with its age`() {
        val minutes = tile(chip(lastSmb = FuseOverviewSource.ChipSmb(now - 12 * minute, 0.3, SmbSource.NORMAL)), ChipTile.SMB)
        assertThat(minutes.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(minutes.sub).isEqualTo("0,30 U vor 12 min")

        val edge = FuseChipTexts.LAST_SMB_HOURS_FROM_MIN
        assertThat(tile(chip(lastSmb = FuseOverviewSource.ChipSmb(now - (edge - 1) * minute, 0.3, SmbSource.NORMAL)), ChipTile.SMB).sub)
            .isEqualTo("0,30 U vor ${edge - 1} min")
        assertThat(tile(chip(lastSmb = FuseOverviewSource.ChipSmb(now - 200 * minute, 0.3, SmbSource.NORMAL)), ChipTile.SMB).sub)
            .isEqualTo("0,30 U vor 3 h")
        // A clock that jumped back never gives a negative age.
        assertThat(tile(chip(lastSmb = FuseOverviewSource.ChipSmb(now + 5 * minute, 0.3, SmbSource.NORMAL)), ChipTile.SMB).sub)
            .isEqualTo("0,30 U vor 0 min")

        val never = tile(chip(), ChipTile.SMB)
        assertThat(never.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(never.sub).isEmpty()
    }

    // ---- limit and channel -------------------------------------------------------------------------------------

    @Test
    fun `nothing holding the SMB back reads free, with the room under it`() {
        val tile = tile(chip(limit = null, room = 2.3), ChipTile.LIMIT)

        assertThat(tile.value).isEqualTo("frei")
        assertThat(tile.sub).isEqualTo("Platz 2,30 U")
        assertThat(tile.tone).isEqualTo(FuseChipTone.FREE)
        assertThat(tile.pinValue).isEqualTo("frei")
        assertThat(tile(chip(limit = null, room = null), ChipTile.LIMIT).sub).isEmpty()
    }

    @Test
    fun `a limit is named in Toni's words and coloured by what the cycle did`() {
        val guard = tile(chip(cycleState = CycleState.GUARD, limit = "guardFloor=70.0", room = 1.0), ChipTile.LIMIT)
        assertThat(guard.value).isEqualTo("Guard")
        assertThat(guard.tone).isEqualTo(FuseChipTone.GUARD)
        assertThat(guard.sub).isEqualTo("Platz 1,00 U")

        val tail = tile(chip(cycleState = CycleState.BLOCKED, limit = "tailHeadroom=-0.1"), ChipTile.LIMIT)
        assertThat(tail.value).isEqualTo("Schwanzprüfung")
        assertThat(tail.tone).isEqualTo(FuseChipTone.BLOCKED)

        assertThat(tile(chip(cycleState = CycleState.ZERO, limit = "SAFETY_ZERO"), ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.BLOCKED)
        assertThat(tile(chip(cycleState = CycleState.UNKNOWN, limit = "drive not estimable"), ChipTile.LIMIT).value).isEqualTo("Signal unreif")
    }

    /** maxSMB or the tail shaping an SMB that still goes out is no alarm, and neither is a deadband doing its work. */
    @Test
    fun `a limit while the cycle counts as free is named without an alarm colour`() {
        val shaped = tile(chip(cycleState = CycleState.FREE, limit = "maxSmb"), ChipTile.LIMIT)
        assertThat(shaped.value).isEqualTo("maxSMB")
        assertThat(shaped.tone).isEqualTo(FuseChipTone.NEUTRAL)
        assertThat(tile(chip(cycleState = CycleState.FREE, limit = "nightDeadband"), ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.NEUTRAL)
        // An SMB beside the running channel, shaped by a limit: the same.
        val besideChannel = chip(cycleState = CycleState.CHANNEL, limit = "iobThHeadroom", smb = FuseOverviewSource.ChipSmb(now, 0.1, SmbSource.NORMAL))
        assertThat(tile(besideChannel, ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.NEUTRAL)
    }

    /** The state word may be "Kanal" or "Null" while the guard holds the SMB back: the tile still warns. */
    @Test
    fun `the guard keeps its colour beside the channel and beside a protective zero`() {
        assertThat(tile(chip(cycleState = CycleState.CHANNEL, limit = "guardFloor=70.0"), ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.GUARD)
        assertThat(tile(chip(cycleState = CycleState.ZERO, limit = "candidate:GUARD_FLOOR"), ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.GUARD)
        // Another lock beside the channel, with no SMB going out: blocked.
        assertThat(tile(chip(cycleState = CycleState.CHANNEL, limit = "correctionExposureLimit"), ChipTile.LIMIT).tone).isEqualTo(FuseChipTone.BLOCKED)
    }

    @Test
    fun `the limit table knows the names FUSE writes, and an unknown one shows itself`() {
        fun name(raw: String) = tile(chip(cycleState = CycleState.BLOCKED, limit = raw), ChipTile.LIMIT).value

        assertThat(name("candidate:GUARD_FLOOR")).isEqualTo("Guard")
        assertThat(name("finalShortened:GUARD_FLOOR")).isEqualTo("Guard")
        assertThat(name("finalVerify:TAIL_LIABILITY")).isEqualTo("Schwanzprüfung")
        assertThat(name("liveness:demandRatioCap")).isEqualTo("Bedarfsanteil")
        assertThat(name("correctionDeadband")).isEqualTo("Korrektur-Totband")
        assertThat(name("nightDeadband")).isEqualTo("Nacht-Totband")
        assertThat(name("reboundDeadband")).isEqualTo("Rebound-Totband")
        assertThat(name("maxIobHeadroom")).isEqualTo("maxIOB")
        assertThat(name("maxIOB=4.0")).isEqualTo("maxIOB")
        assertThat(name("iobTH=2.0")).isEqualTo("iobTH")
        assertThat(name("correctionExposureLimit")).isEqualTo("Expositionsgrenze")
        assertThat(name("mealExposureLimit")).isEqualTo("Expositionsgrenze")
        assertThat(name("measuredDescentRisk")).isEqualTo("Abwärtsrisiko")
        assertThat(name("expectationDescentRisk")).isEqualTo("Abwärtsrisiko")
        assertThat(name("REVERSAL_CONFIRMED")).isEqualTo("Korrekturriegel")
        assertThat(name("mealBalance")).isEqualTo("Bilanz G")
        assertThat(name("ledgerHold")).isEqualTo("Ledger")
        assertThat(name("PUMP_GATE")).isEqualTo("Pumpe")
        assertThat(name("SMB_SWITCH_OFF")).isEqualTo("SMB aus")
        assertThat(name("PARTIAL_RECOVERY")).isEqualTo("Teilbasal")
        assertThat(name("SLOW_CORRECTION")).isEqualTo("Korrekturkanal")
        assertThat(name("PUBLICATION_GATE no commitment")).isEqualTo("Publikations-Tor")
        assertThat(name("SAFETY_HOLD")).isEqualTo("Schutzhalt")
        // No word for it: its own name, without what follows.
        assertThat(name("eineNeueGrenze=1.5|zusatz")).isEqualTo("eineNeueGrenze")
    }

    @Test
    fun `the channel is off, waiting, or shows its factor with the rate`() {
        assertThat(tile(chip(channel = null), ChipTile.CHANNEL).value).isEqualTo("aus")

        val waiting = tile(chip(channel = FuseOverviewSource.ChannelState(phase = "NONE", reason = "WAIT_ENTRY")), ChipTile.CHANNEL)
        assertThat(waiting.value).isEqualTo("wartet")
        assertThat(waiting.sub).isEmpty()

        val running = tile(chip(channel = FuseOverviewSource.ChannelState(phase = "RUNNING", rateUPerH = 1.6, extraUPerH = 0.8)), ChipTile.CHANNEL)
        assertThat(running.value).isEqualTo("×2")
        assertThat(running.sub).isEqualTo("1,60 U/h")
        assertThat(running.pinValue).isEqualTo("×2")

        // A factor that is not whole keeps the decimals it needs.
        val fine = tile(chip(channel = FuseOverviewSource.ChannelState(phase = "PENDING", rateUPerH = 1.2, extraUPerH = 0.4)), ChipTile.CHANNEL)
        assertThat(fine.value).isEqualTo("×1,5")

        // Active, but no extra part known: it runs, without a made up factor.
        val noFactor = tile(chip(channel = FuseOverviewSource.ChannelState(phase = "ENDING", rateUPerH = 0.8)), ChipTile.CHANNEL)
        assertThat(noFactor.value).isEqualTo("läuft")
        assertThat(noFactor.sub).isEqualTo("0,80 U/h")
    }

    // ---- guard and tail ----------------------------------------------------------------------------------------

    @Test
    fun `a margin carries its sign and unit, and the margin with the meal stands under it`() {
        val guard = tile(chip(guard = 18.0, guardWithMeal = 42.0), ChipTile.GUARD)
        assertThat(guard.value).isEqualTo("+18 mg/dl")
        assertThat(guard.sub).isEqualTo("mit Mahlzeit +42")
        assertThat(guard.pinLabel).isEqualTo("Guard")
        assertThat(guard.pinValue).isEqualTo("+18")
        assertThat(guard.tone).isEqualTo(FuseChipTone.NEUTRAL)

        val tail = tile(chip(tail = 25.4), ChipTile.TAIL)
        assertThat(tail.value).isEqualTo("+25 mg/dl")
        assertThat(tail.sub).isEmpty()
    }

    @Test
    fun `a margin below zero blocks - the guard in its colour, the tail as blocked`() {
        val guard = tile(chip(guard = -12.0, guardWithMeal = -3.0), ChipTile.GUARD)
        assertThat(guard.value).isEqualTo("-12 mg/dl")
        assertThat(guard.sub).isEqualTo("mit Mahlzeit -3")
        assertThat(guard.tone).isEqualTo(FuseChipTone.GUARD)

        assertThat(tile(chip(tail = -20.0), ChipTile.TAIL).tone).isEqualTo(FuseChipTone.BLOCKED)
    }

    /** The source holds the margins inside two stops. A value at a stop is "at least this far", not that number. */
    @Test
    fun `a margin at a stop says at least`() {
        val high = tile(chip(guard = FuseOverviewSource.MARGIN_UPPER_MGDL, guardWithMeal = FuseOverviewSource.MARGIN_UPPER_MGDL), ChipTile.GUARD)
        assertThat(high.value).isEqualTo("≥ +150 mg/dl")
        assertThat(high.pinValue).isEqualTo("≥ +150")
        assertThat(high.sub).isEqualTo("mit Mahlzeit ≥ +150")

        val low = tile(chip(tail = FuseOverviewSource.MARGIN_LOWER_MGDL), ChipTile.TAIL)
        assertThat(low.value).isEqualTo("≤ -150 mg/dl")
        assertThat(low.tone).isEqualTo(FuseChipTone.BLOCKED)
        // One below the stop is a plain number again.
        assertThat(tile(chip(guard = 149.0), ChipTile.GUARD).value).isEqualTo("+149 mg/dl")
    }

    @Test
    fun `a margin that was not calculated is a dash`() {
        val tile = tile(chip(guard = null, guardWithMeal = null), ChipTile.GUARD)

        assertThat(tile.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(tile.pinValue).isEqualTo(FuseChipTexts.NONE)
        assertThat(tile.sub).isEmpty()
    }

    // ---- meal --------------------------------------------------------------------------------------------------

    @Test
    fun `the meal account shows its rest, its state, the minutes left and the booked scaler`() {
        val tile = tile(chip(meal = meal()), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo("3,20 U Rest")
        assertThat(tile.sub).isEqualTo("aktiv · 85 min · ×1,4")
        assertThat(tile.pinLabel).isEqualTo("Konto")
        assertThat(tile.pinValue).isEqualTo("3,20 U")
        assertThat(tile.tone).isEqualTo(FuseChipTone.NEUTRAL)
    }

    @Test
    fun `every account state has its word`() {
        fun sub(status: AccountStatus) = tile(chip(meal = meal(status = status, windowEnd = 0L, scale = null)), ChipTile.MEAL).sub

        assertThat(sub(AccountStatus.AKTIV)).isEqualTo("aktiv")
        assertThat(sub(AccountStatus.WARTET)).isEqualTo("wartet")
        assertThat(sub(AccountStatus.PAUSIERT)).isEqualTo("pausiert")
        assertThat(sub(AccountStatus.EINGEFROREN)).isEqualTo("eingefroren")
        assertThat(sub(AccountStatus.GESPERRT)).isEqualTo("gesperrt")
        assertThat(sub(AccountStatus.BEENDET)).isEqualTo("beendet")
    }

    @Test
    fun `a window that is over shows no minutes`() {
        assertThat(tile(chip(meal = meal(windowEnd = now - minute)), ChipTile.MEAL).sub).isEqualTo("aktiv · ×1,4")
        assertThat(tile(chip(meal = meal(windowEnd = now + 30_000L)), ChipTile.MEAL).sub).isEqualTo("aktiv · 0 min · ×1,4")
    }

    /** What is left of an ended account can no longer go out. On the closed chip an amount would read as still to come. */
    @Test
    fun `an ended account keeps its rest on the tile and says ended on the closed chip`() {
        val tile = tile(chip(meal = meal(status = AccountStatus.BEENDET, rest = 1.2, windowEnd = now - minute)), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo("1,20 U Rest")
        assertThat(tile.sub).isEqualTo("beendet · ×1,4")
        assertThat(tile.pinLabel).isEqualTo("Konto")
        assertThat(tile.pinValue).isEqualTo("beendet")
        // Every other state keeps the amount: it can still go out later.
        assertThat(tile(chip(meal = meal(status = AccountStatus.PAUSIERT)), ChipTile.MEAL).pinValue).isEqualTo("3,20 U")
        assertThat(tile(chip(meal = meal(status = AccountStatus.GESPERRT)), ChipTile.MEAL).pinValue).isEqualTo("3,20 U")
    }

    @Test
    fun `an account locked by a low is marked`() {
        val tile = tile(chip(meal = meal(status = AccountStatus.GESPERRT)), ChipTile.MEAL)

        assertThat(tile.sub).isEqualTo("gesperrt · 85 min · ×1,4")
        assertThat(tile.tone).isEqualTo(FuseChipTone.GUARD)
    }

    @Test
    fun `marker only says so, and its rest is a plain zero`() {
        val tile = tile(chip(meal = meal(rest = 0.0, withoutBudget = true, scale = null)), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo("Nur Marker")
        assertThat(tile.sub).isEqualTo("aktiv · 85 min")
        assertThat(tile.pinValue).isEqualTo("0,00 U")
    }

    /** The meal is announced, but no account applies: said loudly, never as a rest of zero. */
    @Test
    fun `a meal without its account says so in the blocked colour`() {
        val tile = tile(chip(meal = meal(status = AccountStatus.OHNE_KONTO, rest = null, windowEnd = 0L, scale = null)), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo("ohne Konto")
        assertThat(tile.sub).isEmpty()
        assertThat(tile.pinValue).isEqualTo(FuseChipTexts.NONE)
        assertThat(tile.tone).isEqualTo(FuseChipTone.BLOCKED)

        // An account that applies but has no expectation (no valid curve) carries a rest of zero: not "marker only".
        val off = tile(chip(meal = meal(status = AccountStatus.OHNE_KONTO, rest = 0.0, withoutBudget = true, scale = null)), ChipTile.MEAL)
        assertThat(off.value).isEqualTo("ohne Konto")
        assertThat(off.pinValue).isEqualTo(FuseChipTexts.NONE)
    }

    @Test
    fun `a bolus calculator meal shows the balance G in place of the rest`() {
        val tile = tile(chip(meal = meal(scale = null, calculator = true, balance = 0.7)), ChipTile.MEAL)
        assertThat(tile.value).isEqualTo("G +0,70 U")
        assertThat(tile.sub).isEqualTo("Rechner · 85 min")
        assertThat(tile.pinLabel).isEqualTo("G")
        assertThat(tile.pinValue).isEqualTo("+0,70 U")

        val over = tile(chip(meal = meal(scale = null, calculator = true, balance = -0.4)), ChipTile.MEAL)
        assertThat(over.value).isEqualTo("G -0,40 U")
        assertThat(over.pinValue).isEqualTo("-0,40 U")
    }

    @Test
    fun `a bolus calculator meal without a balance in this cycle falls back to the rest of its account`() {
        val tile = tile(chip(meal = meal(scale = null, calculator = true, balance = null)), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo("3,20 U Rest")
        assertThat(tile.sub).isEqualTo("Rechner · 85 min")
        assertThat(tile.pinLabel).isEqualTo("Konto")
    }

    @Test
    fun `without a meal the tile is a dash and the closed chip still names the account`() {
        val tile = tile(chip(meal = null), ChipTile.MEAL)

        assertThat(tile.value).isEqualTo(FuseChipTexts.NONE)
        assertThat(tile.sub).isEmpty()
        assertThat(tile.pinLabel).isEqualTo("Konto")
        assertThat(tile.pinValue).isEqualTo(FuseChipTexts.NONE)
    }
}
