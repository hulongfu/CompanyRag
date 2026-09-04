#!/bin/bash
# ================================================
# CompanyRag PostgreSQL 备份/PITR 改动 - Docker 离线验证脚本
# 用途：本地无 Kubernetes 时，用 Docker 运行与 k8s 完全一致的
#       pgvector/pgvector:pg16 镜像，验证本次改动：
#       A) WAL 归档启动参数能否被接受并生效（archive_mode/wal_level/archive_command）
#       B) pg_dump 逻辑备份能否在镜像内跑通（对应 postgres-backup-cronjob）
# 用法：bash k8s/verify-backup-local.sh
# 注意：会临时创建并删除容器 pg-verify，不影响任何工作区文件
# ================================================
set -euo pipefail

IMG="pgvector/pgvector:pg16"
CONTAINER="pg-verify"
PGPASS="changeme-verify-only"

echo "==> [1/5] 清理历史残留容器（若存在）"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "==> [2/5] 启动 postgres，复刻 k8s/postgres.yaml 的启动参数"
# 参数与 postgres.yaml args 一致：
#   -c archive_mode=on
#   -c wal_level=replica
#   -c archive_command=test ! -f /pgarchive/%f && cp %p /pgarchive/%f
# 通过命名卷 pgarchive_tmp 挂载到 /pgarchive，模拟 k8s 中 postgres-archive-pvc
# 对应的独立归档目录；验证结束会删除该卷，不影响工作区任何文件
docker run -d --name "$CONTAINER" \
  -e POSTGRES_DB=company_rag \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD="$PGPASS" \
  -e PGDATA=/var/lib/postgresql/data/pgdata \
  -v pgarchive_tmp:/pgarchive \
  "$IMG" \
  -c archive_mode=on \
  -c wal_level=replica \
  -c "archive_command=test ! -f /pgarchive/%f && cp %p /pgarchive/%f" \
  > /dev/null

echo "==> [3/5] 等待 postgres 就绪（pg_isready）"
for i in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$CONTAINER" pg_isready -U postgres

echo "==> [4/5] 验证启动参数生效"
echo "-- archive_mode:"
docker exec "$CONTAINER" psql -U postgres -Atc "SHOW archive_mode;"
echo "-- wal_level:"
docker exec "$CONTAINER" psql -U postgres -Atc "SHOW wal_level;"
echo "-- archive_command:"
docker exec "$CONTAINER" psql -U postgres -Atc "SHOW archive_command;"

echo "==> [5/5] 验证 pg_dump 可备份（对应 CronJob 逻辑）"
# 注意：Git Bash 默认会把以 / 开头的参数做 MSYS 路径转换（如 /pgarchive 变 D:/programFile/Git/pgarchive），
# 导致写错路径。设置 MSYS_NO_PATHCONV=1 禁止转换，确保写到容器内绝对路径。
MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" pg_dump -U postgres -d company_rag -Fc -f /pgarchive/verify_backup.dump
MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" sh -c 'ls -lh /pgarchive/verify_backup.dump'
echo "备份成功：/pgarchive/verify_backup.dump"

echo
echo "==> 验证结论"
AMS=$(docker exec "$CONTAINER" psql -U postgres -Atc "SHOW archive_mode;")
WAL=$(docker exec "$CONTAINER" psql -U postgres -Atc "SHOW wal_level;")
echo "archive_mode = $AMS  (期望 on)"
echo "wal_level    = $WAL  (期望 replica)"

echo
echo "==> 清理验证容器"
docker rm -f "$CONTAINER" >/dev/null 2>&1
docker volume rm pgarchive_tmp >/dev/null 2>&1 || true
echo "完成。若 archive_mode=on 且 pg_dump 生成文件，则改动在容器层验证通过。"