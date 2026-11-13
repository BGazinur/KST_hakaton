package com.arengineering.inspection

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import com.arengineering.inspection.data.CurrentEquipmentSample
import com.arengineering.inspection.data.TelemetryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class InspectionApplication : Application() {
    val telemetryDatabase: TelemetryDatabase by lazy { TelemetryDatabase.create(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val equipmentId = TelemetryDatabase.DEMO_EQUIPMENT_ID
                val telemetry = telemetryDatabase.telemetry()
                val times = telemetry.replayTimes(equipmentId)
                if (times.isEmpty()) return@launch

                while (isActive) {
                    for (measuredAt in times) {
                        telemetry.updateCurrent(CurrentEquipmentSample(equipmentId, measuredAt, System.currentTimeMillis()))
                        delay(1_000)
                    }
                }
            } catch (error: SQLiteException) {
                Log.e("ArEngineering", "Could not replay local telemetry", error)
            }
        }
    }
}
