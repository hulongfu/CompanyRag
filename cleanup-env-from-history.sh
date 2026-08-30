#!/bin/bash
# Git 历史密钥泄露清理脚本
# 使用 git filter-branch 从所有分支历史中删除 .env 文件

set -e

echo "=== Git 历史密钥泄露清理脚本 ==="
echo ""

# 1. 检查当前分支
echo "步骤 1: 检查当前分支"
git branch -a
echo ""

# 2. 确认 .env 在哪些提交中
echo "步骤 2: 确认 .env 在哪些提交中"
git log --all --oneline -- .env
echo ""

# 3. 备份当前仓库（重要！）
echo "步骤 3: 创建备份分支"
git branch backup-before-env-cleanup
echo "✅ 已创建备份分支：backup-before-env-cleanup"
echo "⚠️  如果清理失败，可以运行：git reset --hard backup-before-env-cleanup 恢复"
echo ""

# 4. 使用 git filter-branch 删除 .env 文件
echo "步骤 4: 从所有分支历史中删除 .env 文件..."
echo "这可能需要几分钟..."

git filter-branch --force --index-filter \
  "git rm --cached --ignore-unmatch .env" \
  --prune-empty --tag-name-filter cat -- --all

echo "✅ filter-branch 完成"
echo ""

# 5. 清理 refs 和原始对象
echo "步骤 5: 清理旧的 refs 和备份对象"
rm -rf .git/refs/original/
git reflog expire --expire=now --all
git gc --prune=now --aggressive
echo "✅ 清理完成"
echo ""

# 6. 验证 .env 是否已从历史中删除
echo "步骤 6: 验证 .env 是否已从历史中删除"
if git log --all --oneline -- .env | grep -q .; then
  echo "❌ 失败：.env 仍然存在于历史中"
  git log --all --oneline -- .env
  exit 1
else
  echo "✅ 成功：.env 已从所有历史提交中删除"
fi
echo ""

# 7. 验证无法再访问旧的 .env 内容
echo "步骤 7: 验证无法再访问旧的 .env 内容"
if git show b89d855:.env 2>/dev/null; then
  echo "❌ 失败：仍然可以访问旧的 .env 内容"
  exit 1
else
  echo "✅ 成功：无法再访问旧的 .env 内容"
fi
echo ""

echo "=== 本地仓库清理完成 ==="
echo ""
echo "下一步操作："
echo "1. 检查本地仓库是否正常：git status"
echo "2. 强制推送到 GitHub: git push --force --all origin"
echo "3. 强制推送到 Gitee:  git push --force --all gitee"
echo ""
echo "⚠️  警告：强制推送会重写远程历史，确保所有协作者已知晓！"
echo "⚠️  推送后，所有 clone 过旧仓库的人都应该重新 clone！"
