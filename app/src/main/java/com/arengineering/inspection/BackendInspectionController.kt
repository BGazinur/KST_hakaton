package com.arengineering.inspection

import android.content.res.Resources
import android.util.Base64
import androidx.annotation.StringRes
import com.arengineering.inspection.cv.EquipmentLookupRequest
import com.arengineering.inspection.cv.CodeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class BackendInspectionController(
    private val strings: Resources,
    private val scope: CoroutineScope,
) : InspectionUiController {
    private val api = TerminalServer()
    private val model = MutableStateFlow(InspectionUiState())
    private var operation: Job? = null
    private var historyLoad: Job? = null
    private var liveUpdates: Job? = null
    private var liveUpdatesEnabled = false
    private var passport: JSONObject? = null
    private var selectedCheckIds: Set<String> = emptySet()
    private val results = mutableListOf<StepResult>()
    override val state = model.asStateFlow()

    fun terminalConnection() = api.copyConnection()

    suspend fun reportPdf(id: Long): ByteArray = api.reportPdf(id)

    suspend fun reviewFrame(stepId: String, photo: ReviewPhoto): JSONObject {
        val current = model.value
        val equipment = checkNotNull(current.equipment).equipment.id.toLong()
        val stage = current.stage as? InspectionStage.InspectNode
        check(stage?.step?.id == stepId && stage.result == null)
        val body = JSONObject().put("jpeg_base64", Base64.encodeToString(photo.jpeg, Base64.NO_WRAP))
            .put("scope", if (photo.candidate) "candidate" else "frame")
        return JSONObject(api.reviewFrame(equipment, stepId.toLong(), body).requireSuccess())
    }

    fun enableLiveUpdates(enabled: Boolean) {
        if (enabled == liveUpdatesEnabled) return
        liveUpdatesEnabled = enabled
        refreshEquipment()
    }

    fun onEquipmentCode(code: EquipmentLookupRequest) {
        val current = model.value
        val stage = current.stage as? InspectionStage.ScanQr ?: return
        if (current.account == null || stage.lookingUp || stage.error != null) return
        model.value = current.copy(stage = stage.copy(lookingUp = true))
        request(onError = { model.value = model.value.copy(stage = InspectionStage.ScanQr(error = it)) }) {
            val resolved = JSONObject(api.resolveEquipment(code.equipmentCode, code.source == CodeSource.OCR).requireSuccess())
            loadEquipment(resolved.getLong("equipment_id"))
        }
    }

    fun onCorrosionCandidate(confidence: Float?) {
        val current = model.value
        val stage = current.stage as? InspectionStage.InspectNode ?: return
        if (!stage.scanCorrosion || stage.result != null || stage.corrosionConfidence == confidence) return
        model.value = current.copy(stage = stage.copy(corrosionConfidence = confidence))
    }

    override fun dispatch(action: InspectionAction) {
        val current = model.value
        val stage = current.stage
        if (action is InspectionAction.SignIn) {
            if (current.account == null && !current.signingIn) signIn(action)
            return
        }
        if (current.account == null) return

        when (action) {
            InspectionAction.SignOut -> signOut()
            InspectionAction.ScanAgain -> if (stage is InspectionStage.PickEquipment ||
                stage is InspectionStage.ScanQr || stage is InspectionStage.EquipmentPreview ||
                stage is InspectionStage.AnalysisReady
            ) resetInspection()
            InspectionAction.OpenEquipmentPicker -> if (stage is InspectionStage.ScanQr) openPicker()
            is InspectionAction.SelectEquipment -> if (stage is InspectionStage.PickEquipment &&
                !stage.loading && stage.options.any { it.id == action.id }
            ) selectEquipment(stage, action.id.toLong())
            InspectionAction.StartInspection -> if (stage is InspectionStage.EquipmentPreview ||
                stage is InspectionStage.AnalysisReady && stage.error != null
            ) analyze()
            is InspectionAction.SelectChecks -> if (stage is InspectionStage.AnalysisReady && stage.error == null) {
                val selection = stage.checks.map { it.id }.filter { it in action.ids }.toSet()
                if (selection.isNotEmpty()) model.value = current.copy(stage = stage.copy(selectedCheckIds = selection))
            }
            InspectionAction.ContinueInspection -> if (stage is InspectionStage.AnalysisReady && stage.error == null) {
                selectedCheckIds = stage.selectedCheckIds
                if (passport?.optJSONObject("equipment")?.optString("kind") == "centrifugal_pump") {
                    model.value = current.copy(stage = InspectionStage.FindPump)
                } else showStep(0)
            }
            InspectionAction.ConfirmPump -> if (stage is InspectionStage.FindPump) showStep(0)
            is InspectionAction.SetAgentHint -> if (stage is InspectionStage.InspectNode &&
                stage.result == null && stage.agentHint != null && !stage.scanCorrosion
            ) model.value = current.copy(stage = stage.copy(agentHintEnabled = action.enabled))
            is InspectionAction.AcceptVisionObservation -> if (stage is InspectionStage.InspectNode &&
                stage.step.id == action.stepId && stage.result == null && action.observation.isNotBlank()
            ) {
                model.value = current.copy(stage = stage.copy(visionObservation = action.observation.take(220)))
            }
            is InspectionAction.RecordNode -> if (stage is InspectionStage.InspectNode &&
                stage.result == null && stage.step.id == action.stepId && action.outcome != StepOutcome.SKIPPED
            ) {
                val recorded = stepResult(stage.step, action.outcome)
                val result = recorded.copy(observation = stage.visionObservation?.let {
                    strings.getString(R.string.vision_review_report_note, it)
                }, findings = recorded.findings.map {
                    it.copy(confidence = stage.corrosionConfidence)
                })
                model.value = current.copy(
                    stage = stage.copy(result = result), findings = current.findings + result.findings,
                )
            }
            is InspectionAction.NextNode -> if (stage is InspectionStage.InspectNode &&
                stage.step.id == action.stepId && stage.result != null
            ) {
                results.add(stage.result)
                showStep(stage.number)
            }
            is InspectionAction.SkipNode -> if (stage is InspectionStage.InspectNode &&
                stage.step.id == action.stepId && stage.result == null
            ) {
                results.add(StepResult(stage.step, StepOutcome.SKIPPED))
                showStep(stage.number)
            }
            is InspectionAction.SaveReport -> if (stage is InspectionStage.Summary &&
                stage.report.id == action.reportId && !stage.saving && !stage.saved
            ) save(stage, action.note)
            InspectionAction.NewInspection -> if (stage is InspectionStage.Summary && stage.saved) resetInspection()
            else -> Unit
        }
    }

    private fun signIn(action: InspectionAction.SignIn) {
        val address = action.serverAddress.trim()
        if (!api.connect(address)) {
            model.value = model.value.copy(signInError = text(R.string.inspection_bad_address))
            return
        }
        model.value = model.value.copy(signingIn = true, signInError = null, serverAddress = address)
        request(onError = { error ->
            model.value = model.value.copy(signingIn = false, signInError = error)
        }) {
            api.login(action.login.trim(), action.password).requireSuccess()
            val profile = JSONObject(api.profile().requireSuccess())
            val position = when (profile.getString("role")) {
                "admin" -> R.string.inspection_admin
                "supervisor" -> R.string.inspection_supervisor
                else -> R.string.inspection_engineer
            }
            model.value = model.value.copy(
                signingIn = false,
                account = InspectorAccount(profile.getString("display_name"), text(position), profile.getString("email")),
            )
        }
    }

    private fun signOut() {
        operation?.cancel()
        liveUpdates?.cancel()
        historyLoad?.cancel()
        val address = model.value.serverAddress
        model.value = InspectionUiState(signingIn = true, serverAddress = address)
        passport = null
        selectedCheckIds = emptySet()
        results.clear()
        operation = scope.launch {
            try {
                api.logout()
            } catch (_: IOException) {
                // Local sign-out still works when the laptop is unreachable.
            } finally {
                api.disconnect()
                model.value = InspectionUiState(serverAddress = address)
            }
        }
    }

    private fun resetInspection() {
        operation?.cancel()
        liveUpdates?.cancel()
        historyLoad?.cancel()
        passport = null
        selectedCheckIds = emptySet()
        results.clear()
        model.value = InspectionUiState(account = model.value.account, serverAddress = model.value.serverAddress)
    }

    private fun openPicker() {
        model.value = model.value.copy(stage = InspectionStage.PickEquipment(loading = true))
        request(onError = { model.value = model.value.copy(stage = InspectionStage.PickEquipment(error = it)) }) {
            val options = JSONArray(api.equipment().requireSuccess()).objects().map(::equipmentRef)
            model.value = model.value.copy(stage = InspectionStage.PickEquipment(options))
        }
    }

    private fun selectEquipment(picker: InspectionStage.PickEquipment, id: Long) {
        model.value = model.value.copy(stage = InspectionStage.PickEquipment(loading = true))
        request(onError = { model.value = model.value.copy(stage = picker.copy(error = it)) }) {
            loadEquipment(id)
        }
    }

    private suspend fun loadEquipment(id: Long) {
        val snapshot = JSONObject(api.passport(id, checksOnly = false).requireSuccess())
        val card = equipmentCard(snapshot)
        passport = snapshot
        model.value = model.value.copy(
            stage = InspectionStage.EquipmentPreview, equipment = card, findings = card.alerts,
        )
        refreshEquipment()
        historyLoad?.cancel()
        historyLoad = scope.launch {
            try {
                val history = JSONArray(api.history(id).requireSuccess()).objects().map {
                    historyReport(JSONObject(api.report(it.getLong("id")).requireSuccess()))
                }
                val current = model.value
                model.value = current.copy(history = (current.history + history).distinctBy { it.id }.take(20))
            } catch (_: BackendError) {
                // The live passport handles session expiry separately.
            } catch (_: IOException) {
                // History is loaded again when the equipment is selected again.
            } catch (_: JSONException) {
                // Preserve the successfully loaded passport if a report has an invalid payload.
            }
        }
    }

    private fun refreshEquipment() {
        liveUpdates?.cancel()
        val id = model.value.equipment?.equipment?.id?.toLong() ?: return
        if (!liveUpdatesEnabled) return
        liveUpdates = scope.launch {
            while (isActive) {
                try {
                    val snapshot = JSONObject(api.passport(id, checksOnly = false).requireSuccess())
                    val card = equipmentCard(snapshot)
                    val current = model.value
                    val analysisStarted = current.stage != InspectionStage.EquipmentPreview
                    model.value = current.copy(equipment = card, findings = if (analysisStarted) current.findings else card.alerts)
                } catch (error: BackendError) {
                    if (error.status == 401) {
                        expireSession()
                        return@launch
                    }
                    markRefreshFailed()
                } catch (_: IOException) {
                    markRefreshFailed()
                } catch (_: JSONException) {
                    markRefreshFailed()
                }
                delay(1_000)
            }
        }
    }

    private fun markRefreshFailed() {
        val current = model.value
        model.value = current.copy(equipment = current.equipment?.copy(refreshFailed = true))
    }

    private fun expireSession() {
        api.disconnect()
        resetInspection()
        model.value = model.value.copy(account = null, signInError = text(R.string.inspection_session_expired))
    }

    private fun analyze() {
        val id = model.value.equipment?.equipment?.id?.toLong() ?: return
        model.value = model.value.copy(stage = InspectionStage.Analyzing, findings = emptyList())
        request(onError = { model.value = model.value.copy(stage = InspectionStage.AnalysisReady(error = it)) }) {
            val snapshot = JSONObject(api.passport(id, checksOnly = false).requireSuccess())
            val card = equipmentCard(snapshot)
            passport = snapshot
            results.clear()
            val error = if (card.checks.isEmpty()) text(R.string.inspection_no_checks) else null
            model.value = model.value.copy(
                equipment = card, findings = card.alerts,
                stage = InspectionStage.AnalysisReady(
                    missingData = snapshot.isNull("telemetry"), error = error,
                    checks = card.checks, selectedCheckIds = card.checks.map { it.id }.toSet(),
                ),
            )
        }
    }

    private fun showStep(index: Int) {
        val snapshot = checkNotNull(passport)
        val current = model.value
        val steps = snapshot.getJSONArray("checks").objects().filter { it.getString("id") in selectedCheckIds }
        val next = steps.getOrNull(index)
        model.value = current.copy(stage = if (next != null) {
            InspectionStage.InspectNode(
                step(next), index + 1, steps.size, guidance = next.string("acceptance_criteria"),
            )
        } else {
            val card = checkNotNull(current.equipment)
            InspectionStage.Summary(
                InspectionReport(
                    UUID.randomUUID().toString(), card.equipment, System.currentTimeMillis(),
                    results.toList(), current.findings, telemetry(snapshot.getJSONArray("sensors")),
                ),
            )
        })
    }

    private fun save(summary: InspectionStage.Summary, note: String) {
        model.value = model.value.copy(stage = summary.copy(saving = true, error = null))
        request(onError = { model.value = model.value.copy(stage = summary.copy(error = it)) }) {
            val report = summary.report
            val items = JSONArray()
            report.results.forEach { result ->
                val item = JSONObject().put("check_id", result.step.id.toLong()).put("outcome", result.outcome.apiValue)
                val confidence = result.findings.firstOrNull()?.confidence
                val observation = listOfNotNull(result.observation, confidence?.let {
                    strings.getString(R.string.cv_corrosion_candidate, formatConfidence(it))
                }).joinToString("\n")
                if (observation.isNotEmpty()) item.put("observation", observation)
                items.put(item)
            }
            val payload = JSONObject().put("equipment_id", report.equipment.id.toLong())
                .put("client_request_id", report.id).put("notes", note.trim()).put("items", items)
            passport?.optJSONObject("telemetry")?.getLong("id")?.let { payload.put("telemetry_sample_id", it) }
            val id = JSONObject(api.saveReport(payload).requireSuccess()).getLong("id")
            val saved = historyReport(JSONObject(api.report(id).requireSuccess()))
            model.value = model.value.copy(
                stage = summary.copy(report = saved, saved = true),
                history = (listOf(saved) + model.value.history.filterNot { it.id == saved.id }).take(20),
            )
        }
    }

    private fun request(onError: (String) -> Unit, action: suspend () -> Unit) {
        operation?.cancel()
        operation = scope.launch {
            try {
                action()
            } catch (e: BackendError) {
                val login = model.value.account == null
                val message = if (e.status == 401) {
                    if (login) text(R.string.inspection_bad_login) else text(R.string.inspection_session_expired)
                } else e.message ?: text(R.string.inspection_server_error)
                if (e.status == 401 && !login) {
                    expireSession()
                } else onError(message)
            } catch (_: IOException) {
                onError(text(R.string.telemetry_server_unreachable))
            } catch (_: JSONException) {
                onError(text(R.string.telemetry_server_invalid_response))
            }
        }
    }

    private fun equipmentRef(value: JSONObject): EquipmentRef {
        val serialNumber = value.string("serial_number")
        return EquipmentRef(
            value.getLong("id").toString(), value.getString("name"),
            serialNumber ?: value.getString("asset_tag"), value.optString("kind"), hasSerialNumber = serialNumber != null,
        )
    }

    private fun equipmentCard(value: JSONObject) = EquipmentCard(
        equipmentRef(value.getJSONObject("equipment")),
        specifications = equipmentFields(value.getJSONObject("equipment")),
        components = value.getJSONArray("components").objects().map {
            ComponentCard(it.getLong("id").toString(), it.getString("name"), equipmentFields(it))
        },
        readings = value.getJSONArray("sensors").objects().map(::passportReading).sortedBy { reading ->
            when (reading.code) {
                "overall_rms_mm_s" -> 0
                "pump_bearing_c" -> 1
                "motor_winding_c" -> 2
                "speed_rpm" -> 3
                else -> 4
            }
        },
        sampleUpdatedAt = value.optJSONObject("telemetry")?.string("updated_at")?.let(::parseServerTime),
        replay = value.optJSONObject("telemetry")?.optString("source") == "replay",
        alerts = findings(value),
        operation = operationFields(value),
        checks = value.getJSONArray("checks").objects().map(::step),
    )

    private fun operationFields(value: JSONObject): List<InspectionField> = buildList {
        value.optJSONObject("operating_state")?.let { state ->
            val status = when (state.optString("state")) {
                "running" -> R.string.equipment_running
                "stopped" -> R.string.equipment_stopped
                else -> R.string.equipment_state_unknown
            }
            add(field(R.string.equipment_operating_state, text(status)))
            state.string("run_hours")?.let { add(field(R.string.equipment_run_hours, it)) }
        }
        value.getJSONArray("maintenance").objects().forEach { plan ->
            val remaining = listOfNotNull(
                plan.string("remaining_days")?.let { strings.getString(R.string.equipment_due_days, it) },
                plan.string("remaining_hours")?.let { strings.getString(R.string.equipment_due_hours, it) },
            )
            val status = plan.string("message") ?: when (plan.optString("status")) {
                "due" -> text(R.string.equipment_due)
                "due_soon" -> text(R.string.equipment_due_soon)
                "scheduled" -> text(R.string.equipment_scheduled)
                else -> text(R.string.equipment_state_unknown)
            }
            add(InspectionField(plan.getString("name"), (listOf(status) + remaining).joinToString(" · ")))
        }
    }

    private fun passportReading(sensor: JSONObject): PassportReading {
        val unit = sensor.getString("unit")
        fun measurement(key: String): String? = (sensor.opt(key) as? Number)?.let {
            "${NumberFormat.getNumberInstance(RUSSIAN_LOCALE).format(it)} $unit"
        }
        val reading = measurement("reading")
        val status = when {
            reading == null -> ReadingStatus.MISSING
            else -> when (sensor.getString("status")) {
                "alarm" -> ReadingStatus.ALARM
                "warning" -> ReadingStatus.WARNING
                "normal" -> ReadingStatus.NORMAL
                else -> ReadingStatus.UNCLASSIFIED
            }
        }
        val limits = listOfNotNull(
            measurement("nominal_value")?.let { field(R.string.equipment_nominal, it) },
            measurement("warning_above")?.let { field(R.string.inspection_threshold, "> $it") },
            measurement("alarm_above")?.let { field(R.string.inspection_alarm, "> $it") },
        )
        return PassportReading(sensor.getString("code"), sensor.getString("label"), reading, status, limits)
    }

    private fun equipmentFields(value: JSONObject): List<InspectionField> = buildList {
        value.string("asset_tag")?.let { add(field(R.string.inspection_asset_tag, it)) }
        value.string("model")?.let { add(field(R.string.equipment_model, it)) }
        value.string("description")?.let { add(field(R.string.inspection_description, it)) }
        value.optJSONObject("specifications")?.let { specs ->
            specs.keys().forEach { key -> add(InspectionField(key, specs.getString(key))) }
        }
    }

    private fun telemetry(sensors: JSONArray): List<InspectionField> = sensors.objects().flatMap { sensor ->
        val label = sensor.getString("label")
        val unit = sensor.getString("unit")
        buildList {
            sensor.string("reading")?.let { add(InspectionField(label, "$it $unit")) }
            sensor.string("warning_above")?.let { add(InspectionField("$label · ${text(R.string.inspection_threshold)}", "> $it $unit")) }
            sensor.string("alarm_above")?.let { add(InspectionField("$label · ${text(R.string.inspection_alarm)}", "> $it $unit")) }
        }
    }

    private fun findings(value: JSONObject): List<InspectionFinding> {
        val readings = value.getJSONArray("sensors").objects().filter { it.getString("status") in listOf("alarm", "warning") }
        val limits = readings.map { sensor ->
            InspectionFinding(
                sensor.getString("code"), sensor.getString("label"),
                text(if (sensor.getString("status") == "alarm") R.string.inspection_alarm else R.string.inspection_threshold),
                text(R.string.inspection_check_reading), FindingSource.SENSOR, telemetry(JSONArray().put(sensor)),
            )
        }
        val plans = value.getJSONArray("maintenance").objects().filter { it.getString("status") in listOf("due", "due_soon") }
        val maintenance = plans.map {
            InspectionFinding(
                "maintenance-${it.getLong("id")}", it.getString("name"),
                it.string("message") ?: it.getString("name"), it.getString("instructions"), FindingSource.REGULATION,
            )
        }
        return limits + maintenance
    }

    private fun step(check: JSONObject) = InspectionStep(
        check.getLong("id").toString(), check.getString("title"), check.getString("instructions"),
        check.string("visual_model"),
        check.string("visual_hint"),
    )

    private fun stepResult(step: InspectionStep, outcome: StepOutcome, check: JSONObject? = findCheck(step.id)): StepResult {
        val findings = if (check != null && outcome in listOf(StepOutcome.POSSIBLE_DEFECT, StepOutcome.CONFIRMED_DEFECT)) {
            listOf(InspectionFinding(
                "visual-${step.id}", step.component, text(outcome.label),
                check.getString("recommendation"), FindingSource.VISUAL,
            ))
        } else emptyList()
        return StepResult(step, outcome, findings)
    }

    private fun findCheck(id: String) = passport?.getJSONArray("checks")?.objects()?.find { it.getString("id") == id }

    private fun historyReport(value: JSONObject): InspectionReport {
        val results = value.getJSONArray("items").objects().map { item ->
            val check = item.getJSONObject("check_snapshot")
            val result = stepResult(step(check), StepOutcome.entries.first { it.apiValue == item.getString("outcome") }, check)
            result.copy(observation = item.string("observation"))
        }
        val time = parseServerTime(value.getString("created_at")) ?: 0L
        return InspectionReport(
            value.getLong("id").toString(), equipmentRef(value.getJSONObject("equipment_snapshot")),
            time, results, results.flatMap { it.findings }, telemetry(value.getJSONArray("sensors_snapshot")),
            value.getString("notes"),
        )
    }

    private fun field(@StringRes res: Int, value: String) = InspectionField(text(res), value)
    private fun text(@StringRes res: Int) = strings.getString(res)
}

private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru-RU")

private fun parseServerTime(value: String): Long? = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.parse(value.take(19), ParsePosition(0))?.time

private fun JSONObject.string(key: String) = if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

internal fun formatConfidence(value: Float): String = NumberFormat.getPercentInstance().format(value.toDouble())

private class BackendError(val status: Int, message: String?) : Exception(message)

private fun TerminalResponse.requireSuccess(): String {
    if (!successful) {
        val error = runCatching { JSONObject(body).opt("detail") as? String }.getOrNull()
        throw BackendError(status, error)
    }
    return body
}

private val StepOutcome.apiValue: String
    get() = when (this) {
        StepOutcome.CLEAR -> "no_visible_issue"
        StepOutcome.REVIEW -> "needs_check"
        StepOutcome.POSSIBLE_DEFECT -> "possible_defect"
        StepOutcome.CONFIRMED_DEFECT -> "confirmed_defect"
        StepOutcome.INCONCLUSIVE -> "inconclusive"
        StepOutcome.SKIPPED -> "skipped"
    }
