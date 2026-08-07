package app.aaps.core.interfaces.overview

import android.widget.Button
import android.widget.ImageButton

interface OverviewMenus {
    enum class CharType {
        PRE,
        BG_PARAB,
        TREAT,
        BAS,
        ABS,
        IOB,
        COB,
        IOB_TH,
        DEV,
        BGI,
        SEN,
        VAR_SEN,
        ACT,
        DEVSLOPE,
        HR,
        STEPS,
        FIN_ISF,
        ACC_ISF,
        BG_ISF,
        PP_ISF,
        DUR_ISF,
        FUSE_DRV,
        FUSE_GRD,
    }

    val setting: List<Array<Boolean>>
    fun loadGraphConfig()
    fun setupChartMenu(chartButton: ImageButton, scaleButton: Button)
    fun enabledTypes(graph: Int): String
    fun isEnabledIn(type: CharType): Int
    fun scaleString(rangeToDisplay: Int): String
    fun isActiveCharTypeData(graph: Int, m: Int): Boolean
}
