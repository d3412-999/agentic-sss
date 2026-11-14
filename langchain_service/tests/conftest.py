"""测试环境变量：必须在导入 app 之前设置。

测试不访问真实大模型 / 不加载真实 ASR 模型：
- LLM_API_KEY 置空，用于验证未配置时返回 503（不做任何兜底）；
- ASR_MODEL_DIR 指向不存在的目录，需要识别器的用例通过测试替身注入；
- 人脸检测使用真实 OpenCV（requirements.txt 已包含）。
"""

import os

os.environ.setdefault("INTERNAL_TOKEN", "test-token")
os.environ.setdefault("LIGHTRAG_ENABLED", "false")
os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("TEMPLATE_DB_PATH", "./.pytest-data/template_library.db")
os.environ["LLM_API_KEY"] = ""
os.environ["ASR_MODEL_DIR"] = "./.pytest-missing-model"