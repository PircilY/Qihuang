ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY rag-service/requirements.txt /app/requirements.txt
ARG INSTALL_LLM=false
RUN pip install --no-cache-dir \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    -r /app/requirements.txt
RUN if [ "$INSTALL_LLM" = "true" ]; then pip install --no-cache-dir "llama-index-llms-openai-like>=0.3,<0.7"; fi
COPY rag-service /app/rag-service
COPY backend/src/main/resources/data /app/backend/src/main/resources/data

WORKDIR /app/rag-service

ENV RAG_DATA_PATH=/app/backend/src/main/resources/data/knowledge-base.json

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
