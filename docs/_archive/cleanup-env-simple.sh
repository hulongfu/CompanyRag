#!/bin/bash
# 简化的 Git 历史清理脚本 - 只处理 main 分支
set -e

echo "=== 简化版 Git 历史清理脚本 ==="
echo ""

# 1. 切换到 main 分支
echo "步骤 1: 切换到 main 分支"
git checkout main
echo ""

# 2. 创建备份
echo "步骤 2: 创建备份分支"
git branch -f backup-main-before-cleanup
echo "✅ 备份分支：backup-main-before-cleanup"
echo ""

# 3. 使用 filter-branch 只重写 main 分支
echo "步骤 3: 从 main 分支历史中删除 .env"
echo "这可能需要几分钟..."

export FILTER_BRANCH_SQUELCH_WARNING=1

git filter-branch -f --index-filter \
  "git rm --cached --ignore-unmatch .env" \
  --prune-empty HEAD $(git rev-list --all --oneline | cut -d' ' -f1 | head -1)

echo "✅ filter-branch 完成"
echo ""

# 4. 清理
echo "步骤 4: 清理备份对象"
rm -rf .git/refs/original/
git reflog expire --expire=now --all
git gc --prune=now --aggressive
echo "✅ 清理完成"
echo ""

# 5. 验证
echo "步骤 5: 验证 .env 是否已删除"
if git log --all --oneline -- .env | grep -q .; then
  echo "❌ 失败：.env 仍在历史中"
  exit 1
else
  echo "✅ 成功：.env 已从历史中删除"
fi
echo ""

echo "=== 本地清理完成 ==="
echo ""
echo "下一步："
echo "1. 切换到 feature/openclaw-skill-engine 分支"
echo "2. rebase 到新的 main: git rebase main"
echo "3. 强制推送到远程：git push --force-with-lease origin main feature/openclaw-skill-engine"
echo "4. 同样推送到 gitee: git push --force-with-lease gitee main feature/openclaw-skill-engine"
