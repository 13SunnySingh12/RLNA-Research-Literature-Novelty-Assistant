# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /build

COPY Frontend/package.json Frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY Frontend/ ./

# Vite inlines these at build time, so they are build arguments rather than
# runtime environment. Only VITE_* values reach the browser, and none of them
# are secret: the API base URL and the public auth endpoint.
ARG VITE_API_BASE_URL
ARG VITE_NEON_AUTH_BASE_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_NEON_AUTH_BASE_URL=$VITE_NEON_AUTH_BASE_URL

RUN node ./node_modules/vite/bin/vite.js build


FROM nginx:1.27-alpine AS runtime
COPY Infrastructure/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY Infrastructure/docker/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
