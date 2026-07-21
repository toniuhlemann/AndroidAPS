package app.aaps.iobaction

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.database.AppRepository

/**
 * LocalCommandChannel — Runtime-Bruecke (IobActionCoreExporter-Muster): MainApp injiziert
 * die bereits gebundenen Singletons und reicht sie hier durch; der (nicht DI-verwaltete)
 * Service liest sie. Kein eigener DI-Graph, keine KSP-Kaskade (Lektion 2026-06-26).
 * Solange init() nicht lief, bleibt der Mutationspfad REJECTED_MUTATION_UNAVAILABLE.
 */
object LocalCommandRuntime {

    @Volatile var repository: AppRepository? = null
        private set
    @Volatile var persistenceLayer: PersistenceLayer? = null
        private set
    /** Capability-Matrix A1: der eine ValueLeaseCoordinator (Dagger-Singleton aus plugins/aps),
     *  fuer den nicht-DI-verwalteten Service durchgereicht. */
    @Volatile var valueLeaseCoordinator: app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator? = null
        private set

    fun init(
        repository: AppRepository,
        persistenceLayer: PersistenceLayer,
        valueLeaseCoordinator: app.aaps.plugins.aps.iobaction.AutoIsfValueLeaseCoordinator? = null,
    ) {
        this.repository = repository
        this.persistenceLayer = persistenceLayer
        this.valueLeaseCoordinator = valueLeaseCoordinator
    }
}
