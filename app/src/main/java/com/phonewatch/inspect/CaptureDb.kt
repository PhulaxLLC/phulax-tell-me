package com.phonewatch.inspect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One decrypted request + its response. Bodies are stored capped (see DeepPrefs.bodyCapKb). */
@Entity(tableName = "flow", indices = [Index("time"), Index("uid"), Index("host")])
data class CapFlow(
    @PrimaryKey val id: Long,
    val time: Long,
    val uid: Int,
    val pkg: String,
    val host: String,
    val ip: String,
    val port: Int,
    val company: String?,
    val cats: Int,
    val scheme: String,          // https | http
    val proto: String,           // h2 | http/1.1
    val method: String,
    val url: String,
    val reqHeaders: String,      // "Name: value" lines
    val reqBody: ByteArray?,
    val reqSize: Long,
    val reqType: String?,
    val reqTrunc: Boolean,
    val status: Int = 0,
    val respHeaders: String? = null,
    val respBody: ByteArray? = null,
    val respSize: Long = 0,
    val respType: String? = null,
    val respTrunc: Boolean = false,
    val sens: Int = 0,           // Sensitive.Type bits found in the request
    val note: String? = null,    // e.g. "media: metadata only"
)

/** Per app + server: could PhoneWatch read it, and if not, why. */
@Entity(tableName = "hoststatus")
data class HostStatus(
    @PrimaryKey val key: String,  // "uid|host" (host "*" = whole app)
    val uid: Int,
    val pkg: String,
    val host: String,
    val status: String,           // inspected | cant_inspect | bad_cert | excluded | error
    val detail: String?,
    val count: Int,
    val firstTime: Long,
    val lastTime: Long,
)

data class CapApp(val uid: Int, val pkg: String, val n: Int, val lastTime: Long, val sens: Int, val bytes: Long)
data class FlowHead(
    val id: Long, val time: Long, val uid: Int, val pkg: String, val host: String, val method: String, val url: String,
    val status: Int, val reqSize: Long, val respSize: Long, val sens: Int, val proto: String, val company: String?,
)
data class SensRow(val uid: Int, val pkg: String, val host: String, val company: String?, val sens: Int, val time: Long)

@Dao
interface CapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(rows: List<CapFlow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertStatus(rows: List<HostStatus>)
    @Query("SELECT * FROM hoststatus WHERE `key` = :key") fun status(key: String): HostStatus?
    @Query("SELECT * FROM hoststatus ORDER BY lastTime DESC") fun statuses(): Flow<List<HostStatus>>
    @Query("SELECT * FROM hoststatus") fun statusesNow(): List<HostStatus>
    @Query("SELECT * FROM hoststatus WHERE status = 'cant_inspect'") fun cantInspect(): List<HostStatus>
    @Query("DELETE FROM hoststatus WHERE `key` = :key") fun deleteStatus(key: String)

    @Query("""SELECT uid, pkg, COUNT(*) AS n, MAX(time) AS lastTime, 0 AS sens, SUM(reqSize) AS bytes FROM flow
        WHERE time >= :since GROUP BY uid ORDER BY lastTime DESC""")
    fun apps(since: Long): Flow<List<CapApp>>

    @Query("""SELECT id, time, uid, pkg, host, method, url, status, reqSize, respSize, sens, proto, company FROM flow
        WHERE uid = :uid AND (:q = '' OR url LIKE '%' || :q || '%' OR reqHeaders LIKE '%' || :q || '%'
        OR CAST(reqBody AS TEXT) LIKE '%' || :q || '%' OR method LIKE :q) ORDER BY time DESC LIMIT 500""")
    fun flows(uid: Int, q: String): Flow<List<FlowHead>>

    @Query("""SELECT id, time, uid, pkg, host, method, url, status, reqSize, respSize, sens, proto, company FROM flow
        WHERE (url LIKE '%' || :q || '%' OR reqHeaders LIKE '%' || :q || '%' OR CAST(reqBody AS TEXT) LIKE '%' || :q || '%')
        ORDER BY time DESC LIMIT 500""")
    fun search(q: String): Flow<List<FlowHead>>

    @Query("SELECT * FROM flow WHERE id = :id") fun get(id: Long): CapFlow?
    @Query("SELECT uid, pkg, host, company, sens, time FROM flow WHERE time >= :since AND sens != 0") fun sensSince(since: Long): Flow<List<SensRow>>
    @Query("SELECT uid, pkg, host, company, sens, time FROM flow WHERE time >= :since AND sens != 0") fun sensSinceNow(since: Long): List<SensRow>
    @Query("SELECT * FROM flow WHERE id > :after ORDER BY id LIMIT :n") fun page(after: Long, n: Int): List<CapFlow>
    @Query("SELECT COUNT(*) FROM flow") fun count(): Int
    @Query("SELECT COUNT(*) FROM flow") fun countFlow(): Flow<Int>
    @Query("DELETE FROM flow WHERE time < :before") fun deleteBefore(before: Long): Int
    @Query("DELETE FROM hoststatus WHERE lastTime < :before") fun deleteStatusBefore(before: Long): Int
    @Query("UPDATE flow SET time = time - :ms") fun ageAll(ms: Long)   // debug-only test hook
}

