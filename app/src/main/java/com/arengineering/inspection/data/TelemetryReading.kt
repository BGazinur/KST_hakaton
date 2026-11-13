package com.arengineering.inspection.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "vibration_readings", primaryKeys = ["equipment_id", "measured_at"])
data class VibrationReading(
    @ColumnInfo(name = "equipment_id") val equipmentId: String,
    @ColumnInfo(name = "measured_at") val measuredAt: String,
    @ColumnInfo(name = "speed_rpm") val speedRpm: Int,
    @ColumnInfo(name = "overall_rms_mm_s") val overallRmsMmPerSecond: Double,
    @ColumnInfo(name = "amplitude_1x_mm_s") val amplitude1xMmPerSecond: Double,
    @ColumnInfo(name = "amplitude_2x_mm_s") val amplitude2xMmPerSecond: Double,
    @ColumnInfo(name = "hf_noise_g") val highFrequencyNoiseG: Double,
    @ColumnInfo(name = "source_diagnosis") val sourceDiagnosis: String,
)

@Entity(tableName = "temperature_readings", primaryKeys = ["equipment_id", "measured_at"])
data class TemperatureReading(
    @ColumnInfo(name = "equipment_id") val equipmentId: String,
    @ColumnInfo(name = "measured_at") val measuredAt: String,
    @ColumnInfo(name = "speed_rpm") val speedRpm: Int,
    @ColumnInfo(name = "pump_bearing_c") val pumpBearingCelsius: Double,
    @ColumnInfo(name = "motor_winding_c") val motorWindingCelsius: Double,
    @ColumnInfo(name = "source_diagnosis") val sourceDiagnosis: String,
)
