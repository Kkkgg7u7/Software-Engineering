# Frozen dependency installation and type-checked production build.
FROM node:24-alpine AS build

WORKDIR /app

# node:20-alpine 自带 corepack，用它启用 pnpm（版本以 package.json/lockfile 为准）
RUN npm install --global pnpm@11.25.0

# 构建期可注入的前端环境变量（Vite 只暴露 VITE_ 前缀的变量）
#   VITE_API_BASE   REST 基础路径，同源反代时保持 /api
#   VITE_WS_URL     WebSocket 地址，留空表示按当前页面 host 自动推导（推荐）
#   VITE_USE_SOCKJS 代理环境不支持原生 WebSocket 时设为 true
ARG VITE_API_BASE=/api
ARG VITE_WS_URL=
ARG VITE_USE_SOCKJS=false
ENV VITE_API_BASE=$VITE_API_BASE
ENV VITE_WS_URL=$VITE_WS_URL
ENV VITE_USE_SOCKJS=$VITE_USE_SOCKJS

# 只拷贝构建真正需要的文件（等价于 .dockerignore 的效果，避免把宿主机的
# node_modules / dist 复制进镜像 —— pnpm 的 node_modules 是符号链接结构，
# 跨平台复制容易损坏）。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY tsconfig.json vite.config.ts env.d.ts ./
COPY public ./public
COPY src ./src
# Vite 的入口 HTML（构建必需，dist/index.html 就是由它生成的）
COPY index.html ./index.html

RUN pnpm install --frozen-lockfile

RUN pnpm run build

# ----------------------------------------------------------- 运行阶段 --
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="werewolf-frontend" \
      org.opencontainers.image.description="基于 AI 增强的多人在线狼人杀系统 - 前端（Nginx 静态托管 + API/WS 反代）" \
      org.opencontainers.image.version="1.0.0"

ENV TZ=Asia/Shanghai

# 站点配置：SPA history 路由 + /api 与 /ws 反代到 backend:8080
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 构建产物（nginx 默认站点根目录）
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx:alpine 自带 busybox wget，可以直接做健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
