"""测试环境变量：必须在导入 app 之前设置。"""

import os

os.environ.setdefault("LLM_PROVIDER", "mock")
os.environ.setdefault("INTERNAL_TOKEN", "test-token")
os.environ.setdefault("LIGHTRAG_ENABLED", "false")
os.environ.setdefault("ENVIRONMENT", "test")
