# Build stage: compile the React app to static files.
FROM docker.io/library/node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
# Files copied from public/ (the photos) keep the mode they have on the build machine (a 640 photo is
# unreadable for the non-root nginx user and gives 403), so make everything in dist readable.
RUN chmod -R a+rX dist
# Build with --build-arg WITH_PHOTOS=no when the photos come from S3: the image then has no photos folder (56 MB smaller).
ARG WITH_PHOTOS=yes
RUN if [ "$WITH_PHOTOS" != "yes" ]; then rm -rf dist/photos; fi

# Runtime stage: nginx serves the static files as a non-root user on port 8080.
FROM docker.io/nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# The file keeps the mode it has on the build machine (a 640 file is unreadable for the non-root nginx user),
# so set it explicitly. This works with every builder; COPY --chmod needs BuildKit.
USER root
RUN chmod 644 /etc/nginx/conf.d/default.conf
USER 101
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD wget -q --spider http://localhost:8080/healthz || exit 1
