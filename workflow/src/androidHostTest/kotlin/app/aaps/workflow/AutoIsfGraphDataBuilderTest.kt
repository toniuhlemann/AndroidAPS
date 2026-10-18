package app.aaps.workflow

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Fork (autoISF): the series of the autoISF graphs, as the fork built them in `PrepareIobAutosensGraphDataWorker` from
 * the stored values of the autoISF runs. Synthetic numbers only.
 */
class AutoIsfGraphDataBuilderTest {

    private val minute = 60_000L

    private fun run(min: Int, factor: Double = 1.0, iobTh: Double = 2.0, valid: Boolean = true) =
        AIV(
            timestamp = min * minute,
            acceIsf = factor + 0.1,
            bgIsf = factor + 0.2,
            ppIsf = factor + 0.3,
            driftIsf = 1.0,
            duraIsf = factor + 0.4,
            finalIsf = factor,
            iobThEffective = iobTh,
            isValid = valid
        )

    @Test
    fun `without autoISF as the active APS nothing is offered and nothing is read`() {
        assertThat(AutoIsfGraphDataBuilder.build(available = false, values = listOf(run(1)))).isEqualTo(AutoIsfGraphData())
    }

    @Test
    fun `every series takes its own value of each run, in time order`() {
        val data = AutoIsfGraphDataBuilder.build(available = true, values = listOf(run(10, factor = 1.5, iobTh = 3.0), run(5)))

        assertThat(data.available).isTrue()
        assertThat(data.iobTh).containsExactly(GraphDataPoint(5 * minute, 2.0), GraphDataPoint(10 * minute, 3.0)).inOrder()
        assertThat(data.finalIsf.map { it.value }).containsExactly(1.0, 1.5).inOrder()
        assertThat(data.acceIsf.map { it.value }).containsExactly(1.1, 1.6).inOrder()
        assertThat(data.bgIsf.map { it.value }).containsExactly(1.2, 1.7).inOrder()
        assertThat(data.ppIsf.map { it.value }).containsExactly(1.3, 1.8).inOrder()
        assertThat(data.duraIsf.map { it.value }).containsExactly(1.4, 1.9).inOrder()
    }

    @Test
    fun `an invalid run and a value that is not a number are left out`() {
        val data = AutoIsfGraphDataBuilder.build(
            available = true,
            values = listOf(run(1), run(2, valid = false), run(3, factor = Double.NaN, iobTh = Double.POSITIVE_INFINITY))
        )

        assertThat(data.iobTh.map { it.timestamp }).containsExactly(1 * minute)
        assertThat(data.finalIsf.map { it.timestamp }).containsExactly(1 * minute)
    }
}
