package com.arengineering.inspection.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    @Query("SELECT * FROM vibration_readings WHERE equipment_id = :equipmentId ORDER BY measured_at")
    suspend fun vibrationReadings(equipmentId: String): List<VibrationReading>

    @Query("SELECT * FROM temperature_readings WHERE equipment_id = :equipmentId ORDER BY measured_at")
    suspend fun temperatureReadings(equipmentId: String): List<TemperatureReading>

    @Query("SELECT measured_at FROM vibration_readings WHERE equipment_id = :equipmentId ORDER BY measured_at")
    suspend fun replayTimes(equipmentId: String): List<String>

    @Upsert
    suspend fun updateCurrent(sample: CurrentEquipmentSample)

    @Query(
        """
        SELECT v.*, t.pump_bearing_c AS pumpBearingCelsius,
               t.motor_winding_c AS motorWindingCelsius, t.source_diagnosis AS temperatureDiagnosis,
               c.updated_at_ms AS updatedAtMillis
        FROM current_equipment_samples c
        JOIN vibration_readings v ON v.equipment_id = c.equipment_id AND v.measured_at = c.measured_at
        JOIN temperature_readings t ON t.equipment_id = c.equipment_id AND t.measured_at = c.measured_at
        WHERE c.equipment_id = :equipmentId
        """,
    )
    fun observeCurrent(equipmentId: String): Flow<EquipmentReading?>
}

@Database(
    entities = [VibrationReading::class, TemperatureReading::class, CurrentEquipmentSample::class],
    version = 2,
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetry(): TelemetryDao

    companion object {
        const val DEMO_EQUIPMENT_ID = "1"

        internal fun create(context: Context): TelemetryDatabase {
            val appContext = context.applicationContext
            return Room.databaseBuilder(appContext, TelemetryDatabase::class.java, "telemetry.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = importReplay(db, appContext)
                })
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS current_equipment_samples (
                                equipment_id TEXT NOT NULL PRIMARY KEY,
                                measured_at TEXT NOT NULL,
                                updated_at_ms INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        importReplay(db, appContext)
                    }
                })
                .build()
        }
    }
}

@Entity(tableName = "current_equipment_samples")
data class CurrentEquipmentSample(
    @PrimaryKey @ColumnInfo(name = "equipment_id") val equipmentId: String,
    @ColumnInfo(name = "measured_at") val measuredAt: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMillis: Long,
)

data class EquipmentReading(
    @Embedded val vibration: VibrationReading,
    val pumpBearingCelsius: Double,
    val motorWindingCelsius: Double,
    val temperatureDiagnosis: String,
    val updatedAtMillis: Long,
)

private fun importReplay(db: SupportSQLiteDatabase, context: Context) {
    val equipmentId = TelemetryDatabase.DEMO_EQUIPMENT_ID
    context.assets.open("telemetry/pump-1.tsv").bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            val measuredAt = fields[0]
            val speedRpm = fields[1].toInt()
            db.execSQL(
                """
                INSERT OR IGNORE INTO vibration_readings (
                    equipment_id, measured_at, speed_rpm, overall_rms_mm_s,
                    amplitude_1x_mm_s, amplitude_2x_mm_s, hf_noise_g, source_diagnosis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    equipmentId, measuredAt, speedRpm, fields[2].toDouble(), fields[3].toDouble(),
                    fields[4].toDouble(), fields[5].toDouble(), fields[6],
                ),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO temperature_readings (
                    equipment_id, measured_at, speed_rpm, pump_bearing_c, motor_winding_c, source_diagnosis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(equipmentId, measuredAt, speedRpm, fields[7].toDouble(), fields[8].toDouble(), fields[9]),
            )
        }
    }
}
