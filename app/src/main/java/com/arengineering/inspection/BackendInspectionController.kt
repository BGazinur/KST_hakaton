package com.arengineering.inspection

import android.content.res.Resources
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
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
    private var passport: JSONObject? = null
    private val results = mutableListOf<StepResult>()
    override val state = model.asStateFlow()

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
            InspectionAction.ContinueInspection -> if (stage is InspectionStage.AnalysisReady && stage.error == null) {
                showStep(0)
            }
            is InspectionAction.RecordNode -> if (stage is InspectionStage.InspectNode &&
                stage.result == null && stage.step.id == action.stepId && action.outcome != StepOutcome.SKIPPED
            ) {
                val result = stepResult(stage.step, action.outcome)
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
        val address = model.value.serverAddress
        model.value = InspectionUiState(signingIn = true, serverAddress = address)
        passport = null
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
        passport = null
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
            val snapshot = JSONObject(api.passport(id, checksOnly = false).requireSuccess())
            val history = JSONArray(api.history(id).requireSuccess()).objects().map {
                historyReport(JSONObject(api.report(it.getLong("id")).requireSuccess()))
            }
            passport = snapshot
            model.value = model.value.copy(
                stage = InspectionStage.EquipmentPreview, equipment = equipmentCard(snapshot), history = history,
            )
        }
    }

    private fun analyze() {
        val id = model.value.equipment?.equipment?.id?.toLong() ?: return
        model.value = model.value.copy(stage = InspectionStage.Analyzing, findings = emptyList())
        request(onError = { model.value = model.value.copy(stage = InspectionStage.AnalysisReady(error = it)) }) {
            val snapshot = JSONObject(api.passport(id, checksOnly = false).requireSuccess())
            passport = snapshot
            results.clear()
            val error = if (snapshot.getJSONArray("checks").length() == 0) text(R.string.inspection_no_checks) else null
            model.value = model.value.copy(
                equipment = equipmentCard(snapshot), findings = findings(snapshot),
                stage = InspectionStage.AnalysisReady(
                    missingData = snapshot.isNull("telemetry"), error = error,
                ),
            )
        }
    }

    private fun showStep(index: Int) {
        val snapshot = checkNotNull(passport)
        val current = model.value
        val steps = snapshot.getJSONArray("checks")
        val next = steps.optJSONObject(index)
        model.value = current.copy(stage = if (next != null) {
            InspectionStage.InspectNode(
                step(next), index + 1, steps.length(), guidance = next.string("acceptance_criteria"),
            )
        } else {
            val card = checkNotNull(current.equipment)
            InspectionStage.Summary(
                InspectionReport(
                    UUID.randomUUID().toString(), card.equipment, System.currentTimeMillis(),
                    results.toList(), current.findings, card.telemetry,
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
                items.put(JSONObject().put("check_id", result.step.id.toLong()).put("outcome", result.outcome.apiValue))
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
                    api.disconnect()
                    resetInspection()
                    model.value = model.value.copy(account = null, signInError = message)
                } else onError(message)
            } catch (_: IOException) {
                onError(text(R.string.telemetry_server_unreachable))
            } catch (_: JSONException) {
                onError(text(R.string.telemetry_server_invalid_response))
            }
        }
    }

    private fun equipmentRef(value: JSONObject) = EquipmentRef(
        value.getLong("id").toString(), value.getString("name"),
        value.string("serial_number") ?: value.getString("asset_tag"),
    )

    private fun equipmentCard(value: JSONObject) = EquipmentCard(
        equipmentRef(value.getJSONObject("equipment")),
        specifications = equipmentFields(value.getJSONObject("equipment")),
        telemetry = telemetry(value.getJSONArray("sensors")),
        components = value.getJSONArray("components").objects().map {
            ComponentCard(it.getLong("id").toString(), it.getString("name"), equipmentFields(it))
        },
    )

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
            stepResult(step(check), StepOutcome.entries.first { it.apiValue == item.getString("outcome") }, check)
        }
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value.getString("created_at").take(19), ParsePosition(0))?.time ?: 0L
        return InspectionReport(
            value.getLong("id").toString(), equipmentRef(value.getJSONObject("equipment_snapshot")),
            time, results, results.flatMap { it.findings }, telemetry(value.getJSONArray("sensors_snapshot")),
            value.getString("notes"),
        )
    }

    private fun field(@StringRes res: Int, value: String) = InspectionField(text(res), value)
    private fun text(@StringRes res: Int) = strings.getString(res)
}

private fun JSONObject.string(key: String) = if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

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
