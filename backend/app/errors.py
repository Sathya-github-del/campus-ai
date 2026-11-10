"""One error shape for every non-2xx response:  {"error": "<code>", "message": "<human text>", ...extra}"""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class APIError(Exception):
    def __init__(self, status: int, code: str, message: str, headers: dict | None = None, **extra):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers
        self.extra = extra

    def body(self) -> dict:
        return {"error": self.code, "message": self.message, **self.extra}


def install_handlers(app: FastAPI) -> None:
    @app.exception_handler(APIError)
    async def _api_error(_: Request, e: APIError):
        return JSONResponse(e.body(), status_code=e.status, headers=e.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, e: RequestValidationError):
        first = e.errors()[0] if e.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", ()) if p != "body")
        msg = f"{where}: {first.get('msg', 'invalid value')}" if where else first.get("msg", "invalid request")
        return JSONResponse({"error": "invalid_request", "message": msg}, status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, e: StarletteHTTPException):
        code = {404: "not_found", 405: "method_not_allowed"}.get(e.status_code, "http_error")
        return JSONResponse({"error": code, "message": str(e.detail)}, status_code=e.status_code,
                            headers=getattr(e, "headers", None))
