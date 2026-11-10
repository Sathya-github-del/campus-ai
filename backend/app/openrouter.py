"""Thin async client for OpenRouter (OpenAI-compatible chat completions + live model list)."""
from __future__ import annotations

import httpx

from .config import Settings


class UpstreamError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"{status}: {message}")
        self.status = status
        self.message = message


class OpenRouterClient:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        headers = {"X-Title": settings.app_name}
        if settings.app_url:
            headers["HTTP-Referer"] = settings.app_url
        self.http = httpx.AsyncClient(
            base_url=settings.openrouter_base_url, headers=headers, transport=transport,
            timeout=httpx.Timeout(settings.upstream_timeout_seconds, connect=10))

    async def list_models(self) -> list[dict]:
        r = await self.http.get("/models", timeout=30)
        r.raise_for_status()
        data = r.json().get("data")
        if not isinstance(data, list):
            raise ValueError("unexpected /models response")
        return data

    async def chat(self, payload: dict) -> dict:
        if not self.settings.openrouter_api_key:
            raise UpstreamError(503, "OPENROUTER_API_KEY is not set")
        try:
            r = await self.http.post("/chat/completions", json=payload,
                                     headers={"Authorization": f"Bearer {self.settings.openrouter_api_key}"})
        except httpx.TimeoutException as e:
            raise UpstreamError(504, "model timed out") from e
        except httpx.HTTPError as e:
            raise UpstreamError(502, f"could not reach model gateway: {type(e).__name__}") from e
        try:
            body = r.json()
        except ValueError:
            body = {}
        if r.status_code >= 400 or "error" in body:
            err = body.get("error") if isinstance(body.get("error"), dict) else {}
            status = int(err.get("code") or r.status_code) if str(err.get("code", "")).isdigit() else r.status_code
            raise UpstreamError(status if status >= 400 else 502, str(err.get("message") or r.text[:200]))
        return body

    async def aclose(self) -> None:
        await self.http.aclose()
