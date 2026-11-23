package de.wigle.offlinenav

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Ein bekannter WLAN-Access-Point aus dem WiGLE-Export. BSSID immer lowercase. */
@Entity(
    tableName = "access_points",
    indices = [Index(value = ["bssid"], unique = true)]
)
data class AccessPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bssid: String,
    val ssid: String,
    val latitude: Double,
    val longitude: Double,
)

@Dao
interface AccessPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AccessPointEntity>)

    @Query("SELECT COUNT(*) FROM access_points")
    fun countFlow(): Flow<Long>

    @Query("SELECT * FROM access_points WHERE bssid IN (:bssids)")
    suspend fun findByBssidsRaw(bssids: List<String>): List<AccessPointEntity>

    /** Zufälliger AP (für Test-Standort). rowid-basiert statt ORDER BY RANDOM(), damit große DBs schnell bleiben. */
    @Query(
        "SELECT * FROM access_points WHERE id >= " +
            "(ABS(RANDOM()) % (SELECT MAX(id) FROM access_points) + 1) ORDER BY id LIMIT 1"
    )
    suspend fun randomOrNull(): AccessPointEntity?

    @Query("DELETE FROM access_points")
    suspend fun clear()
}

/** SQLite erlaubt max. 999 Bind-Parameter (ältere Versionen) → in Blöcken abfragen. */
suspend fun AccessPointDao.findByBssids(bssids: Collection<String>): List<AccessPointEntity> =
    bssids.distinct().chunked(900).flatMap { findByBssidsRaw(it) }

@Database(entities = [AccessPointEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accessPointDao(): AccessPointDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wigle_aps.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
