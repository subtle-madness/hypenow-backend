# 헤드리스 브라우저(Chromium) 내장 베이스 — 별도 브라우저 설치 불필요.
# playwright npm 패키지 버전(package.json)과 반드시 동일 태그를 쓴다(버전 불일치 시
# 드라이버-브라우저 프로토콜 미스매치로 launch가 실패할 수 있다).
FROM mcr.microsoft.com/playwright:v1.62.1-noble

WORKDIR /app

# 락파일이 있으면 npm ci(재현 가능한 설치), 없으면 npm install로 폴백.
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev || npm install --omit=dev

COPY refresh-docid.js ./

# one-shot — restart 정책 없음(compose profile tools, `run --rm`로만 실행).
CMD ["node", "refresh-docid.js"]
