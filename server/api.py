import asyncio
import json
from contextlib import asynccontextmanager, suppress
from datetime import date
from itertools import cycle
from pathlib import Path
from typing import Annotated, Literal
from urllib.parse import quote

from email_validator import EmailNotValidError
from fastapi import FastAPI, File, Form, HTTPException, Query, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, SecretStr

from .admin import require_equipment, router as admin_router
from .auth import SESSION_SECONDS, authenticate, normalize_email, token_hash
from .database import DEMO_PUMP, connect, current_telemetry, db_path, initialize, set_current, utc_now
from .http_auth import Credentials, User, unauthorized
from .passport import decode, get_passport, rows, sensor_status
from .report_exports import EXPORT_TYPES, make_pdf, make_xlsx
from .vision_review import VisionBusy, VisionFrame, VisionReview, VisionUnavailable, inspect_frame


def replay_ids(path):
    with connect(path) as db:
        return [
            row["id"] for row in db.execute(
                """
                SELECT id FROM telemetry_samples
                WHERE equipment_id = ? AND source = 'replay' ORDER BY measured_at
                """,
                (DEMO_PUMP,),
            )
        ]


def update_sample(path, sample_id):
    with connect(path) as db:
        db.execute("BEGIN IMMEDIATE")
        current = current_telemetry(db, DEMO_PUMP)
        if current is None or current["source"] == "replay":
            set_current(db, DEMO_PUMP, sample_id)


async def replay(path, sample_ids):
    for sample_id in cycle(sample_ids):
        await asyncio.to_thread(update_sample, path, sample_id)
        await asyncio.sleep(1)


@asynccontextmanager
async def lifespan(app):
    path = db_path()
    await asyncio.to_thread(initialize, path)
    app.state.db_path = path
    sample_ids = await asyncio.to_thread(replay_ids, path)
    if not sample_ids:
        raise RuntimeError("No replay samples for equipment 1")
    task = asyncio.create_task(replay(path, sample_ids))
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="AR Engineering", lifespan=lifespan)
app.include_router(admin_router)
web = Path(__file__).resolve().parent / "web"
app.mount("/panel/assets", StaticFiles(directory=web), name="panel-assets")


@app.get("/", include_in_schema=False)
def home():
    return RedirectResponse("/panel")


@app.get("/panel", include_in_schema=False)
def panel():
    return FileResponse(web / "index.html", headers={
        "Content-Security-Policy": "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer",
    })


class Login(BaseModel):
    email: str = Field(max_length=254)
    password: SecretStr = Field(min_length=1, max_length=128)


class CheckResult(BaseModel):
    check_id: int
    outcome: Literal[
        "no_visible_issue", "needs_check", "possible_defect", "confirmed_defect", "inconclusive", "skipped"
    ]
    observation: str = Field(default="", max_length=4000)


class ReportInput(BaseModel):
    equipment_id: int
    notes: str = Field(default="", max_length=4000)
    items: list[CheckResult] = Field(min_length=1, max_length=100)
    telemetry_sample_id: int | None = None
    client_request_id: str | None = Field(default=None, min_length=1, max_length=64)


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, error: RequestValidationError):
    return JSONResponse(
        status_code=422,
        content={"detail": [
            {"loc": item["loc"], "msg": item["msg"], "type": item["type"]}
            for item in error.errors()
        ]},
    )


@app.get("/health")
def health(request: Request):
    with connect(request.app.state.db_path) as db:
        db.execute("SELECT 1 FROM equipment LIMIT 1").fetchone()
    return {"status": "ok"}


@app.post("/auth/login")
def login(data: Login, request: Request, response: Response):
    try:
        email = normalize_email(data.email)
    except EmailNotValidError:
        raise unauthorized() from None
    with connect(request.app.state.db_path) as db:
        token = authenticate(db, email, data.password.get_secret_value())
    if token is None:
        raise unauthorized()
    response.headers["Cache-Control"] = "no-store"
    return {"access_token": token, "token_type": "bearer", "expires_in": SESSION_SECONDS}


@app.get("/auth/me")
def me(user: User):
    return user


@app.post("/auth/logout")
def logout(request: Request, credentials: Credentials, user: User):
    with connect(request.app.state.db_path) as db:
        db.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash(credentials.credentials),))
    return Response(status_code=204)


