from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.config import Settings
from app.engine import LlamaIndexEngine
from app.schemas import (
    ChatRequest,
    ChatResponse,
    EngineInfoResponse,
    HealthResponse,
    KnowledgeImportRequest,
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


@app.post("/v1/knowledge/import", response_model=KnowledgeImportResponse)
def import_knowledge(request: KnowledgeImportRequest) -> KnowledgeImportResponse:
    return engine.ingest(
        request.document.model_dump(by_alias=True),
        [chunk.model_dump(by_alias=True) for chunk in request.chunks],
    )


@app.post("/v1/knowledge/reload", response_model=HealthResponse)
def reload_knowledge() -> HealthResponse:
    engine.reload()
    return engine.health()
