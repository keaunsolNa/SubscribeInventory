# SubscribeInventory — 구독 서비스 대시보드

구독형 AI 서비스(ElevenLabs · xAI · OpenAI · Anthropic)의 **잔여 할당량 · 선불 크레딧 · 이번 달 사용액**을
한 화면에서 보는 대시보드입니다. Spring Boot 백엔드가 각 서비스 API를 대신 호출·정규화하고,
백엔드가 직접 서빙하는 단일 HTML 대시보드가 이를 표시합니다.

- 배포: Google Cloud Run (서울, `asia-northeast3`), Dockerfile 기반
- 키 관리: **BYOK(Bring Your Own Key)** — 사용자 키는 브라우저 localStorage에만 저장되고
  요청별로 중계될 뿐 서버에 저장·로깅되지 않습니다. 서버 환경변수 키는 셀프호스팅용 폴백.

## 아키텍처

```
[브라우저]                         [Spring Boot @ Cloud Run]            [AI 서비스]
대시보드(index.html)  ──POST /api/usage──▶  키 중계 (무저장)  ──▶  ElevenLabs / xAI / OpenAI / Anthropic
키·설정: localStorage      X-Access-Token 검사, 60초 캐시
```

## 서비스별로 얻는 데이터 (실제 키로 검증된 사실)

| 서비스 | 엔드포인트 | 지표 | 의미 |
|---|---|---|---|
| ElevenLabs | `GET /v1/user/subscription` (헤더 `xi-api-key`) | `QUOTA` | 이번 주기 잔여 문자수 (used/limit/remaining/reset) |
| xAI (Grok) | `GET /v1/billing/teams/{teamId}/postpaid/invoice/preview` (Management 키 Bearer) | `BALANCE` | 선불 크레딧: 충전 `coreInvoice.prepaidCredits` − 사용 `prepaidCreditsUsed`. 값은 `{"val":"-500"}` 형태(문자열 USD 센트, 음수) |
| OpenAI | `GET /v1/organization/costs` (**Admin 키**, `api.usage.read` 스코프 필수) | `COST` | 월초부터 누적 지출. 잔여 잔액 API는 공식 미제공 |
| Anthropic | `GET /v1/organizations/cost_report` (헤더 `x-api-key` Admin) | `COST` | 월초부터 누적 지출 (후불제) |

> xAI의 `prepaid/balance` 엔드포인트는 충전 원장만 기록해 사용분이 반영되지 않으므로 쓰지 않습니다.
> OpenAI·Anthropic은 "잔여량" API가 없어 "이번 달 사용액"만 표시합니다.

## 엔드포인트

- `GET /api/health` → `{"status":"UP"}` (항상 개방 — 플랫폼 헬스체크용)
- `GET /api/usage` → 서버 환경변수 키로 조회 (셀프호스팅 모드)
- `POST /api/usage` → BYOK: 본문 `{"keys":{"xai":{"apiKey":"...","teamId":"..."}, ...}}`
  키는 이 요청에서만 사용되고 저장되지 않음
- `GET /` → 대시보드 HTML (`src/main/resources/static/index.html`)

응답(`DashboardResponse`): `generatedAt` + 프로바이더별 `{providerId, metricType(QUOTA|BALANCE|COST),
status(OK|DISABLED|ERROR), used, limit, remaining, usedPercent, cost, currency, resetsAt, monthlyFee, message}`.
한 서비스가 실패해도 나머지는 정상 반환됩니다(ERROR 카드 격리).

## 보안

- **토큰 인증**: 환경변수 `ACCESS_TOKEN` 설정 시 `/api/**`에 `X-Access-Token` 헤더 요구
  (`/api/health` 제외). 미설정이면 개방(로컬용).
- **캐시**: 동일 키 조합 요청은 60초 TTL 캐시로 상류 API 호출을 줄임. 캐시 키는 자격증명의
  SHA-256 지문이라 사용자 간 데이터 격리, 평문 키 미보관. 최대 500엔트리.
- 키는 절대 커밋 금지 — `.gitignore`에 `.env*`, `application-local.*` 방어선 있음.

## 로컬 실행

```bash
# 키 없이도 실행됨(전부 DISABLED 카드) — 대시보드 키 패널(BYOK)로 조회 가능
mvn spring-boot:run          # http://localhost:8080

# 셀프호스팅 모드: 환경변수로 서버에 키 주입 (필요한 것만)
ELEVENLABS_API_KEY=... XAI_MANAGEMENT_KEY=... XAI_TEAM_ID=... \
OPENAI_ADMIN_KEY=... ANTHROPIC_ADMIN_KEY=... mvn spring-boot:run
```

월 고정 구독료는 `application.yml`의 `dashboard.providers.<id>.monthly-fee`(원화)로 설정하면
카드에 자동 표시됩니다 (대시보드에서 수동 입력도 가능).

## Slack 알림 (다중 사용자 구독)

각 사용자가 대시보드의 "🔔 Slack 알림 구독" 패널에서 자신의 Webhook URL·임계값을 등록하면,
Cloud Scheduler가 매시 정각 `POST /api/alerts/check`를 호출해 구독별로 점검·발송합니다.

- `POST /api/alerts/subscriptions` — 구독 등록: `{webhookUrl, keys, thresholds}`.
  **키+웹훅 전체를 AES-256-GCM으로 암호화해 Firestore에 저장** (옵트인, 평문 미보관).
  Webhook은 `https://hooks.slack.com/` 프리픽스만 허용(SSRF 차단).
- `DELETE /api/alerts/subscriptions/{id}` — 해지 즉시 삭제
- 임계값 기본: 사용량 80%, 잔액 $5, 월 사용액 한도는 선택. 동일 경고 상태는 재발송하지 않음
  (지문을 Firestore에 저장하므로 재시작에도 유지)
- 활성 조건: `ENCRYPTION_KEY`(base64 32바이트, Secret Manager)와 `GCP_PROJECT_ID` 설정.
  로컬 등 미설정 환경에서는 구독 API가 503으로 응답

## 배포 (Cloud Run)

```bash
gcloud run deploy subscribe-inventory \
  --source . \
  --region asia-northeast3 \
  --allow-unauthenticated \
  --set-env-vars "ACCESS_TOKEN=<비밀 토큰>"
```

`--source .`는 Cloud Build가 Dockerfile로 빌드하므로 로컬 Docker가 필요 없습니다.
`server.port=${PORT:8080}`로 플랫폼 포트에 자동 대응합니다.
공개 배포에는 서버 측 프로바이더 키를 넣지 않습니다(순수 BYOK).

## 테스트

```bash
mvn test    # 27개: 프로바이더 파싱(MockWebServer) · 캐시 · 인증 필터 · 집계 · 컨트롤러
```

각 프로바이더의 파싱은 `provider/*Provider.java`의 `parse(JsonNode)` 한 곳에 격리되어 있어,
서비스가 응답 필드를 바꾸면 그 메서드와 대응 테스트만 수정하면 됩니다.

## TODO

- Slack 알림 다중 사용자화: 사용자별 구독·임계값·웹훅 저장 (현재는 배포 소유자 단일 구독)
- GCP 비용 카드 (보류): GCP는 지출 조회 API가 없어 사용자별 BigQuery 내보내기 + 서비스 계정이
  필요 — 고급 사용자 옵션으로만 검토
- 실서비스 다듬기: 커스텀 도메인, min-instances(콜드스타트 제거), CORS 축소
