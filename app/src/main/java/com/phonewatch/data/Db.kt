package com.phonewatch.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** One network connection (or one blocked DNS lookup) made by an app. */
@Entity(tableName = "conn", indices = [Index("time"), Index("uid"), Index("company")])
data class Conn(
    @PrimaryKey val id: Long,
    val time: Long,
    val uid: Int,
    val pkg: String,
    val host: String?,
    val ip: String,
    val port: Int,
    val proto: Int,
    val up: Long = 0,
    val down: Long = 0,
    val blocked: Boolean,
    val company: String?,
    val cats: Int,
    val via: String,          // dns | sni | ip
    val cname: String? = null, // DNS CNAME chain behind the name, e.g. "metrics.site.com > site.sc.omtrdc.net"
)

/** A blocking rule. pkg == null means "all apps". */
@Entity(tableName = "rules", indices = [Index(value = ["kind", "target", "pkg"], unique = true)])
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,          // category | company | domain | exempt | allowdomain
    val target: String,
    val pkg: String? = null,
    val created: Long = System.currentTimeMillis(),
)

/** Debloat history, so every removal can be undone. */
@Entity(tableName = "removals")
data class Removal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val label: String,
    val method: String,        // uninstall | disable
    val time: Long,
    val restored: Boolean = false,
)

data class AppSummary(
    val uid: Int, val pkg: String, val n: Int, val up: Long, val down: Long,
    val blocked: Int, val companies: Int, val hosts: Int, val lastTime: Long,
)

data class CompanyRow(val company: String, val cats: Int, val n: Int, val blocked: Int, val apps: Int, val bytes: Long)

data class HostRow(
    val host: String?, val ip: String, val company: String?, val cats: Int, val n: Int,
    val up: Long, val down: Long, val blocked: Int, val lastTime: Long, val uid: Int, val pkg: String,
)

data class Totals(val n: Int, val blocked: Int, val up: Long, val down: Long, val apps: Int, val companies: Int)

