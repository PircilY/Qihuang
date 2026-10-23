ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
ARG PIP_INDEX_URL=https://pypi.org/simple
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY rag-service/requirements-lock.txt /app/requirements-lock.txt
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r /app/requirements-lock.txt \
    && groupadd --gid 10001 rag && useradd --uid 10001 --gid rag --create-home rag \
    && mkdir -p /app/runtime && chown rag:rag /app/runtime
COPY rag-service/app /app/rag-service/app
COPY safety-rules.json /app/rag-service/safety-rules.json
COPY backend/src/main/resources/data/knowledge-base.json /app/backend/src/main/resources/data/knowledge-base.json
COPY backend/src/main/resources/data/expanded/knowledge-base.json /app/backend/src/main/resources/data/expanded/knowledge-base.json
WORKDIR /app/rag-service
ENV RAG_DATA_PATH=/app/backend/src/main/resources/data/knowledge-base.json \
    SAFETY_RULES_PATH=/app/rag-service/safety-rules.json EMBEDDING_CACHE_PATH=/app/runtime/embedding-cache
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
