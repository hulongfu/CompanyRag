# Python 依赖精简报告

**日期**: 2026-08-30  
**来源**: `D:/uv_project/mcp-server-docker/requirements.txt`  
**目标**: `./agent_skills/requirements.txt`

---

## 统计对比

| 项目 | 原始文件 | 精简后 | 减少比例 |
|------|---------|--------|----------|
| 依赖包数量 | 314 个 | 77 个 | **75.5%** |
| 文件大小 | ~80KB | ~3.5KB | **95.7%** |
| 预计安装时间 | 10-30 分钟 | 2-5 分钟 | **80%+** |
| 预计镜像体积增加 | 1-2GB | 200-400MB | **80%+** |

---

## 剔除的依赖类别

### 1. **AI/ML 框架和大型库**（约 80 个包）
**剔除原因**: CompanyRag 使用 Spring AI + 通义千问，不需要本地 ML 框架

- `torch`, `torchvision` - PyTorch 深度学习框架（体积巨大）
- `transformers`, `tokenizers`, `sentence-transformers` - HuggingFace 模型
- `accelerate`, `safetensors` - 模型加速和序列化
- `timm`, `effdet`, `pycocotools` - 计算机视觉模型
- `scikit-learn`, `scipy`, `joblib`, `threadpoolctl` - 机器学习库
- `numba`, `llvmlite` - JIT 编译器
- `onnx`, `onnxruntime` - 模型推理引擎
- `ml-dtypes`, `numpy` (保留 1.26.4 精简版)

### 2. **向量数据库客户端**（约 10 个包）
**剔除原因**: CompanyRag 使用 PGVector，不需要其他向量数据库

- `chromadb` - Chroma 向量数据库
- `pinecone`, `pinecone-client`, `pinecone-plugin-*` - Pinecone 客户端
- `pypika` - SQL 查询构建器（Chroma 依赖）

### 3. **Web UI 框架**（约 15 个包）
**剔除原因**: CompanyRag 使用 Spring Boot 提供 Web 界面

- `gradio`, `gradio-client` - Web UI 框架
- `flask`, `werkzeug`, `blinker`, `itsdangerous` - Flask 框架
- `fastapi` (保留给 MCP 使用), `typer`, `httptools`, `uvloop`, `watchfiles`

### 4. **LangChain 生态系统**（约 25 个包）
**剔除原因**: CompanyRag 使用 Spring AI，不需要 LangChain

- `langchain`, `langchain-core`, `langchain-community`
- `langchain-anthropic`, `langchain-openai`, `langchain-google-genai`
- `langchain-pinecone`, `langchain-ollama`, `langchain-huggingface`
- `langgraph`, `langgraph-checkpoint`, `langgraph-prebuilt`
- `langgraph-supervisor`, `langgraph-swarm`, `langgraph-sdk`
- `langserve`, `langsmith`, `langchain-text-splitters`
- `langchain-experimental`, `langchain-classic`

### 5. **特定 LLM 提供商 SDK**（部分保留）
**剔除原因**: CompanyRag 使用通义千问（DashScope）

**剔除**:
- `anthropic`, `openai`, `ollama` - 其他 LLM 客户端
- `google-genai`, `google-api-core`, `google-auth`, `google-cloud-vision`
- `grpcio`, `grpcio-status`, `proto-plus`, `protobuf` - Google RPC

**保留**:
- `dashscope` - 通义千问 SDK（必需）

### 6. **开发和测试工具**（约 20 个包）
**剔除原因**: 生产环境不需要开发工具

- `pytest`, `pytest-cov`, `coverage` - 测试框架
- `pre-commit`, `nodeenv`, `identify` - 代码检查工具
- `virtualenv`, `distlib`, `platformdirs` - 虚拟环境工具
- `build`, `pyproject-hooks`, `setuptools` - 打包工具
- `pip`, `wheel` 相关工具

### 7. **Windows 特定依赖**（约 5 个包）
**剔除原因**: Docker 容器运行在 Linux 上

- `pywin32`, `win32-setctime`, `pyreadline3` - Windows API
- `comtypes`, `wxauto` - Windows 自动化

### 8. **GPU 相关依赖**（约 15 个包）
**剔除原因**: Docker 镜像基于 CPU，不需要 GPU 支持

- `nvidia-cublas-cu12`, `nvidia-cuda-*`, `nvidia-cudnn-cu12`
- `nvidia-cufft-cu12`, `nvidia-curand-cu12`, `nvidia-cusolver-cu12`
- `nvidia-cusparse-cu12`, `nvidia-nccl-cu12`, `nvidia-nvtx-cu12`
- `triton` - GPU 编译器

### 9. **其他大型或不相关依赖**（约 40 个包）
**剔除原因**: 与技能执行无直接关系

- `geopandas`, `pyogrio`, `shapely`, `geojson` - 地理信息系统
- `kubernetes`, `durationpy`, `isodate` - K8s 客户端
- `playwright`, `pyee` - 浏览器自动化
- `msoffcrypto-tool`, `olefile`, `pyperclip` - Office 加密
- `pymupdf`, `pdf2image`, `pi-heif`, `pikepdf` - PDF 处理（保留 pymupdf）
- `nltk`, `langdetect`, `python-iso639` - NLP 工具
- `matplotlib`, `seaborn`, `contourpy`, `cycler`, `fonttools` - 绘图库
- `opencv-python` - 计算机视觉
- `mesa`, `networkx` - 复杂网络分析
- `deepagents`, `instructor`, `cyclopts` - Agent 框架

---

## 保留的依赖类别

### 1. **HTTP 和网络库**（12 个包）
**保留原因**: 技能执行需要 HTTP 请求能力