@Dao
interface ConnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertAll(rows: List<Conn>)
    @Query("UPDATE conn SET up = up + :up, down = down + :down WHERE id = :id") fun addBytes(id: Long, up: Long, down: Long)
    @Query("UPDATE conn SET host = :host, company = :company, cats = :cats, via = :via, blocked = :blocked, cname = IFNULL(:cname, cname) WHERE id = :id")
    fun setHost(id: Long, host: String, company: String?, cats: Int, via: String, blocked: Boolean, cname: String?)
    @Query("DELETE FROM conn WHERE time < :before") fun deleteBefore(before: Long): Int
    @Query("DELETE FROM conn") fun deleteAll()

    @Query("SELECT * FROM conn ORDER BY time DESC LIMIT :limit") fun recent(limit: Int): Flow<List<Conn>>
    @Query("SELECT * FROM conn ORDER BY time DESC LIMIT :limit") fun recentList(limit: Int): List<Conn>

    @Query("""SELECT uid, pkg, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down, SUM(blocked) AS blocked,
        COUNT(DISTINCT company) AS companies, COUNT(DISTINCT host) AS hosts, MAX(time) AS lastTime
        FROM conn WHERE time >= :since GROUP BY uid ORDER BY n DESC""")
    fun apps(since: Long): Flow<List<AppSummary>>

    @Query("""SELECT company, cats, COUNT(*) AS n, SUM(blocked) AS blocked, COUNT(DISTINCT uid) AS apps, SUM(up + down) AS bytes
        FROM conn WHERE time >= :since AND company IS NOT NULL GROUP BY company, cats""")
    fun companies(since: Long): Flow<List<CompanyRow>>

    @Query("""SELECT COUNT(*) AS n, IFNULL(SUM(blocked),0) AS blocked, IFNULL(SUM(up),0) AS up, IFNULL(SUM(down),0) AS down,
        COUNT(DISTINCT uid) AS apps, COUNT(DISTINCT company) AS companies FROM conn WHERE time >= :since""")
    fun totals(since: Long): Flow<Totals>

    @Query("""SELECT host, ip, company, cats, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down, SUM(blocked) AS blocked,
        MAX(time) AS lastTime, uid, pkg FROM conn WHERE uid = :uid AND time >= :since GROUP BY IFNULL(host, ip) ORDER BY n DESC""")
    fun hostsForApp(uid: Int, since: Long): Flow<List<HostRow>>

    @Query("""SELECT host, ip, company, cats, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down, SUM(blocked) AS blocked,
        MAX(time) AS lastTime, uid, pkg FROM conn WHERE uid = :uid AND time >= :since GROUP BY IFNULL(host, ip) ORDER BY n DESC""")
    fun hostsForAppNow(uid: Int, since: Long): List<HostRow>

    @Query("""SELECT host, ip, company, cats, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down, SUM(blocked) AS blocked,
        MAX(time) AS lastTime, uid, pkg FROM conn WHERE company = :company AND time >= :since GROUP BY uid, IFNULL(host, ip) ORDER BY n DESC""")
    fun hostsForCompany(company: String, since: Long): Flow<List<HostRow>>

    @Query("""SELECT uid, pkg, host, MAX(ip) AS ip, MAX(company) AS company, MAX(cats) AS cats, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down,
        SUM(blocked) AS blocked, MIN(time) AS firstTime, MAX(time) AS lastTime, MAX(cname) AS cname
        FROM conn WHERE time >= :since GROUP BY uid, IFNULL(host, ip)""")
    fun recipients(since: Long): Flow<List<RecipRow>>

    @Query("""SELECT uid, pkg, host, MAX(ip) AS ip, MAX(company) AS company, MAX(cats) AS cats, COUNT(*) AS n, SUM(up) AS up, SUM(down) AS down,
        SUM(blocked) AS blocked, MIN(time) AS firstTime, MAX(time) AS lastTime, MAX(cname) AS cname
        FROM conn WHERE time >= :since GROUP BY uid, IFNULL(host, ip)""")
    fun recipientsNow(since: Long): List<RecipRow>

    /** pkg -> number of distinct companies contacted since [since] (used by the Debloat screen). */
    @Query("SELECT pkg, COUNT(DISTINCT company) AS n FROM conn WHERE time >= :since AND company IS NOT NULL GROUP BY pkg")
    fun companiesPerPkg(since: Long): List<PkgCount>
}

data class PkgCount(val pkg: String, val n: Int)

/** One app -> one server (domain, or IP when no name is known), with totals. */
data class RecipRow(
    val uid: Int, val pkg: String, val host: String?, val ip: String, val company: String?, val cats: Int, val n: Int,
    val up: Long, val down: Long, val blocked: Int, val firstTime: Long, val lastTime: Long, val cname: String?,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY kind, target") fun all(): Flow<List<Rule>>
    @Query("SELECT * FROM rules") fun allNow(): List<Rule>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(rule: Rule): Long
    @Query("DELETE FROM rules WHERE kind = :kind AND target = :target AND ((:pkg IS NULL AND pkg IS NULL) OR pkg = :pkg)")
    fun delete(kind: String, target: String, pkg: String?)
    @Query("DELETE FROM rules WHERE id = :id") fun deleteId(id: Long)
}

@Dao
interface RemovalDao {
    @Query("SELECT * FROM removals ORDER BY time DESC") fun all(): Flow<List<Removal>>
    @Insert fun insert(r: Removal): Long
    @Query("UPDATE removals SET restored = 1 WHERE pkg = :pkg AND restored = 0") fun markRestored(pkg: String)
    @Query("SELECT * FROM removals WHERE restored = 0") fun activeNow(): List<Removal>
}

@Database(entities = [Conn::class, Rule::class, Removal::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun conn(): ConnDao
    abstract fun rules(): RuleDao
    abstract fun removals(): RemovalDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "phonewatch.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE conn ADD COLUMN cname TEXT") }
                })
                .fallbackToDestructiveMigration(true)
                .build().also { inst = it }
        }
    }
}
