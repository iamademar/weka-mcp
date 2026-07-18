# WEKA MCP server image. Runs the HTTP transport so other containers can reach it.
# (Local stdio development is usually run from the host with `npm run dev`.)
FROM node:22-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# Small ARFF fixture for the deployed smoke test (weka_upload_dataset reads a path
# local to THIS container). Test-only asset; harmless in production.
COPY fixtures ./fixtures

EXPOSE 3000

# dist/http.js reads MCP_HTTP_PORT (default 3000) and WEKA_API_URL.
CMD ["node", "dist/http.js"]