@app.get("/equipment/resolve")
def resolve_equipment(
    kind: Literal["qr", "nameplate"],
    value: Annotated[str, Query(min_length=1, max_length=254)],
    request: Request,
    user: User,
):
    with connect(request.app.state.db_path) as db:
        row = db.execute(
            "SELECT equipment_id FROM equipment_identifiers WHERE kind = ? AND value = ?",
            (kind, value.strip()),
        ).fetchone()
    if row is None:
        raise HTTPException(404, "Оборудование с таким идентификатором не найдено.")
    return dict(row)


@app.get("/equipment")
def list_equipment(request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        return rows(
            db,
            "SELECT id, name, serial_number, asset_tag FROM equipment WHERE parent_id IS NULL ORDER BY id",
            (),
        )


@app.get("/equipment/{equipment_id}/passport")
def passport(equipment_id: int, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN")
        result = get_passport(db, equipment_id)
    if result is None:
        raise HTTPException(404, "Оборудование не найдено.")
    return result


@app.get("/equipment/{equipment_id}/samples")
def sample_history(
    equipment_id: int, request: Request, user: User,
    limit: Annotated[int, Query(ge=1, le=100)] = 25,
):
    with connect(request.app.state.db_path) as db:
        require_equipment(db, equipment_id)
        return rows(
            db,
            """
            SELECT * FROM telemetry_samples WHERE equipment_id = ? AND source = 'live'
            ORDER BY measured_at DESC, id DESC LIMIT ?
            """,
            (equipment_id, limit),
        )


@app.get("/documents/{document_id}")
def document(document_id: int, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        row = db.execute("SELECT filename, content_type, content FROM documents WHERE id = ?", (document_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "Документ не найден.")
    return download(row["content"], row["filename"], row["content_type"])


@app.post("/equipment/{equipment_id}/checks/{check_id}/vision-review", response_model=VisionReview)
def vision_review(equipment_id: int, check_id: int, frame: VisionFrame, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        check = db.execute(
            "SELECT title, acceptance_criteria FROM inspection_checks WHERE id = ? AND equipment_id = ?",
            (check_id, equipment_id),
        ).fetchone()
    if check is None:
        raise HTTPException(404, "В паспорте оборудования нет такого шага.")
    try:
        return inspect_frame(frame, check)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    except VisionBusy as error:
        raise HTTPException(429, "Локальная модель проверяет другой кадр. Попробуйте ещё раз.") from error
    except VisionUnavailable as error:
        raise HTTPException(503, "Локальная модель недоступна. Можно продолжить осмотр вручную.") from error


def download(content, filename, content_type):
    return Response(content, media_type=content_type, headers={
        "Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename, safe='')}",
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "private, no-store",
    })


def read_upload(file, limit, signatures):
    content = file.file.read(limit + 1)
    if len(content) > limit:
        raise HTTPException(413, f"Файл больше {limit // (1024 * 1024)} МБ.")
    for signature, content_type in signatures:
        if content.startswith(signature):
            filename = (file.filename or "document").replace("\\", "/").rsplit("/", 1)[-1].strip()
            return filename[:255] or "document", content_type, content
    raise HTTPException(422, "Формат файла не поддерживается.")


@app.post("/equipment/{equipment_id}/documents", status_code=201)
def add_document(
    equipment_id: int,
    request: Request,
    user: User,
    kind: Annotated[Literal["passport", "manual", "operating_map", "maintenance_regulation"], Form()],
    title: Annotated[str, Form(min_length=1, max_length=254)],
    file: Annotated[UploadFile, File()],
    revision: Annotated[str | None, Form(max_length=100)] = None,
):
    if user["role"] != "admin":
        raise HTTPException(403, "Добавлять документы может администратор.")
    if not title.strip():
        raise HTTPException(422, "Укажите название документа.")
    upload = read_upload(file, 10 * 1024 * 1024, ((b"%PDF-", "application/pdf"),))
    with connect(request.app.state.db_path) as db:
        require_equipment(db, equipment_id)
        document_id = db.execute(
            """
            INSERT INTO documents (equipment_id, kind, title, revision, filename, content_type, content)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (equipment_id, kind, title.strip(), (revision or "").strip() or None, *upload),
        ).lastrowid
    return {"id": document_id}


@app.post("/equipment/{equipment_id}/verifications", status_code=201)
def add_verification(
    equipment_id: int,
    request: Request,
    user: User,
    sensor_code: Annotated[str, Form(min_length=1, max_length=100)],
    title: Annotated[str, Form(min_length=1, max_length=254)],
    performed_at: Annotated[date, Form()],
    outcome: Annotated[Literal["pass", "fail", "inconclusive"], Form()],
    valid_until: Annotated[date | None, Form()] = None,
    certificate_number: Annotated[str | None, Form(max_length=100)] = None,
    details: Annotated[str, Form(max_length=4000)] = "",
    protocol: Annotated[UploadFile | None, File()] = None,
):
    if user["role"] not in ("supervisor", "admin"):
        raise HTTPException(403, "Загружать поверки могут мастер и администратор.")
    if not title.strip():
        raise HTTPException(422, "Укажите название поверки.")
    if valid_until is not None and (valid_until < performed_at or outcome != "pass"):
        raise HTTPException(422, "Срок действия указывается для успешной поверки и не может быть раньше даты поверки.")
    upload = read_upload(protocol, 10 * 1024 * 1024, ((b"%PDF-", "application/pdf"),)) if protocol else None
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT 1 FROM equipment WHERE id = ?", (equipment_id,)).fetchone() is None:
            raise HTTPException(404, "Оборудование не найдено.")
        if db.execute(
            "SELECT 1 FROM sensors WHERE equipment_id = ? AND code = ?", (equipment_id, sensor_code)
        ).fetchone() is None:
            raise HTTPException(422, "Датчик не относится к этому оборудованию.")

        document_id = None
        if upload:
            document_id = db.execute(
                """
                INSERT INTO documents (equipment_id, kind, title, filename, content_type, content)
                VALUES (?, 'test_protocol', ?, ?, ?, ?)
                """,
                (equipment_id, title.strip(), *upload),
            ).lastrowid
        verification_id = db.execute(
            """
            INSERT INTO equipment_tests (
                equipment_id, sensor_code, kind, title, performed_at, valid_until, outcome,
                details, protocol_document_id, certificate_number, uploaded_by
            ) VALUES (?, ?, 'metrological_verification', ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                equipment_id, sensor_code, title.strip(), performed_at.isoformat(),
                valid_until.isoformat() if valid_until else None, outcome, details,
                document_id, (certificate_number or "").strip() or None, user["id"],
            ),
        ).lastrowid
        return dict(db.execute("SELECT * FROM equipment_tests WHERE id = ?", (verification_id,)).fetchone())


@app.get("/equipment/{equipment_id}/verifications")
def verifications(equipment_id: int, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        if db.execute("SELECT 1 FROM equipment WHERE id = ?", (equipment_id,)).fetchone() is None:
            raise HTTPException(404, "Оборудование не найдено.")
        return rows(
            db,
            """
            SELECT * FROM equipment_tests
            WHERE equipment_id = ? AND kind = 'metrological_verification'
            ORDER BY performed_at DESC, id DESC
            """,
            (equipment_id,),
        )


@app.get("/defect-types")
def defect_types(request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        return rows(db, "SELECT * FROM defect_types ORDER BY id", ())


def encode(value):
    return json.dumps(value, ensure_ascii=False)


@app.post("/reports", status_code=201)
def create_report(data: ReportInput, request: Request, user: User):
    if len({item.check_id for item in data.items}) != len(data.items):
        raise HTTPException(422, "Каждый шаг должен присутствовать в отчёте один раз.")

    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN IMMEDIATE")
        if data.client_request_id is not None:
            existing = db.execute(
                "SELECT id FROM reports WHERE author_id = ? AND client_request_id = ?",
                (user["id"], data.client_request_id),
            ).fetchone()
            if existing is not None:
                return {"id": existing["id"]}
        # A report keeps the inspected sample, even if replay advances before save.
        snapshot = get_passport(db, data.equipment_id)
        if snapshot is None:
            raise HTTPException(404, "Оборудование не найдено.")
        checks = {check["id"]: check for check in snapshot["checks"]}
        if any(item.check_id not in checks for item in data.items):
            raise HTTPException(422, "Один из шагов не относится к этому оборудованию.")

        telemetry = snapshot["telemetry"]
        if data.telemetry_sample_id is not None:
            sample = db.execute(
                "SELECT * FROM telemetry_samples WHERE equipment_id = ? AND id = ?",
                (data.equipment_id, data.telemetry_sample_id),
            ).fetchone()
            if sample is None:
                raise HTTPException(422, "Замер не относится к этому оборудованию.")
            telemetry = dict(sample)

        sensors = [sensor_status(sensor, telemetry) for sensor in snapshot["sensors"]]

        report_id = db.execute(
            """
            INSERT INTO reports (
                equipment_id, author_id, created_at, notes, equipment_snapshot, telemetry_snapshot, sensors_snapshot,
                client_request_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (data.equipment_id, user["id"], utc_now(), data.notes,
             encode(snapshot["equipment"]), encode(telemetry) if telemetry is not None else None, encode(sensors),
             data.client_request_id),
        ).lastrowid
        db.executemany(
            """
            INSERT INTO report_items (report_id, equipment_id, check_id, check_snapshot, outcome, observation)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [
                (report_id, data.equipment_id, item.check_id, encode(checks[item.check_id]),
                 item.outcome, item.observation)
                for item in data.items
            ],
        )
    return {"id": report_id}


@app.get("/equipment/{equipment_id}/reports")
def report_history(
    equipment_id: int,
    request: Request,
    user: User,
    limit: Annotated[int | None, Query(ge=1, le=100)] = None,
):
    with connect(request.app.state.db_path) as db:
        return rows(
            db,
            """
            SELECT id, equipment_id, author_id, created_at, notes FROM reports
            WHERE equipment_id = ? AND (? OR author_id = ?) ORDER BY created_at DESC, id DESC LIMIT ?
            """,
            (equipment_id, user["role"] != "engineer", user["id"], limit or -1),
        )


@app.get("/reports/{report_id}")
def report(report_id: int, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN")
        return read_report(db, report_id, user)


def report_row(db, report_id, user):
    row = db.execute(
        """
        SELECT r.*, u.display_name AS author_name
        FROM reports r JOIN users u ON u.id = r.author_id
        WHERE r.id = ? AND (? OR r.author_id = ?)
        """,
        (report_id, user["role"] != "engineer", user["id"]),
    ).fetchone()
    if row is None:
        raise HTTPException(404, "Отчёт не найден.")
    return row


def read_report(db, report_id, user):
    result = decode(report_row(db, report_id, user), "equipment_snapshot", "telemetry_snapshot", "sensors_snapshot")
    result["items"] = [
        decode(item, "check_snapshot")
        for item in db.execute(
            """
            SELECT * FROM report_items WHERE report_id = ?
            ORDER BY json_extract(check_snapshot, '$.position'), check_id
            """,
            (report_id,),
        )
    ]
    result["attachments"] = rows(
        db, "SELECT id, kind, check_id, filename, content_type FROM report_attachments WHERE report_id = ? ORDER BY id",
        (report_id,),
    )
    return result


@app.post("/reports/{report_id}/photos", status_code=201)
def add_report_photo(
    report_id: int,
    request: Request,
    user: User,
    photo: Annotated[UploadFile, File()],
    check_id: Annotated[int | None, Form(gt=0)] = None,
):
    with connect(request.app.state.db_path) as db:
        report_row(db, report_id, user)
        upload = read_upload(
            photo, 5 * 1024 * 1024, ((b"\xff\xd8\xff", "image/jpeg"), (b"\x89PNG\r\n\x1a\n", "image/png"))
        )
        if check_id is not None and db.execute(
            "SELECT 1 FROM report_items WHERE report_id = ? AND check_id = ?", (report_id, check_id)
        ).fetchone() is None:
            raise HTTPException(422, "Этого шага нет в отчёте.")
        attachment_id = db.execute(
            """
            INSERT INTO report_attachments (report_id, check_id, kind, filename, content_type, content)
            VALUES (?, ?, 'photo', ?, ?, ?)
            """,
            (report_id, check_id, *upload),
        ).lastrowid
        return {"id": attachment_id}


@app.get("/reports/{report_id}/attachments/{attachment_id}")
def report_attachment(report_id: int, attachment_id: int, request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        report_row(db, report_id, user)
        row = db.execute(
            "SELECT filename, content_type, content FROM report_attachments WHERE report_id = ? AND id = ?",
            (report_id, attachment_id),
        ).fetchone()
    if row is None:
        raise HTTPException(404, "Вложение не найдено.")
    return download(row["content"], row["filename"], row["content_type"])


@app.get("/reports/{report_id}/export/{file_format}")
def export_report(report_id: int, file_format: Literal["pdf", "xlsx"], request: Request, user: User):
    with connect(request.app.state.db_path) as db:
        db.execute("BEGIN")
        result = read_report(db, report_id, user)
    content = make_pdf(result) if file_format == "pdf" else make_xlsx(result)
    return download(content, f"inspection-{report_id}.{file_format}", EXPORT_TYPES[file_format])