- `aiohttp`, `httpx`, `requests` - HTTP 客户端
- `urllib3`, `certifi`, `httpcore` - HTTP 底层库
- `aiohappyeyeballs`, `aiosignal`, `frozenlist` - 异步支持
- `multidict`, `yarl`, `propcache` - URL 和字典处理

### 2. **MCP 相关**（7 个包）
**保留原因**: CompanyRag Agent 需要 MCP 协议支持

- `mcp`, `fastmcp` - MCP 核心库
- `dashscope` - 通义千问 SDK
- `sse-starlette`, `starlette` - SSE 服务器
- `uvicorn`, `httpx-sse` - ASGI 服务器和 SSE 客户端

### 3. **数据验证和配置**（7 个包）
**保留原因**: 技能参数验证和配置管理

- `pydantic`, `pydantic-core`, `pydantic-settings` - 数据验证
- `python-dotenv` - 环境变量管理
- `annotated-types`, `typing-extensions`, `typing-inspection` - 类型提示

### 4. **JSON 和数据格式**（6 个包）
**保留原因**: 数据序列化和验证

- `orjson` - 快速 JSON 库
- `pyyaml` - YAML 解析
- `jsonschema`, `jsonschema-specifications` - JSON Schema 验证
- `referencing`, `rpds-py` - JSON Schema 引用处理

### 5. **文档解析和处理**（13 个包）
**保留原因**: 补充 Tika，支持更多文档格式

- `beautifulsoup4`, `soupsieve`, `lxml` - HTML/XML 解析
- `pypdf`, `pdfminer-six`, `pymupdf` - PDF 处理
- `python-docx`, `python-pptx` - Office 文档
- `openpyxl`, `xlrd`, `xlsxwriter` - Excel 处理
- `filetype`, `python-magic` - 文件类型检测

### 6. **数据处理和分析**（5 个包）
**保留原因**: 数据分析类技能可能需要

- `pandas`, `numpy` - 数据处理
- `python-dateutil`, `pytz`, `tzdata` - 日期时区处理

### 7. **日志和工具**（8 个包）
**保留原因**: 技能日志记录和调试

- `loguru` - 日志库
- `tenacity`, `backoff` - 重试机制
- `rich`, `pygments` - 终端美化
- `markdown`, `markdown-it-py`, `mdurl` - Markdown 处理

### 8. **文本处理**（6 个包）
**保留原因**: 文本技能需要

- `emoji` - Emoji 处理
- `tabulate` - 表格格式化
- `rapidfuzz`, `rank-bm25` - 模糊匹配和搜索
- `tiktoken`, `regex` - Token 化和正则表达式

### 9. **加密和安全**（3 个包）
**保留原因**: API 认证和数据加密

- `cryptography`, `cffi`, `pycparser` - 加密库

### 10. **异步和并发**（3 个包）
**保留原因**: 异步技能执行

- `anyio`, `sniffio`, `exceptiongroup` - 异步支持

### 11. **其他实用工具**（8 个包）
**保留原因**: 通用工具库

- `click` - 命令行工具
- `colorama`, `humanfriendly`, `coloredlogs` - 彩色日志
- `psutil` - 系统监控
- `distro` - 操作系统检测
- `pillow` - 图像处理

---

## Docker 适用性分析

### ✅ 适合 Docker 的理由

1. **无平台特定依赖**: 已剔除所有 Windows 特定包
2. **无 GPU 依赖**: 已剔除所有 NVIDIA CUDA 相关包
3. **体积可控**: 从 314 个包减少到 77 个，预计安装后 200-400MB
4. **安装时间短**: 预计 2-5 分钟完成安装
5. **功能完整**: 保留了所有可能需要的功能类别
6. **版本锁定**: 所有包都指定了确切版本号，确保可重复构建

### ⚠️ 需要注意的事项

1. **系统依赖**: 某些包可能需要系统级依赖
   - `lxml`: 需要 `libxml2`, `libxslt`
   - `python-magic`: 需要 `libmagic`
   - `cryptography`: 需要 `openssl`
   - `pillow`: 可能需要图像库

2. **Dockerfile 建议修改**:
   ```dockerfile
   # 安装系统依赖
   RUN apt-get update && apt-get install -y \
       libxml2-dev \
       libxslt1-dev \
       libmagic1 \
       libssl-dev \
       libjpeg-dev \
       zlib1g-dev \
       && rm -rf /var/lib/apt/lists/*
   ```

3. **Python 版本**: 确保 Docker 中 Python 版本 >= 3.10（推荐 3.11+）

---

## 未来扩展建议

### 可能需要的额外依赖（按需添加）

1. **如果需要网页爬取**:
   ```text
   playwright==1.58.0
   selenium==4.x
   ```

2. **如果需要更多 LLM 支持**:
   ```text
   openai==2.8.1
   anthropic==0.77.1
   ollama==0.6.1
   ```

3. **如果需要本地 AI 模型**:
   ```text
   sentence-transformers==5.1.2
   transformers==4.57.3
   ```

4. **如果需要地理信息**:
   ```text
   geopandas==1.1.1
   shapely==2.1.2
   ```

---

## 结论

✅ **精简后的 `requirements.txt` 非常适合 Docker 部署！**

**优势**:
- 依赖数量减少 75.5%，大幅降低镜像体积和构建时间
- 保留了所有核心功能和扩展性
- 剔除了平台特定和不相关的依赖
- 支持未来技能扩展

**建议**:
1. 在 Dockerfile 中添加系统依赖安装步骤
2. 使用多阶段构建进一步优化镜像体积
3. 定期审查和更新依赖版本
4. 根据实际技能使用情况继续优化依赖列表
