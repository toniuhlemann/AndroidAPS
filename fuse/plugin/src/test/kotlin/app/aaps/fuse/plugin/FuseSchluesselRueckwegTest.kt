package app.aaps.fuse.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DER RUECKWEG UEBER DIE SICHERUNG (Konsolidierung 2.0).
 *
 * Die AAPS-Sicherung schreibt nur Schluessel, die ein Plugin REGISTRIERT hat und die exportierbar sind
 * (`PreferencesImpl.isExportableKey` ueber die registrierten Schluessel). v4: auch der Import nimmt nur, was
 * `isExportableKey` kennt (`PrefsTransfer`) - ein nicht registrierter Schluessel ginge also in BEIDE Richtungen
 * verloren. Daraus folgt der Vertrag, den dieser Test haelt:
 *  - jeder Schluessel des 1.x-Standes bleibt registriert und gleich exportierbar, auch die entfernten Einstellungen
 *    ([FuseRetiredKey]). Sonst verloere eine Sicherung aus 2.0 ihre Werte still, und der Rueckweg ueber die
 *    1.x-APK fiele auf deren Vorgaben zurueck;
 *  - es kommt kein neuer Schluessel hinzu: er fehlte in jeder alten Sicherung und stuende nach dem Import still auf
 *    seiner Vorgabe (Uebernahmevertrag: fehlende Schluessel nie still). Einzige Ausnahme: seine Vorgabe IST das
 *    bisherige Verhalten ([neuMitNeutralerVorgabe]) - dann fuehrt sein Fehlen zu keiner anderen Funktionskombination
 *    (Vertragspunkt 3), und die Vorgabe ist hier festgehalten;
 *  - eine alte Sicherung mit den entfernten Schluesseln laedt: FUSE liest sie nie, kein Zyklus haengt an ihnen.
 */
class FuseSchluesselRueckwegTest {

