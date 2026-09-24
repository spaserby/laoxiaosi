#!/usr/bin/env bash
# ============================================================
# 服务器发布脚本（在服务器上执行）
#   1) git 拉取最新代码（拿不到远端时告警并用当前提交继续）
#   2) 数据库迁移（幂等：两个迁移 + init.sql 全量 upsert）
#   3) 重建镜像并重启容器
#   4) 验收探针（6 项全 PASS 才算发布成功）
#
# 用法：bash deploy/deploy.sh            # 完整发布
#       bash deploy/deploy.sh --no-db    # 只更新代码与镜像（跳过数据库）
#       bash deploy/deploy.sh --no-pull  # 跳过拉取（用工作区当前提交发布）
#
# 前提：GitHub Deploy Key 已加到仓库（见 runbook「发布」一节）；
#       未配置时本脚本会告警并退回"用当前提交发布"。
# ⚠ 严禁在本目录执行 git clean -xfd：会删掉未跟踪的 deploy/.env（真实密钥）。
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="deploy/docker-compose.external.yml"
DO_DB=1
DO_PULL=1
for arg in "$@"; do
  case "$arg" in
    --no-db) DO_DB=0 ;;
    --no-pull) DO_PULL=0 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

cd "$REPO_ROOT"

echo "===== 1/4 拉取代码 ====="
if [ "$DO_PULL" = "1" ]; then
  if git fetch origin --quiet 2>/dev/null; then
    git merge --ff-only origin/main
    echo "已更新到 $(git rev-parse --short HEAD)  $(git log -1 --pretty=%s)"
  else
    echo "!! 无法访问远端 origin（GitHub Deploy Key 未配置或网络不通）"
    echo "!! 本次用工作区当前提交继续：$(git rev-parse --short HEAD) $(git log -1 --pretty=%s)"
  fi
else
  echo "跳过（--no-pull）：$(git rev-parse --short HEAD)"
fi

if [ "$DO_DB" = "1" ]; then
  echo "===== 2/4 数据库迁移（幂等）====="
  # 口令经 --env-file 注入（psql 认 PGPASSWORD，故把 PG_PASSWORD 映射一份），不进命令行
  TMPENV=$(mktemp)
  trap 'rm -f "$TMPENV"' EXIT
  grep -E '^PG_PASSWORD=' deploy/.env | head -1 | sed 's/^PG_PASSWORD=/PGPASSWORD=/' | tr -d '\r' > "$TMPENV"
  run_sql() {
    # client_min_messages=warning：幂等脚本会打一堆 "already exists, skipping" NOTICE，压掉噪音
    docker run --rm -i -e PGOPTIONS='-c client_min_messages=warning' \
      --env-file deploy/.env --env-file "$TMPENV" postgres:17-alpine \
      sh -c 'psql -h "$PG_HOST" -p "${PG_PORT:-5432}" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q -f -'
  }
  for f in scripts/migration-01-doc-type-and-dedupe.sql scripts/migration-02-session-owner.sql db/init.sql; do
    printf '  %-48s' "$f"
    run_sql < "$f" > /dev/null
    echo "OK"
  done
else
  echo "===== 2/4 数据库迁移 跳过（--no-db）====="
fi

echo "===== 3/4 重建镜像并重启 ====="
docker compose -f "$COMPOSE_FILE" up -d --build
docker compose -f "$COMPOSE_FILE" ps --format '  {{.Service}}  {{.Status}}'

echo "===== 4/4 验收探针 ====="
bash deploy/probes.sh

echo
echo "发布完成：$(git rev-parse --short HEAD) $(git log -1 --pretty=%s)"
