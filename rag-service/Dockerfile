ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY rag-service/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY rag-service /app/rag-service
COPY backend/src/main/resources/data /app/backend/src/main/resources/data

WORKDIR /app/rag-service

ENV RAG_DATA_PATH=/app/backend/src/main/resources/data/knowledge-base.json

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
