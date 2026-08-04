#!/bin/bash
# ============================================================
# Tang Source Plugin — 代码更新脚本（在 ECS 上执行）
# 用法：bash aliyun-update.sh
# 功能：拉取最新代码 → 重新构建 → 滚动重启
# ============================================================
set -e

WORK_DIR="/opt/tang-source-plugin"
cd "$WORK_DIR" || { echo "ERROR: $WORK_DIR 不存在"; exit 1; }

echo "=== 1. 重新构建 Docker 镜像 ==="
docker build -t tang-source-plugin:latest .

echo ""
echo "=== 2. 滚动重启容器 ==="
docker rm -f tang-source-plugin 2>/dev/null || true

docker run -d \
  --name tang-source-plugin \
  --restart unless-stopped \
  --env-file .env \
  -p 8088:8088 \
  -v /opt/tang-source-plugin/data:/app/data \
  tang-source-plugin:latest

echo ""
echo "=== 3. 等待启动（15秒）==="
sleep 15

echo ""
echo "=== 4. 健康检查 ==="
for i in 1 2 3 4 5; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8088/api/plugin/health || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 更新成功 (HTTP 200)"
    docker logs --tail 5 tang-source-plugin
    exit 0
  fi
  echo "  尝试 $i/5: HTTP $HTTP_CODE，等待 10 秒..."
  sleep 10
done

echo "❌ 启动失败，查看日志："
docker logs --tail 80 tang-source-plugin
exit 1
