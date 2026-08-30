#!/bin/bash
# GitHub 推送重试脚本
# 每隔 10 分钟尝试推送，最多重试 6 次（1 小时）

REMOTE="origin"
BRANCHES="main feature/openclaw-skill-engine feature/hermes-agent-poc"
MAX_RETRIES=6
INTERVAL=600  # 10 分钟

echo "=== GitHub 推送重试脚本 ==="
echo "远程仓库：$REMOTE"
echo "推送分支：$BRANCHES"
echo "最大重试次数：$MAX_RETRIES"
echo "重试间隔：${INTERVAL}秒"
echo ""

for i in $(seq 1 $MAX_RETRIES); do
    echo "========== 第 $i 次尝试 (共 $MAX_RETRIES 次) =========="
    
    SUCCESS=true
    for branch in $BRANCHES; do
        echo "推送分支：$branch ..."
        if git push --force $REMOTE $branch 2>&1; then
            echo "✅ $branch 推送成功"
        else
            echo "❌ $branch 推送失败"
            SUCCESS=false
        fi
    done
    
    if [ "$SUCCESS" = true ]; then
        echo ""
        echo "🎉 所有分支推送成功！"
        exit 0
    fi
    
    if [ $i -lt $MAX_RETRIES ]; then
        echo ""
        echo "等待 ${INTERVAL}秒后重试..."
        sleep $INTERVAL
    fi
done

echo ""
echo "⚠️  $MAX_RETRIES 次尝试后仍有分支推送失败"
echo "请检查网络连接或手动推送"
exit 1
