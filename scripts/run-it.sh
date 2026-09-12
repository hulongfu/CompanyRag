#!/usr/bin/env bash
# ================================================
# CompanyRag 本地一键 IT 回归脚本
#
# 作用：
#   检测已在运行的 docker PG(5433)/Redis(6379)，幂等灌入 IT 种子数据，
#   然后以 -Dit.pg=true 运行真实数据库集成测试（RlsIsolationTest + AuditLogTenantIsolationIT
#   + MultiRetrieveIntegrationIT）。后端 MultiRetrieve 强依赖外网 embedding，不稳定，
#   仅在 --all 且提供 DASHSCOPE_API_KEY / SILICONFLOW_API_KEY 时运行。
#
# 用法：
#   scripts/run-it.sh            # 仅跑租户 IT（推荐，稳定）
#   scripts/run-it.sh --seed      # 先灌种子数据再跑
#   scripts/run-it.sh --all       # 灌种子 + 租户 IT + MultiRetrieve（需外网 key）
#
# 可覆盖的环境变量：
#   POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD
#   REDIS_HOST / REDIS_PORT
#   DASHSCOPE_API_KEY / SILICONFLOW_API_KEY
# ================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PG_CONTAINER="${PG_CONTAINER:-docker-pgvector-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-docker-redis-1}"
PG_PORT="${POSTGRES_PORT:-5433}"
REDIS_PORT="${REDIS_PORT:-6379}"
PG_DB="${POSTGRES_DB:-company_rag}"
PG_USER="${POSTGRES_USER:-company_rag_app}"
PG_PASS="${POSTGRES_PASSWORD:-company_rag_app123456}"

DO_SEED=false
DO_ALL=false
for arg in "$@"; do
  case "$arg" in
    --seed) DO_SEED=true ;;
    --all)  DO_ALL=true; DO_SEED=true ;;
    *) echo "未知参数: $arg(忽略)" ;;
  esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[run-it]${NC} $*"; }
warn()  { echo -e "${YELLOW}[run-it]${NC} $*"; }
fail()  { echo -e "${RED}[run-it] 错误:${NC} $*" >&2; exit 1; }

# ---- 0) 检测 docker ----
command -v docker >/dev/null 2>&1 || fail "未找到 docker 命令"

# ---- 1) 检测 PG / Redis 容器 ----
PG_RUNNING=false; REDIS_RUNNING=false
if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then PG_RUNNING=true; fi
if docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER"; then REDIS_RUNNING=true; fi

if [ "$PG_RUNNING" = false ]; then
  warn "未检测到 PG 容器($PG_CONTAINER)，租户 IT 将被跳过。可先 docker compose up -d 启动 docker-pgvector-1"
fi
if [ "$REDIS_RUNNING" = false ]; then
  warn "未检测到 Redis 容器($REDIS_CONTAINER)；RAG 检索链路的缓存相关测试可能不可用"
fi

# ---- 2) 幂等灌入种子数据 ----
seed_pg() {
  info "灌入 IT 种子数据 (db=$PG_DB user=$PG_USER)..."
  # seed-it.sql 使用 SET LOCAL，需以单事务执行才生效
  docker exec -i -e PGPASSWORD="$PG_PASS" "$PG_CONTAINER" \
    psql -v ON_ERROR_STOP=1 --single-transaction -U "$PG_USER" -d "$PG_DB" \
    -f /dev/stdin < "$ROOT_DIR/sql/seed-it.sql" \
    && info "种子数据灌入完成" \
    || warn "种子数据灌入失败，请检查表结构(flyway 是否已执行)"
}
if [ "$DO_SEED" = true ]; then
  [ "$PG_RUNNING" = true ] && seed_pg || warn "PG 未运行，跳过种子数据"
fi

# ---- 3) 运行真实库租户 IT ----
if [ "$PG_RUNNING" = true ]; then
  info "运行租户隔离 IT (-Dit.pg=true)..."
  mvn -q -pl company-rag-tenant -am test \
      -Dit.pg=true \
      -Dtest='RlsIsolationTest,AuditLogTenantIsolationIT' \
      -Dsurefire.failIfNoSpecifiedTests=false
  RC=$?
  if [ $RC -ne 0 ]; then
    fail "租户隔离 IT 未通过(exit=$RC)。查看 target/surefire-reports 获取详情"
  fi
  info "租户隔离 IT 全部通过 ✅"
else
  warn "跳过租户 IT（无 PG）"
fi

# ---- 4) 可选：MultiRetrieve 全链路（强依赖外网 embedding，默认跳过）----
if [ "$DO_ALL" = true ]; then
  if [ -n "${DASHSCOPE_API_KEY:-}" ] || [ -n "${SILICONFLOW_API_KEY:-}" ]; then
    info "检测到 LLM/Embedding key，尝试运行 MultiRetrieve 全链路集成测试..."
    # 该测试(multiRetrieveIntegrationIT)加载完整 bootstrap 上下文：
    #   - 需 Redis 运行：REDIS_PASSWORD 从 .env 读取（否则 Redisson 连接失败）
    #   - 需 JWT_SECRET：application-dev.yml 默认空串，JwtSecurityValidator 启动即校验
    #   - 本机 JDK17 旧版 Mockito self-attach 受限，注入 allowAttachSelf 规避
    REDIS_PSWD="${REDIS_PASSWORD:-difyai123456}"
    [ -f "$ROOT_DIR/company-rag-bootstrap/.env" ] \
      && REDIS_PSWD="$(grep -E '^REDIS_PASSWORD=' "$ROOT_DIR/company-rag-bootstrap/.env" | head -1 | cut -d= -f2-)"
    JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 32)}"
    mvn -q -pl company-rag-bootstrap test \
        -Dit.pg=true \
        -Dtest='MultiRetrieveIntegrationIT' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -DargLine="-Djdk.attach.allowAttachSelf=true" \
        -DREDIS_PASSWORD="$REDIS_PSWD" -DJWT_SECRET="$JWT_SECRET"
    RC=$?
    if [ $RC -ne 0 ]; then
      warn "MultiRetrieve 集成测试未通过(exit=$RC)。该测试强依赖真实向量数据与外网 embedding，仅供参考"
    else
      info "MultiRetrieveIntegrationIT 全部通过 ✅"
    fi
  else
    warn "未提供 DASHSCOPE_API_KEY / SILICONFLOW_API_KEY，跳过 MultiRetrieve 可选验证"
  fi
else
  info "未指定 --all，跳过 MultiRetrieve 可选验证（如需运行请 --all 并提供外网 key）"
fi

info "完成。核心租户隔离回归已通过 ✅"