    /** Der Schluesselstand von 1.x (Geraetestand vor der Konsolidierung). */
    private val stand1x: Set<String> = setOf(
        "fuse_absorption_credit_window_min", "fuse_bolus_share_lambda", "fuse_calm_recovery_cycles",
        "fuse_calm_recovery_enabled", "fuse_calm_recovery_guard_distance", "fuse_calm_recovery_min_ukf",
        "fuse_calm_treatment_mode", "fuse_conditional_tail_enabled", "fuse_correction_demand_ratio_cap",
        "fuse_correction_exposure_limit_u", "fuse_correction_reversal_guard_enabled", "fuse_correction_series_cap_u",
        "fuse_correction_series_window_min", "fuse_deferred_prime_enabled", "fuse_deferred_prime_end_min",
        "fuse_drive_lower_quantile_pct", "fuse_drive_tau_min", "fuse_early_adaptive_meal_horizon_min",
        "fuse_early_supply_budget_max_u", "fuse_early_supply_enabled", "fuse_early_supply_guard_with_expectation",
        "fuse_early_supply_initial_lifts_guard", "fuse_early_supply_initial_lifts_tail",
        "fuse_early_supply_initial_share", "fuse_early_supply_low_confirm_ts", "fuse_early_supply_max_per_period_u",
        "fuse_early_supply_max_portion_u", "fuse_early_supply_period_min", "fuse_early_supply_scale_after_low",
        "fuse_early_supply_scale_stable", "fuse_early_supply_tail_with_expectation",
        "fuse_evidence_rebound_override_max_min", "fuse_expectation_account_enabled", "fuse_expectation_curve",
        "fuse_expectation_curve_120_u", "fuse_expectation_curve_30_u", "fuse_expectation_curve_60_u",
        "fuse_expectation_curve_90_u", "fuse_expectation_guard_exception_enabled", "fuse_expectation_horizon_min",
        "fuse_expectation_ledger_enabled", "fuse_expectation_phi0_after_low", "fuse_expectation_phi0_stable",
        "fuse_expectation_release_threshold_u", "fuse_expectation_stability_bg_mgdl",
        "fuse_expectation_stability_fall_mgdl_per_min", "fuse_expectation_stability_min",
        "fuse_fast_restraint_enabled", "fuse_forecast_shadow_collection_enabled", "fuse_guard_floor_mgdl",
        "fuse_iob_th_percent", "fuse_liability_horizon_min", "fuse_liveness_bg_min_meal_mgdl",
        "fuse_liveness_bg_min_mgdl", "fuse_liveness_bg_min_night_mgdl",
        "fuse_liveness_booking_exit_without_rearm_enabled", "fuse_liveness_channel_enabled",
        "fuse_liveness_drive_hold_enabled", "fuse_liveness_meal_power_min", "fuse_liveness_rearm_min",
        "fuse_liveness_rebound_evidence_exception_enabled", "fuse_low_gate_horizon_min",
        "fuse_low_gate_min_benefit_mgdl", "fuse_marker_authorises_low", "fuse_marker_boost_max_min",
        "fuse_marker_prime_descent_horizon_min", "fuse_max_smb_u", "fuse_meal_arm_cycles",
        "fuse_meal_demand_ratio_cap", "fuse_meal_exposure_limit_u", "fuse_meal_foundation_enabled",
        "fuse_meal_foundation_end_min", "fuse_meal_foundation_phase_a_share",
        "fuse_meal_foundation_phase_a_upfront_share", "fuse_meal_marker_armed_ts",
        "fuse_meal_marker_expectation_only", "fuse_meal_marker_no_prime", "fuse_meal_marker_stamp",
        "fuse_meal_marker_withdraw_due_ts", "fuse_meal_marker_withdraw_for_ts", "fuse_meal_marker_withdraw_ordnung",
        "fuse_meal_marker_withdraw_quelle", "fuse_night_deadband_enabled", "fuse_night_deadband_mgdl",
        "fuse_night_end_min", "fuse_night_start_min", "fuse_onset_channel_enabled", "fuse_onset_envelope_u",
        "fuse_partial_recovery_enabled", "fuse_positive_correction_rearm_enabled",
        "fuse_positive_descent_horizon_min", "fuse_prime_envelope_u", "fuse_prime_release_enabled",
        "fuse_prime_window_min", "fuse_rearm_confirm_cycles", "fuse_rearm_hold_min", "fuse_rearm_up_ukf",
        "fuse_rebound_deadband_enabled", "fuse_rebound_deadband_mgdl", "fuse_rebound_window_min",
        "fuse_release_horizon_min", "fuse_reversal_confirm_cycles", "fuse_reversal_fall_ukf",
        "fuse_reversal_lookback_min", "fuse_reversal_rebound_ukf", "fuse_rise_ramp_high_r", "fuse_rise_ramp_low_r",
        "fuse_signal_rejoin_enabled", "fuse_smb_ratio", "fuse_smb_ratio_rise", "fuse_tail_floor_mgdl",
        "fuse_tail_guard_enabled", "fuse_tail_recovery_u", "fuse_tbr_end_zero_when_reason_gone",
        "fuse_theil_sen_window_min", "fuse_zero_latch_calm_distance_mgdl", "fuse_zero_latch_calm_exit_min",
        "fuse_zero_latch_enabled", "fuse_zero_latch_reason_gone_exit_cycles",
    )

    /** Davon nicht exportierbar: Zustand, keine Einstellung (Marker, Ruecknahme, Bestaetigung nach Tief). */
    private val nichtExportierbar1x: Set<String> = setOf(
        "fuse_early_supply_low_confirm_ts", "fuse_meal_marker_armed_ts", "fuse_meal_marker_expectation_only",
        "fuse_meal_marker_no_prime", "fuse_meal_marker_stamp", "fuse_meal_marker_withdraw_due_ts",
        "fuse_meal_marker_withdraw_for_ts", "fuse_meal_marker_withdraw_ordnung", "fuse_meal_marker_withdraw_quelle",
    )

    /**
     * Neue Schluessel NACH dem 1.x-Stand, deren Vorgabe genau das bisherige Verhalten ist - je mit dieser Vorgabe. Ein
     * Eintrag hier braucht den Nachweis im Code (Standard = alter Ablauf), nicht nur einen Wert.
     */
    private val neuMitNeutralerVorgabe: Map<String, Double> = mapOf(
        // Schritt 2: 0 = die Schwelle "Mahlzeit laeuft" ist die Rampen-Unterkante, wie vor dieser Einstellung.
        FuseDoubleKey.MealRunningMinR.key to 0.0,
    )

