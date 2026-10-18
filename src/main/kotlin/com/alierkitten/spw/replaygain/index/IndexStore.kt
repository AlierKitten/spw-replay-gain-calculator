package com.alierkitten.spw.replaygain.index

import com.alierkitten.spw.replaygain.replaygain.ReplayGainResult
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.Properties

data class AlbumTrack(
    val path: String,
    val integratedLoudness: Double
)

/**
 * SQLite-backed persistence of indexed tracks.
 */
class IndexStore(private val dbPath: Path) {

    private var connection: Connection? = null
    private var driver: Driver? = null

    @Synchronized
    fun init() {
        dbPath.toAbsolutePath().parent?.toFile()?.mkdirs()
        val conn = openConnection()
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
                    album_gain REAL,
                    reference REAL,
                    status TEXT,
                    indexed_at INTEGER
                )
                """.trimIndent()
            )
        }
        connection = conn
    }

    /**
     * `DriverManager` is initialised by the host application long before this plugin's
     * classloader (and the bundled `lib/sqlite-jdbc.jar`) exists, so the driver is never
     * discovered through `META-INF/services/java.sql.Driver`. Load and register it with
     * this plugin's own classloader first; fall back to a direct driver connection in case
     * `DriverManager` still refuses the driver because of classloader visibility.
     */
    private fun openConnection(): Connection {
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
        return try {
            DriverManager.getConnection(url)
        } catch (error: SQLException) {
            sqliteDriver.connect(url, Properties()) ?: throw error
        }
    }

    @Synchronized
    fun isIndexed(trackKey: String): Boolean {
        val conn = connection ?: return false
        conn.prepareStatement(
            "SELECT 1 FROM indexed_tracks WHERE track_key = ? AND status = 'done' LIMIT 1"
        ).use { statement ->
            statement.setString(1, trackKey)
            statement.executeQuery().use { result -> return result.next() }
        }
    }

    @Synchronized
    fun markIndexed(
        trackKey: String,
        trackId: String,
        path: String,
        album: String,
        result: ReplayGainResult
    ) {
        val conn = connection ?: return
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO indexed_tracks
                (track_key, track_id, path, album, integrated_loudness, true_peak_db,
                 track_gain, track_peak, album_gain, reference, status, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            statement.setNull(9, Types.REAL)
            statement.setDouble(10, result.referenceLoudness)
            statement.setString(11, "done")
            statement.setLong(12, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    @Synchronized
    fun getAlbumTracks(album: String): List<AlbumTrack> {
        val conn = connection ?: return emptyList()
        conn.prepareStatement(
            "SELECT path, integrated_loudness FROM indexed_tracks " +
                "WHERE album = ? AND status = 'done' AND integrated_loudness IS NOT NULL"
        ).use { statement ->
            statement.setString(1, album)
            statement.executeQuery().use { result ->
                val tracks = mutableListOf<AlbumTrack>()
                while (result.next()) {
                    tracks += AlbumTrack(
                        path = result.getString("path"),
                        integratedLoudness = result.getDouble("integrated_loudness")
                    )
                }
                return tracks
            }
        }
    }

    @Synchronized
    fun updateAlbumGain(album: String, albumGain: Double): Int {
        val conn = connection ?: return 0
        conn.prepareStatement(
            "UPDATE indexed_tracks SET album_gain = ? WHERE album = ?"
        ).use { statement ->
            statement.setDouble(1, albumGain)
            statement.setString(2, album)
            return statement.executeUpdate()
        }
    }

    @Synchronized
    fun count(): Int {
        val conn = connection ?: return 0
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM indexed_tracks").use { result ->
                return if (result.next()) result.getInt(1) else 0
            }
        }
    }

    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
        // Keep the global DriverManager registry clean so the plugin classloader
        // (and its bundled jars) can be garbage collected after the plugin unloads.
        driver?.let { instance -> runCatching { DriverManager.deregisterDriver(instance) } }
        driver = null
    }

    private companion object {
        const val SQLite_DRIVER_CLASS = "org.sqlite.JDBC"
    }
}
