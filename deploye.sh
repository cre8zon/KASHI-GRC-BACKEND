#!/bin/bash

set -e

echo "🚀 Starting Deployment..."

# 1. Go to project directory
cd /Users/paritoshkushwaha/Desktop/KASHI-GRC-LATEST-APP/KASHI-GRC-BACKEND

echo "📦 Building JAR..."
mvn clean package -DskipTests

echo "📤 Copying JAR to server..."
scp target/grc-0.0.1-SNAPSHOT.jar root@64.227.182.108:/home/

echo "🔁 Restarting Spring Boot on server..."
ssh root@64.227.182.108 << 'EOF'

sudo systemctl daemon-reload
sudo systemctl restart springboot
sudo systemctl status springboot --no-pager

EOF

echo "🌐 Reloading Nginx..."
ssh root@64.227.182.108 "sudo nginx -t && sudo systemctl reload nginx"

echo "✅ Deployment Successful!"