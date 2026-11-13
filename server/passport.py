import json
from datetime import date

from .database import current_telemetry


def rows(db, sql, params):
    return [dict(row) for row in db.execute(sql, params)]


def decode(row, *fields):
    result = dict(row)
    for name in fields:
        if result[name] is not None:
            result[name] = json.loads(result[name])
    return result


def maintenance_status(plan, run_hours, today):
    days = (date.fromisoformat(plan["next_due_on"]) - today).days if plan["next_due_on"] else None
    hours = (
        plan["next_due_hours"] - run_hours
        if plan["next_due_hours"] is not None and run_hours is not None else None
    )
    if any(remaining is not None and remaining <= 0 for remaining in (days, hours)):
        status = "due"
    elif (days is not None and days <= plan["warning_days"]) or (
        hours is not None and hours <= plan["warning_hours"]
    ):
        status = "due_soon"
    elif days is None and hours is None:
        status = "unknown"
    else:
        status = "scheduled"
    message = None
    if plan["kind"] == "service":
        message = {"due": "Наступил срок планового ТО", "due_soon": "Приближается плановое ТО"}.get(status)
    elif plan["kind"] == "metrological_verification":
        message = {"due": "Наступил срок поверки", "due_soon": "Приближается плановая поверка"}.get(status)
    return {
        **plan, "status": status, "message": message, "remaining_days": days, "remaining_hours": hours,
    }


def sensor_status(sensor, telemetry):
    reading = telemetry.get(sensor["code"]) if telemetry else None
    warning = sensor["warning_above"]
    alarm = sensor["alarm_above"]
    status = "unknown"
    if reading is not None and (warning is not None or alarm is not None):
        if alarm is not None and reading > alarm:
            status = "alarm"
        elif warning is not None and reading > warning:
            status = "warning"
        else:
            status = "normal"
    return {**sensor, "reading": reading, "status": status}


def get_passport(db, equipment_id):
    key = (equipment_id,)
    equipment = db.execute("SELECT * FROM equipment WHERE id = ?", key).fetchone()
    if equipment is None:
        return None

    state = db.execute("SELECT * FROM operating_state WHERE equipment_id = ?", key).fetchone()
    run_hours = state["run_hours"] if state else None
    today = date.today()
    plans = rows(db, "SELECT * FROM maintenance_plans WHERE equipment_id = ? ORDER BY id", key)
    telemetry = current_telemetry(db, equipment_id)
    sensors = rows(db, "SELECT * FROM sensors WHERE equipment_id = ? ORDER BY code", key)

    lines = rows(
        db,
        """
        SELECT l.* FROM equipment_lines l JOIN line_equipment m ON m.line_id = l.id
        WHERE m.equipment_id = ? ORDER BY l.id
        """,
        key,
    )
    for line in lines:
        line["equipment"] = rows(
            db,
            """
            SELECT m.position, e.id, e.asset_tag, e.name, e.kind
            FROM line_equipment m JOIN equipment e ON e.id = m.equipment_id
            WHERE m.line_id = ? ORDER BY m.position
            """,
            (line["id"],),
        )

    return {
        "equipment": decode(equipment, "specifications"),
        "identifiers": rows(db, "SELECT kind, value FROM equipment_identifiers WHERE equipment_id = ? ORDER BY kind, value", key),
        "components": [
            decode(row, "specifications")
            for row in db.execute("SELECT * FROM equipment WHERE parent_id = ? ORDER BY id", key)
        ],
        "lines": lines,
        "documents": rows(
            db,
            """
            SELECT id, kind, title, revision, filename, content_type
            FROM documents WHERE equipment_id = ? ORDER BY id
            """,
            key,
        ),
        "operating_modes": [
            decode(row, "parameters")
            for row in db.execute("SELECT * FROM operating_modes WHERE equipment_id = ? ORDER BY id", key)
        ],
        "operating_state": dict(state) if state else None,
        "sensors": [sensor_status(sensor, telemetry) for sensor in sensors],
        "telemetry": telemetry,
        "checks": rows(db, "SELECT * FROM inspection_checks WHERE equipment_id = ? ORDER BY position", key),
        "maintenance": [maintenance_status(plan, run_hours, today) for plan in plans],
        "maintenance_history": rows(
            db, "SELECT * FROM maintenance_events WHERE equipment_id = ? ORDER BY performed_at DESC, id DESC", key
        ),
        "tests": rows(db, "SELECT * FROM equipment_tests WHERE equipment_id = ? ORDER BY performed_at DESC, id DESC", key),
    }
