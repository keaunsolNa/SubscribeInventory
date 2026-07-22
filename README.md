# SubscribeInventory — AI 구독 서비스 잔액 대시보드

[![Deploy](https://github.com/keaunsolNa/SubscribeInventory/actions/workflows/deploy.yml/badge.svg)](https://github.com/keaunsolNa/SubscribeInventory/actions/workflows/deploy.yml)

**8개 AI 서비스의 잔여 크레딧 · 할당량 · 이번 달 사용액을 한 화면에서.**
키는 브라우저에만 저장되고(BYOK), 잔액이 임계값 아래로 내려가면 Slack으로 알려줍니다.

**▶ 바로 사용하기: https://subscribeinventory.web.app** (무료, Google 로그인)

![SubscribeInventory](src/main/resources/static/og.png)

## 특징

- **8개 서비스 통합 조회** — ElevenLabs · xAI(Grok) · OpenAI · Anthropic(Claude) · DeepSeek ·
  OpenRouter · Stability AI · fal.ai (전부 실키로 응답 스키마 검증)
- **BYOK (Bring Your Own Key)** — 키는 브라우저 localStorage에만 저장. 서버는 요청별로 중계만
  하고 저장·로깅하지 않습니다. 코드가 공개되어 있으니 직접 확인하세요.
- **추이 스파크라인 + 소진 예측** — 시간별 히스토리를 쌓아 최근 7일 추이와
  "이 속도면 N일 후 소진"을 카드에 표시
- **Slack 잔여량 알림** — 웹훅+임계값 구독 시 매시 자동 점검. 구독 정보는 AES-256-GCM 암호화
  저장(키 지문 태깅으로 무중단 키 회전 지원)
- **서비스별 잔액 확인 가이드** — 각 서비스의 콘솔·API 확인법과 키 스코프 함정 정리:
  [guides](https://subscribeinventory.web.app/guides/)

## 아키텍처

```
[브라우저]                    [Firebase Hosting]        [Spring Boot @ Cloud Run (서울)]
대시보드(정적 HTML)  ──────▶  전 트래픽 rewrite  ──────▶  키 중계(무저장) · 60초 캐시 · JWT 인증
키·설정: localStorage                                      │
                                                          ├─▶ 8개 AI 서비스 API
[Cloud Scheduler] ── 매시 알림 스윕 / 주간 리포트 ─────────┤
[Cloud Billing → Pub/Sub] ── 예산 알림 ────────────────────┤
                                                          └─▶ Firestore (암호화 구독·히스토리)
[GitHub Actions] ── main 푸시 → 테스트 → WIF 키리스 배포
```

## 서비스별로 얻는 데이터 (실키 검증 완료)

| 서비스 | 엔드포인트 | 지표 | 비고 |
|---|---|---|---|
| ElevenLabs | `GET /v1/user/subscription` (`xi-api-key`) | 잔여 글자 수 | 일반 키 가능 |
| xAI (Grok) | `GET /v1/billing/teams/{teamId}/postpaid/invoice/preview` (Management 키) | 선불 잔액 | `prepaidCredits − prepaidCreditsUsed`, 센트 문자열·음수. `prepaid/balance`는 콘솔과 불일치라 미사용 |
| OpenAI | `GET /v1/organization/costs` (**Admin 키**, `api.usage.read`) | 월 사용액 | 잔액 API 미제공 |
| Anthropic | `GET /v1/organizations/cost_report` (**조직 Admin 키**) | 월 사용액 | 개인 계정은 조직 구성 선행 |
| DeepSeek | `GET /user/balance` (Bearer) | 선불 잔액 | 일반 키 가능 |
| OpenRouter | `GET /api/v1/credits` (Bearer) | 크레딧 | `total_credits − total_usage` |
| Stability AI | `GET /v1/user/balance` (Bearer) | 크레딧 | 일반 키 가능 |
| fal.ai | `GET /v1/account/billing?expand=credits` (`Key` 스킴) | 크레딧 | **ADMIN 스코프 키 전용** (기본 키는 403) |

> Gemini·Groq·Perplexity·Mistral 등은 사용량/잔액 조회 공개 API가 없어 추가 불가 (2026-07 기준).
> 각 함정의 상세 설명은 [서비스별 가이드](https://subscribeinventory.web.app/guides/)에 있습니다.

## API

- `GET /api/health` — 헬스체크 (항상 개방)
- `POST /api/usage` — BYOK 조회: `{"keys":{"xai":{"apiKey":"...","teamId":"..."}, ...}}`
- `POST /api/usage/history` — 최근 7일 시간별 히스토리 (스파크라인용)
- `POST /api/alerts/subscriptions` / `DELETE .../{id}` — Slack 알림 구독·해지
  (payload 전체 AES-256-GCM 암호화, 웹훅은 `hooks.slack.com` 프리픽스만 허용)
- `GET /` — 대시보드, `GET /guides/` — 서비스별 가이드

한 서비스가 실패해도 나머지는 정상 반환됩니다(ERROR 카드 격리).

## 보안 설계

- **인증 3모드** (`AuthFilter`): Google 로그인(ID 토큰 → 자체 JWT 7일, 구독은 소유자 귀속) /
  공유 토큰 / 개방(로컬). 기계 호출(Scheduler·Pub/Sub push)은 별도 토큰 경로.
- **캐시 키 = 자격증명 SHA-256 지문** — 사용자 간 격리, 평문 키 미보관 (60초 TTL)
- **히스토리에는 수치만** — 프로바이더별 잔액 숫자와 비가역 지문만 저장, 키는 절대 저장 안 함
- **암호화 키 회전** — 암호문에 키 지문 태그를 붙여 `ENCRYPTION_OLD_KEYS`로 무중단 회전 가능

## 로컬 실행

```bash
# 키 없이 실행 → 대시보드 키 패널(BYOK)로 조회
mvn spring-boot:run          # http://localhost:8080

# 셀프호스팅: 환경변수로 서버에 키 주입 (필요한 것만)
ELEVENLABS_API_KEY=... OPENAI_ADMIN_KEY=... mvn spring-boot:run
```

## 배포

main에 푸시하면 GitHub Actions가 테스트(100개) 후 Workload Identity Federation(키리스)으로
Cloud Run에 자동 배포합니다 (`.github/workflows/deploy.yml`). 수동 배포:

```bash
gcloud run deploy subscribe-inventory --source . --region asia-northeast3 --allow-unauthenticated
```

선택 환경변수: `GOOGLE_CLIENT_ID`+`JWT_SECRET`(Google 로그인), `ACCESS_TOKEN`(공유 토큰 모드),
`ENCRYPTION_KEY`+`GCP_PROJECT_ID`(Slack 구독·히스토리), `BUDGET_SLACK_WEBHOOK`(예산 알림 릴레이).
공개 배포에는 서버 측 프로바이더 키를 넣지 않습니다(순수 BYOK).

## 라이선스

소스는 투명성(키를 다루는 서비스의 신뢰)을 위해 공개되어 있습니다.
별도 허가 없는 상업적 재사용·재배포는 허용되지 않습니다. © keaunsolNa
