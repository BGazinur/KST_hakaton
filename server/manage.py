import argparse
import getpass
import mimetypes
import sqlite3
from pathlib import Path

from .auth import create_user
from .database import connect, db_path, initialize


def main():
    parser = argparse.ArgumentParser(description="Локальная БД AR Engineering")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init", help="Создать базу с демонстрационными данными")
    user = commands.add_parser("create-user", help="Создать локальную учётную запись")
    user.add_argument("email")
    user.add_argument("--name", required=True)
    user.add_argument("--role", choices=("engineer", "supervisor", "admin"), default="engineer")
    document = commands.add_parser("add-document", help="Сохранить копию документа в карточке")
    document.add_argument("equipment_id", type=int)
    document.add_argument("file", type=Path)
    document.add_argument("--kind", choices=(
        "passport", "manual", "operating_map", "test_protocol", "maintenance_regulation"
    ), required=True)
    document.add_argument("--title", required=True)
    document.add_argument("--revision")
    args = parser.parse_args()
    path = db_path()
    initialize(path)

    try:
        if args.command == "create-user":
            password = getpass.getpass("Пароль (12–128 символов): ")
            if password != getpass.getpass("Повторите пароль: "):
                parser.error("Пароли различаются.")
            with connect(path) as db:
                create_user(db, args.email, password, args.name, args.role)
            print("Учётная запись создана.")
        elif args.command == "add-document":
            with connect(path) as db:
                row = db.execute(
                    """
                    INSERT INTO documents (equipment_id, kind, title, revision, filename, content_type, content)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (args.equipment_id, args.kind, args.title, args.revision, args.file.name,
                     mimetypes.guess_type(args.file.name)[0] or "application/octet-stream", args.file.read_bytes()),
                )
                print(f"Документ сохранён в карточке. ID {row.lastrowid}.")
        else:
            print(path)
    except (ValueError, OSError, sqlite3.IntegrityError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