@Database(entities = [CapFlow::class, HostStatus::class], version = 1, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun dao(): CapDao

    companion object {
        private const val NAME = "deep_capture.db"
        private const val KEY_ALIAS = "phonewatch_capture_db_key"
        @Volatile private var inst: CaptureDb? = null
        /** Bumped on wipe so screens drop the old database handle and open the new (empty) one. */
        val generation = androidx.compose.runtime.mutableIntStateOf(0)

        fun get(ctx: Context): CaptureDb = inst ?: synchronized(this) {
            inst ?: build(ctx.applicationContext).also { inst = it }
        }

        private fun build(c: Context): CaptureDb {
            System.loadLibrary("sqlcipher")
            val pass = passphrase(c)
            return Room.databaseBuilder(c, CaptureDb::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(pass))
                .fallbackToDestructiveMigration(true)
                .build()
        }

        /** Random 32-byte database key, stored only wrapped (AES-GCM) by a non-exportable Android Keystore key. */
        private fun passphrase(c: Context): ByteArray {
            val sp = c.getSharedPreferences("deep_db", Context.MODE_PRIVATE)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val wrapped = sp.getString("k", null)
            if (wrapped != null && ks.containsAlias(KEY_ALIAS)) runCatching {
                val raw = Base64.decode(wrapped, Base64.NO_WRAP)
                val cph = Cipher.getInstance("AES/GCM/NoPadding")
                cph.init(Cipher.DECRYPT_MODE, ks.getKey(KEY_ALIAS, null) as SecretKey, GCMParameterSpec(128, raw, 0, 12))
                return cph.doFinal(raw, 12, raw.size - 12)
            }
            // first run (or key lost): start a fresh, empty database
            c.getDatabasePath(NAME).let { f -> listOf(f, File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal")).forEach { it.delete() } }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            val key = kg.generateKey()
            val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cph = Cipher.getInstance("AES/GCM/NoPadding")
            cph.init(Cipher.ENCRYPT_MODE, key)
            val ct = cph.doFinal(pass)
            sp.edit().putString("k", Base64.encodeToString(cph.iv + ct, Base64.NO_WRAP)).commit()
            return pass
        }

        /** "Wipe all captured data": closes and deletes the database and its key. */
        @Synchronized fun wipe(c: Context) {
            synchronized(this) { inst?.close(); inst = null }
            c.getDatabasePath(NAME).let { f -> listOf(f, File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal")).forEach { it.delete() } }
            c.getSharedPreferences("deep_db", Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
            generation.intValue++
        }

        fun exists(c: Context) = c.getDatabasePath(NAME).exists()
    }
}
