package app.aaps.ui.compose.overview.chips

import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.FuseOverviewSource
import app.aaps.core.interfaces.overview.FuseOverviewSource.AccountStatus
import app.aaps.core.interfaces.overview.FuseOverviewSource.ChipTile
import app.aaps.core.interfaces.overview.FuseOverviewSource.CycleState
import app.aaps.core.interfaces.overview.FuseOverviewSource.RatioMode
import app.aaps.core.interfaces.overview.FuseOverviewSource.SmbSource
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.compose.overview.graphs.fuseBindingCore
import app.aaps.ui.compose.overview.graphs.fuseBindingRef
import app.aaps.ui.compose.overview.graphs.fuseFactorText
import kotlin.math.pow
import kotlin.math.round

/**
 * Fork (Toni 15.11.2026): the texts of the nine tiles of the FUSE chip, from the values of one FUSE cycle.
 *
 * Nothing is calculated here that FUSE calculated before: this only turns numbers into words. A value the cycle does
 * not have is a dash, never a 0. Numbers with a sign get their plus here and not from a format template, because the
 * templates do not know that flag on every platform.
 */
internal class FuseChipTexts(
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter
) {

    /** All tiles in the order they are shown. With `null` (no cycle in this process yet) every tile shows a dash. */
    fun tiles(chip: FuseOverviewSource.Chip?, now: Long): List<FuseChipTileUi> = ChipTile.entries.map { tile ->
        when (tile) {
            ChipTile.DRIVE   -> drive(chip)
            ChipTile.DEMAND  -> demand(chip)
            ChipTile.RATIO   -> ratio(chip)
            ChipTile.SMB     -> smb(chip, now)
            ChipTile.LIMIT   -> limit(chip)
            ChipTile.CHANNEL -> channel(chip)
            ChipTile.GUARD   -> margin(ChipTile.GUARD, CoreUiStrings.fuse_chip_tile_guard, chip?.guardMarginMgdl, chip?.guardWithMealMarginMgdl, FuseChipTone.GUARD)
            ChipTile.TAIL    -> margin(ChipTile.TAIL, CoreUiStrings.fuse_chip_tile_tail, chip?.tailMarginMgdl, chip?.tailWithMealMarginMgdl, FuseChipTone.BLOCKED)
            ChipTile.MEAL    -> meal(chip, now)
        }
    }

    /** The disturbance r, and under it the meal effect the account expects. */
    private fun drive(chip: FuseOverviewSource.Chip?): FuseChipTileUi {
        val r = chip?.driveMgdlPerMin?.takeIf { it.isFinite() }?.let { signed(it, 2) }
        return FuseChipTileUi(
            tile = ChipTile.DRIVE,
            label = rh.gs(CoreUiStrings.fuse_chip_tile_drive),
            value = r?.let { rh.gs(CoreUiStrings.fuse_chip_drive_value, it) } ?: NONE,
            sub = chip?.expectedMealDriveMgdlPerMin?.takeIf { it.isFinite() }
                ?.let { rh.gs(CoreUiStrings.fuse_chip_drive_expected, signed(it, 2)) }.orEmpty(),
            pinLabel = rh.gs(CoreUiStrings.fuse_chip_pin_drive),
            pinValue = r ?: NONE
        )
    }

    /**
     * The demand. Where the normal path calculated none and the meal account carries the cycle, it is the demand of the
     * account - and the tile says so.
     */
    private fun demand(chip: FuseOverviewSource.Chip?): FuseChipTileUi {
        val demand = chip?.insulinReqU?.takeIf { it.isFinite() }
        return FuseChipTileUi(
            tile = ChipTile.DEMAND,
            label = rh.gs(CoreUiStrings.fuse_chip_tile_demand),
            value = demand?.let { insulin(it) } ?: NONE,
            sub = if (demand != null && chip.insulinReqFromAccount) rh.gs(CoreUiStrings.fuse_chip_demand_account) else ""
        )
    }

    /** The SMB share of the demand, and the rule it comes from. */
    private fun ratio(chip: FuseOverviewSource.Chip?): FuseChipTileUi = FuseChipTileUi(
        tile = ChipTile.RATIO,
        label = rh.gs(CoreUiStrings.fuse_chip_tile_ratio),
        value = chip?.smbRatio?.takeIf { it.isFinite() }?.let { decimalFormatter.to2Decimal(it) } ?: NONE,
        sub = when (chip?.ratioMode) {
            RatioMode.RAMP       -> chip.rampPercent?.let { rh.gs(CoreUiStrings.fuse_chip_ratio_ramp, it) }.orEmpty()
            RatioMode.CORRECTION -> rh.gs(CoreUiStrings.fuse_chip_ratio_correction)
            RatioMode.REBOUND    -> rh.gs(CoreUiStrings.fuse_chip_ratio_rebound)
            null                 -> ""
        }
    )

    /**
     * The SMB of this cycle. Under it what FUSE wanted when a limit cut it; else where the SMB came from; and without
     * an SMB in this cycle the last one with its age.
     */
    private fun smb(chip: FuseOverviewSource.Chip?, now: Long): FuseChipTileUi {
        val given = chip?.smb
        val wanted = chip?.smbWantedU
        val last = chip?.lastSmb
        return FuseChipTileUi(
            tile = ChipTile.SMB,
            label = rh.gs(CoreUiStrings.fuse_chip_tile_smb),
            value = given?.let { insulin(it.units) } ?: NONE,
            sub = when {
                wanted != null -> rh.gs(CoreUiStrings.fuse_chip_smb_wanted, insulin(wanted))
                given != null  -> rh.gs(sourceRef(given.source))
                last != null   -> {
                    val minutes = minutesBetween(last.timestamp, now)
                    if (minutes < LAST_SMB_HOURS_FROM_MIN) rh.gs(CoreUiStrings.fuse_chip_smb_last_min, insulin(last.units), minutes)
                    else rh.gs(CoreUiStrings.fuse_chip_smb_last_hours, insulin(last.units), minutes / 60)
                }

                else           -> ""
            }
        )
    }

    /** "frei", or the limit or lock that holds the SMB back; under it the room up to the tightest amount limit. */
    private fun limit(chip: FuseOverviewSource.Chip?): FuseChipTileUi {
        val label = rh.gs(CoreUiStrings.fuse_chip_tile_limit)
        chip ?: return FuseChipTileUi(tile = ChipTile.LIMIT, label = label, value = NONE)
        val raw = chip.limit
        return FuseChipTileUi(
            tile = ChipTile.LIMIT,
            label = label,
            value = if (raw == null) rh.gs(CoreUiStrings.fuse_chip_limit_free) else fuseBindingRef(raw)?.let { rh.gs(it) } ?: fuseBindingCore(raw),
            sub = chip.roomU?.takeIf { it.isFinite() }?.let { rh.gs(CoreUiStrings.fuse_chip_limit_room, insulin(it)) }.orEmpty(),
            tone = when {
                raw == null                                                                                    -> FuseChipTone.FREE
                // No alarm while the cycle counts as free: the limit only shapes an SMB that still goes out (maxSMB,
                // the tail), or it is a deadband doing its quiet work. The same holds for an SMB beside the channel.
                chip.cycleState == CycleState.FREE                                                             -> FuseChipTone.NEUTRAL
                chip.cycleState == CycleState.CHANNEL && chip.smb != null                                      -> FuseChipTone.NEUTRAL
                // The guard keeps its colour also where the state word is another one (channel, protective zero).
                chip.cycleState == CycleState.GUARD || fuseBindingRef(raw) == CoreUiStrings.fuse_binding_guard -> FuseChipTone.GUARD
                else                                                                                           -> FuseChipTone.BLOCKED
            }
        )
    }

    /** The slow correction channel: off, waiting, or its factor with the rate under it. */
    private fun channel(chip: FuseOverviewSource.Chip?): FuseChipTileUi {
        val label = rh.gs(CoreUiStrings.fuse_chip_tile_channel)
        chip ?: return FuseChipTileUi(tile = ChipTile.CHANNEL, label = label, value = NONE)
        val channel = chip.channel
        return FuseChipTileUi(
            tile = ChipTile.CHANNEL,
            label = label,
            value = when {
                channel == null -> rh.gs(CoreUiStrings.fuse_chip_channel_off)
                !channel.active -> rh.gs(CoreUiStrings.fuse_chip_channel_waiting)
                else            -> channel.factor?.takeIf { it.isFinite() }
                    ?.let { rh.gs(CoreUiStrings.fuse_channel_factor, fuseFactorText(it, decimalFormatter)) }
                    ?: rh.gs(CoreUiStrings.fuse_channel_running)
            },
            sub = channel?.takeIf { it.active }?.rateUPerH?.takeIf { it.isFinite() }
                ?.let { rh.gs(CoreUiStrings.value_with_unit, decimalFormatter.to2Decimal(it), rh.gs(InterfacesStrings.profile_ins_units_per_hour)) }
                .orEmpty()
        )
    }

    /** The guard or the tail margin, and under it the same margin with the announced meal. Below zero it blocks. */
    private fun margin(tile: ChipTile, label: TextRef, marginMgdl: Double?, withMealMgdl: Double?, blockedTone: FuseChipTone): FuseChipTileUi {
        val margin = marginMgdl?.takeIf { it.isFinite() }
        val short = margin?.let { marginShort(it) }
        return FuseChipTileUi(
            tile = tile,
            label = rh.gs(label),
            value = when {
                margin == null || short == null -> NONE
                // The word of the lower stop stands alone: it is no number, so it has no unit.
                atLowerStop(margin)             -> short
                else                            -> rh.gs(CoreUiStrings.fuse_chip_margin, short)
            },
            sub = withMealMgdl?.takeIf { it.isFinite() }?.let { rh.gs(CoreUiStrings.fuse_chip_margin_with_meal, marginShort(it)) }.orEmpty(),
            pinValue = short ?: NONE,
            tone = if (margin != null && margin < 0.0) blockedTone else FuseChipTone.NEUTRAL
        )
    }

    private fun atLowerStop(marginMgdl: Double): Boolean = marginMgdl <= FuseOverviewSource.MARGIN_LOWER_MGDL

    /**
     * A margin without its unit. The source holds it inside two stops. At the upper stop it reads "at least this far".
     * At the lower stop it is a word and no number (Toni 25.11.2026): there the source also puts a tail that blocks
     * but whose depth it cannot tell, so a number would claim more than is known - and it read like a glucose value.
     */
    private fun marginShort(marginMgdl: Double): String = when {
        atLowerStop(marginMgdl)                            -> rh.gs(CoreUiStrings.fuse_chip_margin_closed)
        marginMgdl >= FuseOverviewSource.MARGIN_UPPER_MGDL -> rh.gs(CoreUiStrings.fuse_chip_margin_max, signed(FuseOverviewSource.MARGIN_UPPER_MGDL, 0))
        else                                               -> signed(marginMgdl, 0)
    }

    /**
     * The meal: what the account may still give, or for a bolus calculator meal the balance G. Under it the state of the
     * account, the minutes left in the meal window and the scaler booked at the marker.
     */
    private fun meal(chip: FuseOverviewSource.Chip?, now: Long): FuseChipTileUi {
        val label = rh.gs(CoreUiStrings.fuse_chip_tile_meal)
        val account = rh.gs(CoreUiStrings.fuse_chip_pin_account)
        val meal = chip?.meal ?: return FuseChipTileUi(tile = ChipTile.MEAL, label = label, value = NONE, pinLabel = account)
        val balance = meal.balanceU?.takeIf { it.isFinite() }
        val rest = meal.restU?.takeIf { it.isFinite() }
        val parts = listOfNotNull(
            if (meal.calculator) rh.gs(CoreUiStrings.fuse_chip_meal_calculator) else statusRef(meal.status)?.let { rh.gs(it) },
            meal.windowEndTs.takeIf { it > now }?.let { rh.gs(CoreUiStrings.format_mins, minutesBetween(now, it)) },
            meal.scale?.takeIf { it.isFinite() }?.let { rh.gs(CoreUiStrings.fuse_chip_meal_scale, fuseFactorText(it, decimalFormatter)) }
        )
        val sub = when (parts.size) {
            0    -> ""
            1    -> parts[0]
            2    -> rh.gs(CoreUiStrings.fuse_chip_parts_2, parts[0], parts[1])
            else -> rh.gs(CoreUiStrings.fuse_chip_parts_3, parts[0], parts[1], parts[2])
        }
        val tone = when (meal.status) {
            AccountStatus.OHNE_KONTO -> FuseChipTone.BLOCKED
            AccountStatus.GESPERRT   -> FuseChipTone.GUARD
            else                     -> FuseChipTone.NEUTRAL
        }
        return when {
            balance != null                         -> {
                val text = insulinText(signed(balance, 2))
                FuseChipTileUi(
                    tile = ChipTile.MEAL, label = label, value = rh.gs(CoreUiStrings.fuse_chip_meal_balance, text), sub = sub,
                    pinLabel = rh.gs(CoreUiStrings.fuse_chip_pin_balance), pinValue = text, tone = tone
                )
            }

            // Before the rest: an account without an expectation carries a rest of zero, which is not "marker only".
            meal.status == AccountStatus.OHNE_KONTO -> FuseChipTileUi(
                tile = ChipTile.MEAL, label = label, value = rh.gs(CoreUiStrings.fuse_chip_meal_no_account), sub = sub,
                pinLabel = account, pinValue = NONE, tone = tone
            )

            rest != null                            -> FuseChipTileUi(
                tile = ChipTile.MEAL, label = label,
                value = if (meal.withoutBudget) rh.gs(CoreUiStrings.fuse_chip_meal_marker_only) else rh.gs(CoreUiStrings.fuse_chip_meal_rest, insulin(rest)),
                sub = sub, pinLabel = account,
                // The closed chip has room for one of the two, the amount or the state. While the account gives, the
                // amount says most. In every other state the state does (Toni 25.11.2026): a rest that is paused,
                // waiting, frozen, locked or ended is not on its way, and as a bare amount it read as still to come.
                pinValue = statusRef(meal.status)?.takeIf { meal.status != AccountStatus.AKTIV }?.let { rh.gs(it) } ?: insulin(rest),
                tone = tone
            )

            else                                    -> FuseChipTileUi(tile = ChipTile.MEAL, label = label, value = NONE, sub = sub, pinLabel = account, tone = tone)
        }
    }

    /** The account state in one word. "Without account" has no word here: the tile says it as its value. */
    private fun statusRef(status: AccountStatus?): TextRef? = when (status) {
        AccountStatus.AKTIV       -> CoreUiStrings.fuse_chip_meal_status_active
        AccountStatus.WARTET      -> CoreUiStrings.fuse_chip_meal_status_waiting
        AccountStatus.PAUSIERT    -> CoreUiStrings.fuse_chip_meal_status_paused
        AccountStatus.EINGEFROREN -> CoreUiStrings.fuse_chip_meal_status_frozen
        AccountStatus.GESPERRT    -> CoreUiStrings.fuse_chip_meal_status_locked
        AccountStatus.BEENDET     -> CoreUiStrings.fuse_chip_meal_status_ended
        AccountStatus.OHNE_KONTO  -> null
        null                      -> null
    }

    private fun sourceRef(source: SmbSource): TextRef = when (source) {
        SmbSource.EXPECTATION -> CoreUiStrings.fuse_source_expectation
        SmbSource.NORMAL      -> CoreUiStrings.fuse_source_normal
        SmbSource.OTHER       -> CoreUiStrings.fuse_source_other
    }

    /** An insulin amount with two decimals and its unit. */
    private fun insulin(units: Double): String = insulinText(decimalFormatter.to2Decimal(units))

    /**
     * An amount that is already formatted, with the insulin unit AAPS shows everywhere else on the screen ("U", in
     * German "IE") - not a unit of its own, so the chip never reads differently from the IOB chip above it (Toni).
     */
    private fun insulinText(amount: String): String = rh.gs(CoreUiStrings.value_with_unit, amount, rh.gs(CoreUiStrings.insulin_unit_shortname))

    /**
     * A number with its sign: "+1,20", "-0,40", "0,00". Rounded first, so that a value that shows as zero has no sign
     * at all (no "-0,00" and no "+0").
     */
    private fun signed(value: Double, decimals: Int): String {
        val scale = 10.0.pow(decimals)
        // Adding 0.0 turns a negative zero into a plain zero.
        val rounded = round(value * scale) / scale + 0.0
        val text = when (decimals) {
            0    -> decimalFormatter.to0Decimal(rounded)
            1    -> decimalFormatter.to1Decimal(rounded)
            else -> decimalFormatter.to2Decimal(rounded)
        }
        return if (rounded > 0.0) PLUS + text else text
    }

    /** Whole minutes from [fromTs] to [toTs], never below 0. */
    private fun minutesBetween(fromTs: Long, toTs: Long): Int = ((toTs - fromTs).coerceAtLeast(0L) / 60_000L).toInt()

    companion object {

        /** A tile without a value in this cycle: a dash, never 0 (Toni) - the same sign as the insulinReq chip. */
        const val NONE = ChipsViewModel.NOT_CALCULATED

        /** From this age on the last SMB shows in hours. */
        const val LAST_SMB_HOURS_FROM_MIN = 120

        private const val PLUS = "+"
    }
}
