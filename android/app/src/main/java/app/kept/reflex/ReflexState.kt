package app.kept.reflex

import app.kept.engine.Engine
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
    /** "SOL" banks into the vault as SOL. "SKR" swapped the share into SKR and staked it in the vault's name. */
    val asset: String = "SOL",
    val stakeSignature: String? = null,
    val stakedSkrRaw: Long = 0,
    /** SKR bought but under the 1 SKR minimum stake, waiting in the trading wallet for the next win. */
    val waitingSkrRaw: Long = 0,
    /** Last block height at which this signed SOL transaction can land. Null on older receipts. */
    val lastValidBlockHeight: Long? = null,
    /** Earlier signed attempts proven failed or expired before a safe retry. */
    val priorBankSignatures: List<String> = emptyList(),
) {
    @Serializable
    enum class Status { Pending, Confirmed, Failed, TooSmall, NoVault }
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

class StateCorruptionException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

internal fun directorySyncIsUnsupportedOnThisHost(
    error: Throwable,
    osName: String = System.getProperty("os.name") ?: "",
): Boolean = error is AccessDeniedException && osName.startsWith("Windows", ignoreCase = true)

class JsonFileStore(private val file: File) : StateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directory = requireNotNull(file.absoluteFile.parentFile) { "State file needs a parent directory" }
    private val backup = File(directory, file.name + ".bak")
    private val tmp = File(directory, file.name + ".tmp")
    private val backupTmp = File(directory, file.name + ".bak.tmp")

    override fun load(): ReflexState {
        if (!file.exists() && !backup.exists()) return ReflexState()
        decode(file)?.let { return it }
        decode(backup)?.let { return it }
        val primaryFailure = runCatching { file.readText() }.exceptionOrNull()
        throw StateCorruptionException("Reflex state and its backup are unreadable; refusing to reset financial history", primaryFailure)
    }

    override fun save(state: ReflexState) {
        directory.mkdirs()
        writeSynced(tmp, json.encodeToString(ReflexState.serializer(), state))
        checkNotNull(decode(tmp)) { "Reflex state failed validation before commit" }

        // Retain the previous valid generation. Never replace a good backup with corrupt primary data.
        decode(file)?.let { previous ->
            writeSynced(backupTmp, json.encodeToString(ReflexState.serializer(), previous))
            replace(backupTmp, backup)
        }
        replace(tmp, file)
    }

    private fun decode(source: File): ReflexState? =
        if (!source.exists()) null else runCatching {
            json.decodeFromString<ReflexState>(source.readText())
        }.getOrNull()

    private fun writeSynced(target: File, value: String) {
        FileOutputStream(target).use { out ->
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // fsyncing the file is not enough: persist the renamed directory entry before save()
        // returns and a caller is allowed to perform a network side effect.
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (error: AccessDeniedException) {
            // Windows does not allow FileChannel.open() on a directory. Kept never runs there;
            // Windows only hosts the release build. Android/Linux must still fail closed.
            if (!directorySyncIsUnsupportedOnThisHost(error)) throw error
        }
    }
}
