# 화면 이미지: 1단계에서 화면을 빌드하고, 2단계에서 Caddy 웹서버가 빌드 결과를 서빙한다.
# Caddy는 /api 요청을 백엔드로 넘기고, HTTPS 인증서를 자동으로 발급·갱신한다 (설정: deploy/Caddyfile).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /app/dist /srv
