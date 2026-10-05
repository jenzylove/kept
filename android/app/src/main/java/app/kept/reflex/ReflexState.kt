package app.kept.reflex

import app.kept.engine.Engine
import java.io.File
import java.math.BigInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PositionDto(val raw: String, val cost: String)

/** One bank attempt, kept so the app can show a receipt for every win. */
@Serializable
data class BankEntry(
    val sellSignature: String,
    val mint: String,
    val profitLamports: Long,
    val amountLamports: Long,
    val bankSignature: String? = null,
    val status: Status,
    val reason: String? = null,
    val sellTime: Long? = null,
    val bankedAt: Long,
    val late: Boolean = false,
) {
    @Serializable
    enum class Status { Confirmed, Failed, TooSmall, NoVault }
}

@Serializable
data class ReflexState(
    /** Newest signature already handled; the next drain only looks past it. */
    val lastSignature: String? = null,
    /** Sells before this moment still update cost basis, but are never banked. Unix seconds. */
    val activatedAt: Long = 0,
    val positions: Map<String, PositionDto> = emptyMap(),
    val log: List<BankEntry> = emptyList(),
) {
    fun enginePositions(): Map<String, Engine.Position> =
        positions.mapValues { Engine.Position(BigInteger(it.value.raw), BigInteger(it.value.cost)) }

    fun withPositions(p: Map<String, Engine.Position>): ReflexState =
        copy(positions = p.filterValues { it.raw.signum() > 0 }.mapValues { PositionDto(it.value.raw.toString(), it.value.cost.toString()) })

    fun withLog(e: BankEntry): ReflexState = copy(log = (listOf(e) + log.filterNot { it.sellSignature == e.sellSignature }).take(200))
}

interface StateStore {
    fun load(): ReflexState
    fun save(state: ReflexState)
}

class JsonFileStore(private val file: File) : StateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): ReflexState =
        if (file.exists()) runCatching { json.decodeFromString<ReflexState>(file.readText()) }.getOrDefault(ReflexState()) else ReflexState()

    override fun save(state: ReflexState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ReflexState.serializer(), state))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
