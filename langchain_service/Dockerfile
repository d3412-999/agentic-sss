# AI 服务镜像（FastAPI + mock provider，离线可跑）
# 构建：docker build -t sss-ai ./langchain_service

FROM python:3.12-slim
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

# 先装依赖（利用层缓存）。国内可加：--build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_INDEX_URL=https://pypi.org/simple
COPY requirements.txt requirements-optional.txt ./
RUN pip install -i "$PIP_INDEX_URL" -r requirements.txt

# 再拷代码
COPY main.py ./
COPY app ./app

# 运行期配置：容器内必须监听 0.0.0.0；environment=prod 可关掉热重载
ENV AI_HOST=0.0.0.0 \
    AI_PORT=8000 \
    ENVIRONMENT=prod \
    LLM_PROVIDER=mock \
    ASR_PROVIDER=mock \
    FACE_PROVIDER=mock \
    TEMPLATE_DB_PATH=/app/data/template_library.db

RUN mkdir -p /app/data
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status==200 else 1)"

CMD ["python", "main.py"]
