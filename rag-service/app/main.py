from __future__ import annotations

from contextlib import asynccontextmanager
import threading
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from app.config import Settings
from app.engine import KnowledgeOutOfSync, LlamaIndexEngine
from app.schemas import (
    ChatRequest,
    ChatResponse,
    EngineInfoResponse,
    HealthResponse,
    KnowledgeImportRequest,
    KnowledgeImportBatchRequest,
    KnowledgeImportResponse,
)


settings = Settings.from_env()
engine = LlamaIndexEngine(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
    engine.reload()
    yield


app = FastAPI(
    title="TCM Cardiovascular LlamaIndex RAG Service",
    version="0.1.0",
    lifespan=lifespan,
)


_import_windows: dict[str, tuple[int, int]] = {}
_import_lock = threading.Lock()

@app.middleware("http")
async def import_rate_limit(request: Request, call_next):
    if request.method == "POST" and request.url.path.startswith("/v1/knowledge/"):
        minute = int(time.monotonic() // 60)
        peer = request.client.host if request.client else "unknown"
        with _import_lock:
            for key in list(_import_windows):
                if _import_windows[key][0] != minute:
                    del _import_windows[key]
            previous = _import_windows.get(peer, (minute, 0))[1]
            if previous >= settings.import_requests_per_minute or len(_import_windows) >= 4096:
                return JSONResponse(status_code=429, content={"code": "IMPORT_RATE_LIMITED"},
                                    headers={"Retry-After": "60"})
            _import_windows[peer] = (minute, previous + 1)
    return await call_next(request)


@app.exception_handler(KnowledgeOutOfSync)
async def knowledge_out_of_sync(_: Request, exception: KnowledgeOutOfSync) -> JSONResponse:
    return JSONResponse(status_code=409, content={
        "code": "KNOWLEDGE_OUT_OF_SYNC",
        "message": "知识库修订号不一致",
        "knowledgeRevision": exception.revision,
        "documents": exception.documents,
    })


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return engine.health()


@app.get("/v1/engine/info", response_model=EngineInfoResponse)
def engine_info() -> EngineInfoResponse:
    return engine.info()


@app.post("/v1/rag/chat", response_model=ChatResponse)
def chat(request: ChatRequest) -> ChatResponse:
    try:
        return engine.query(request)
    except ValueError as exception:
        raise HTTPException(status_code=400, detail=str(exception)) from exception


@app.post("/v1/query", response_model=ChatResponse)
def query(request: ChatRequest) -> ChatResponse:
    return chat(request)


@app.post("/v1/chat", response_model=ChatResponse)
def chat_alias(request: ChatRequest) -> ChatResponse:
    return chat(request)


@app.post("/v1/knowledge/import", response_model=KnowledgeImportResponse)
def import_knowledge(request: KnowledgeImportRequest) -> KnowledgeImportResponse:
    try:
        return engine.ingest(
            request.document.model_dump(by_alias=True),
            [chunk.model_dump(by_alias=True) for chunk in request.chunks],
            request.corpus,
        )
    except (KeyError, TypeError, ValueError) as exception:
        raise HTTPException(status_code=400, detail=str(exception)) from exception


@app.post("/v1/knowledge/import-batch", response_model=KnowledgeImportResponse)
def import_knowledge_batch(request: KnowledgeImportBatchRequest) -> KnowledgeImportResponse:
    try:
        items = [
            (
                item.document.model_dump(by_alias=True),
                [chunk.model_dump(by_alias=True) for chunk in item.chunks],
            )
            for item in request.items
        ]
        return engine.ingest_batch(items, request.corpus)
    except (KeyError, TypeError, ValueError) as exception:
        raise HTTPException(status_code=400, detail=str(exception)) from exception


@app.post("/v1/knowledge/reload", response_model=HealthResponse)
def reload_knowledge() -> HealthResponse:
    engine.reload()
    return engine.health()
