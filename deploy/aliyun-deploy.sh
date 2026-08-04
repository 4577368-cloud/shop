#!/bin/bash
# ============================================================
# Tang Source Plugin 后端 — 阿里云 ECS 部署脚本
# 用法：在 ECS 上执行 bash aliyun-deploy.sh
# 前提：Docker 已安装，本目录已有 Dockerfile + pom.xml + src/
# ============================================================
set -e

WORK_DIR="/opt/tang-source-plugin"
cd "$WORK_DIR" || { echo "ERROR: $WORK_DIR 不存在"; exit 1; }

# 检查 .env 文件
if [ ! -f .env ]; then
  echo "ERROR: .env 文件不存在，请先复制 .env.example 为 .env 并填写真实值"
  exit 1
fi

echo "=== 1. 构建 Docker 镜像 ==="
docker build -t tang-source-plugin:latest .

echo ""
echo "=== 2. 停止旧容器（如有）==="
docker rm -f tang-source-plugin 2>/dev/null || true

echo ""
echo "=== 3. 启动新容器 ==="
# 加载 .env 文件中的环境变量
set -a
source .env
set +a

docker run -d \
  --name tang-source-plugin \
  --restart unless-stopped \
  --env-file .env \
  -p 8088:8088 \
  -v /opt/tang-source-plugin/data:/app/data \
  tang-source-plugin:latest

echo ""
echo "=== 4. 等待启动（15秒）==="
sleep 15

echo ""
echo "=== 5. 健康检查 ==="
for i in 1 2 3 4 5; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8088/api/plugin/health || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 健康检查通过 (HTTP 200)"
    echo ""
    echo "=== 部署成功 ==="
    echo "后端地址: http://$(curl -s ifconfig.me):8088"
    echo "健康检查: http://$(curl -s ifconfig.me):8088/api/plugin/health"
    echo ""
    echo "查看日志: docker logs -f tang-source-plugin"
    exit 0
  fi
  echo "  尝试 $i/5: HTTP $HTTP_CODE，等待 10 秒..."
  sleep 10
done

echo "❌ 健康检查失败，查看容器日志："
docker logs --tail 50 tang-source-plugin
exit 1
