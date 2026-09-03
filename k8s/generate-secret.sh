#!/usr/bin/env bash
#
# 从 .env 读取真实密钥，动态生成 K8s Secret 清单。
# 真实密钥永不写入提交到仓库的 k8s/secret.yaml，而是生成到 .gitignore 忽略的临时文件。
#
# 用法：
#   bash k8s/generate-secret.sh          # 生成 k8s/secret.generated.yaml
#   kubectl apply -f k8s/secret.generated.yaml
#
set -euo pipefail

# 项目根目录（脚本位于 k8s/ 子目录）
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${BASE_DIR}/.env"
OUT_FILE="${BASE_DIR}/k8s/secret.generated.yaml"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "未找到 ${ENV_FILE}，请先从 .env.example 复制并填写真实密钥。" >&2
  exit 1
fi

# 只加载 .env，不覆盖已有的同名 shell 变量
set -a
# shellcheck source=/dev/null
source "${ENV_FILE}"
set +a

# 必填密钥：缺失则中止
REQUIRED_KEYS=(DASHSCOPE_API_KEY SILICONFLOW_API_KEY JWT_SECRET POSTGRES_PASSWORD)
for key in "${REQUIRED_KEYS[@]}"; do
  if [[ -z "${!key:-}" ]]; then
    echo "缺少必填密钥 ${key}（.env 未配置该值）。" >&2
    exit 1
  fi
done

# postgres 超级用户密码：生产强烈建议设置，未设置则回退到 POSTGRES_PASSWORD 并告警
SUPERUSER_PW="${POSTGRES_SUPERUSER_PASSWORD:-${POSTGRES_PASSWORD}}"
if [[ -z "${POSTGRES_SUPERUSER_PASSWORD:-}" ]]; then
  echo "⚠️  .env 未设置 POSTGRES_SUPERUSER_PASSWORD，回退为 POSTGRES_PASSWORD；生产请显式配置。" >&2
fi

# base64 编码辅助函数（兼容 mac 的 base64）
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

cat > "${OUT_FILE}" <<YAML
apiVersion: v1
kind: Secret
metadata:
  name: company-rag-secret
  namespace: default
# 本文件由 k8s/generate-secret.sh 自动生成，勿手工编辑、勿提交到版本库（已在 .gitignore）
type: Opaque
data:
  DASHSCOPE_API_KEY: "$(b64 "${DASHSCOPE_API_KEY}")"
  SILICONFLOW_API_KEY: "$(b64 "${SILICONFLOW_API_KEY}")"
  JWT_SECRET: "$(b64 "${JWT_SECRET}")"
  POSTGRES_PASSWORD: "$(b64 "${POSTGRES_PASSWORD}")"
  POSTGRES_SUPERUSER_PASSWORD: "$(b64 "${SUPERUSER_PW}")"
YAML

echo "已生成 ${OUT_FILE}（含敏感信息，请勿提交到 git）"
echo "下一步："
echo "  kubectl apply -f k8s/secret.generated.yaml"
echo "校验应用用户密码是否与 initdb-configmap.yaml 中 CREATE USER 的密码一致，避免连接失败。"