    private val alle get() = fuseSchluesselKlassen.flatMap { it.enumConstants!!.asIterable() }

    /** Was die AAPS-Sicherung mit einem gespeicherten Schluessel tut - dieselbe Regel wie `isExportableKey`. */
    private fun exportierbar(key: String): Boolean = alle.any { it.key == key && it.exportable }

    @Test
    fun `jeder Schluessel des 1x-Standes bleibt registriert und gleich exportierbar`() {
        val fehlt = stand1x - alle.map { it.key }.toSet()
        assertTrue(fehlt.isEmpty()) { "nicht mehr registriert - eine Sicherung verloere sie still: $fehlt" }
        for (k in stand1x) assertEquals(k !in nichtExportierbar1x, exportierbar(k), "Exportierbarkeit von $k")
    }

    @Test
    fun `es kommt kein neuer Schluessel hinzu und keiner doppelt`() {
        val schluessel = alle.map { it.key }
        val neu = schluessel.toSet() - stand1x - neuMitNeutralerVorgabe.keys
        assertTrue(neu.isEmpty()) { "neu - fehlt in jeder alten Sicherung (Uebernahmevertrag pruefen): $neu" }
        assertEquals(schluessel.size, schluessel.toSet().size, "doppelt registriert")
    }

    /** Die Ausnahme haelt nur, solange die Vorgabe das bisherige Verhalten ist - sie darf sich nicht still verschieben. */
    @Test
    fun `neue Schluessel stehen auf ihrer neutralen Vorgabe`() {
        for ((key, neutral) in neuMitNeutralerVorgabe) {
            val k = FuseDoubleKey.entries.single { it.key == key }
            assertEquals(neutral, k.defaultValue, 0.0, "Vorgabe von $key")
            assertTrue(neutral in k.min..k.max) { "neutrale Vorgabe von $key nicht einstellbar" }
        }
        assertTrue(neuMitNeutralerVorgabe.keys.none { it in stand1x }) { "kein 1.x-Schluessel ist neu" }
    }

    /**
     * Das Plugin registriert genau diese Liste - eine zweite, handgepflegte Liste waere die alte Fehlerquelle.
     * v4: `ownPreferences` nimmt die Schluessel selbst; [fuseSchluessel] ist dieselbe Menge wie die Klassenliste.
     */
    @Test
    fun `das Plugin registriert alle Schluesselklassen`() {
        val src = listOf(
            "src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
            "fuse/plugin/src/main/kotlin/app/aaps/fuse/plugin/FusePlugin.kt",
        ).map(::File).first { it.exists() }.readText()
        assertTrue(Regex("""ownPreferences\s*=\s*fuseSchluessel\b""").containsMatchIn(src)) {
            "ownPreferences nutzt nicht fuseSchluessel"
        }
        assertEquals(alle.toSet(), fuseSchluessel.toSet(), "fuseSchluessel ist nicht die Menge aller Schluesselklassen")
        assertEquals(fuseSchluessel.size, fuseSchluessel.toSet().size, "fuseSchluessel enthaelt einen Schluessel doppelt")
        assertTrue(FuseRetiredKey::class.java in fuseSchluesselKlassen)
    }

    /** Die entfernten Einstellungen liest FUSE nie: im Hauptcode steht FuseRetiredKey nur in seiner eigenen Datei. */
    @Test
    fun `entfernte Einstellungen liest FUSE nicht`() {
        val wurzel = listOf("src/main/kotlin", "fuse/plugin/src/main/kotlin").map(::File).first { it.exists() }
        val leser = wurzel.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FuseKeys.kt" }
            .filter { f ->
                f.readLines().map { it.substringBefore("//").trim() }
                    .filterNot { it.startsWith("*") || it.startsWith("/*") }
                    .any { it.contains("FuseRetiredKey.") }
            }
            .map { it.name }.toList()
        assertTrue(leser.isEmpty()) { "liest eine entfernte Einstellung: $leser" }
        assertTrue(FuseRetiredKey.entries.isNotEmpty() && FuseRetiredKey.entries.all { it.exportable })
    }
}
