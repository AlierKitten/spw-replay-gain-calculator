package com.alierkitten.spw.replaygain.index

import com.alierkitten.spw.replaygain.replaygain.ReplayGainResult
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * SQLite-backed persistence of indexed tracks.
 *
 * The connection is opened **lazily** on the first database operation and is
 * automatically closed (and the JDBC driver deregistered) once the store has
 * been idle for [idleTimeoutMs]. This matters on Windows: while a connection is
 * open, sqlite-jdbc keeps its native library loaded, which pins the plugin
 * classloader and holds `sqlite-jdbc-*.jar` open — preventing the plugin from
 * being deleted or upgraded ("另一个程序正在使用此文件"). Closing on idle lets
 * the host unload the plugin cleanly.
 */
class IndexStore(private val dbPath: Path) {

    private val lock = Any()
    @Volatile
    private var connection: Connection? = null
    private var driver: Driver? = null

    private val idleTimeoutMs = 30_000L
    private val idleNanos = idleTimeoutMs * 1_000_000L

    private val idleWatcher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "spw-replaygain-sqlite-idle").apply { isDaemon = true }
            }
        )

    init {
        // Poll for idleness; closing is cheap and harmless if recently used.
        idleWatcher.scheduleAtFixedRate(
            { runCatching { closeIfIdle() } },
            idleTimeoutMs,
            idleTimeoutMs / 2,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Opens the connection on first use and keeps it alive for subsequent
     * calls. Table creation is deferred here so nothing touches the database
     * until an operation actually needs it.
     */
    private fun ensureOpen(): Connection = synchronized(lock) {
        val existing = connection
        if (existing != null && !existing.isClosed) {
            touch()
            return existing
        }
        val opened = openConnection()
        connection = opened
        touch()
        opened
    }

    private fun touch() {
        lastAccessNanos = System.nanoTime()
    }

    private var lastAccessNanos = 0L

    /**
     * `DriverManager` is initialised by the host application long before this plugin's
     * classloader (and the bundled `lib/sqlite-jdbc.jar`) exists, so the driver is never
     * discovered through `META-INF/services/java.sql.Driver`. Load and register it with
     * this plugin's own classloader first; fall back to a direct driver connection in case
     * `DriverManager` still refuses the driver because of classloader visibility.
     */
    private fun openConnection(): Connection {
        dbPath.toAbsolutePath().parent?.toFile()?.mkdirs()
        val loader = IndexStore::class.java.classLoader
        val sqliteDriver = try {
            Class.forName(SQLite_DRIVER_CLASS, true, loader)
                .getDeclaredConstructor()
                .newInstance() as Driver
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "无法加载 SQLite JDBC 驱动 $SQLite_DRIVER_CLASS，" +
                    "请确认 sqlite-jdbc 已打包进插件的 lib/ 目录",
                error
            )
        }
        driver = sqliteDriver
        runCatching { DriverManager.registerDriver(sqliteDriver) }

        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val conn = try {
            DriverManager.getConnection(url)
        } catch (error: SQLException) {
            sqliteDriver.connect(url, Properties()) ?: throw error
        }

        conn.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS indexed_tracks (
                    track_key TEXT PRIMARY KEY,
                    track_id TEXT,
                    path TEXT NOT NULL,
                    album TEXT,
                    integrated_loudness REAL,
                    true_peak_db REAL,
                    track_gain REAL,
                    track_peak REAL,
                    reference REAL,
                    status TEXT,
                    indexed_at INTEGER
                )
                """.trimIndent()
            )
        }
        return conn
    }

    fun isIndexed(trackKey: String): Boolean = synchronized(lock) {
        val conn = ensureOpen()
        conn.prepareStatement(
            "SELECT 1 FROM indexed_tracks WHERE track_key = ? AND status = 'done' LIMIT 1"
        ).use { statement ->
            statement.setString(1, trackKey)
            statement.executeQuery().use { result -> return result.next() }
        }
    }

    fun markIndexed(
        trackKey: String,
        trackId: String,
        path: String,
        album: String,
        result: ReplayGainResult
    ) = synchronized(lock) {
        val conn = ensureOpen()
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO indexed_tracks
                (track_key, track_id, path, album, integrated_loudness, true_peak_db,
                 track_gain, track_peak, reference, status, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, trackKey)
            statement.setString(2, trackId)
            statement.setString(3, path)
            statement.setString(4, album)
            statement.setDouble(5, result.integratedLoudness)
            statement.setDouble(6, result.truePeakDb)
            statement.setDouble(7, result.trackGainDb)
            statement.setDouble(8, result.trackPeakLinear)
            statement.setDouble(9, result.referenceLoudness)
            statement.setString(10, "done")
            statement.setLong(11, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun count(): Int = synchronized(lock) {
        val conn = ensureOpen()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM indexed_tracks").use { result ->
                return if (result.next()) result.getInt(1) else 0
            }
        }
    }

    /**
     * Closes the connection and deregisters the driver immediately.
     */
    fun close() = synchronized(lock) {
        runCatching { connection?.close() }
        connection = null
        // Keep the global DriverManager registry clean so the plugin classloader
        // (and its bundled jars) can be garbage collected after the plugin unloads.
        driver?.let { instance -> runCatching { DriverManager.deregisterDriver(instance) } }
        driver = null
    }

    /**
     * Closes the connection only if it has been idle past [idleTimeoutMs], so the
     * SQLite native library is unloaded and the plugin jar is no longer held open.
     */
    private fun closeIfIdle() = synchronized(lock) {
        val conn = connection ?: return
        if (conn.isClosed) {
            connection = null
            return
        }
        if (System.nanoTime() - lastAccessNanos < idleNanos) return
        close()
    }

    /**
     * Stops the idle watcher and releases all database resources.
     */
    fun shutdown() {
        close()
        runCatching { idleWatcher.shutdownNow() }
    }

    private companion object {
        const val SQLite_DRIVER_CLASS = "org.sqlite.JDBC"
    }
}
