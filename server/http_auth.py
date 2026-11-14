from typing import Annotated

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .auth import session_user
from .database import connect

Credentials = Annotated[HTTPAuthorizationCredentials | None, Depends(HTTPBearer(auto_error=False))]


def unauthorized():
    return HTTPException(401, "Неверные данные входа или сессия истекла.", headers={"WWW-Authenticate": "Bearer"})


def authenticated_user(request: Request, credentials: Credentials):
    if credentials:
        with connect(request.app.state.db_path) as db:
            user = session_user(db, credentials.credentials)
        if user is not None:
            return user
    raise unauthorized()


User = Annotated[dict, Depends(authenticated_user)]
