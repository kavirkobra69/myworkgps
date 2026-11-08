package ir.mmdkavir.smartattendance.data

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

@Entity(tableName = "attendance", indices = [Index(value = ["eventId"], unique = true)])
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val arrivalMillis: Long,
    val zoneId: String,
    val date: String,
    val time: String,
    val workplace: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val status: String,
    val leftMillis: Long? = null,
    val sent: Boolean = false
)

@Entity(tableName = "outbox", indices = [Index(value = ["eventId"], unique = true)])
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val text: String,
    val attempts: Int = 0,
    val createdAt: Long
)

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceRecord): Long

    @Query("SELECT * FROM attendance ORDER BY arrivalMillis DESC")
    fun observeAll(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance ORDER BY arrivalMillis DESC LIMIT 1")
    fun observeLatest(): Flow<AttendanceRecord?>

    @Query("SELECT * FROM attendance ORDER BY arrivalMillis DESC")
    suspend fun all(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance ORDER BY arrivalMillis DESC LIMIT 1")
    suspend fun latest(): AttendanceRecord?

    @Query("UPDATE attendance SET leftMillis = :time WHERE id = :id")
    suspend fun setLeft(id: Long, time: Long)

    @Query("UPDATE attendance SET sent = 1 WHERE eventId = :eventId")
    suspend fun markSent(eventId: String)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: OutboxItem): Long

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxItem>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bump(id: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}

@Database(entities = [AttendanceRecord::class, OutboxItem::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun attendance(): AttendanceDao
    abstract fun outbox(): OutboxDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "attendance.db"
                ).build().also { instance = it }
            }
    }
}
