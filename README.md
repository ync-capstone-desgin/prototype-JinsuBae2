# 협력업체 통합관리 시연 프로토타입 (JinsuBae2)

한국농산 캡스톤 디자인 과제의 팀원별 시연 프로토타입이다. 설계 근거는 capstone-docs 저장소의 `docs/JinsuBae2/prototype-design.md`를 따른다.

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 백엔드 | Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security, Flyway |
| 데이터베이스 | PostgreSQL 16 (Docker Compose) |
| 알림 | DB 아웃박스 + 정기 발송기, 화면 알림(SSE), 이메일(개발용 Mailpit) |
| 프론트엔드 | React 19 + TypeScript + Vite, Ant Design 6, AG Grid Community, TanStack Query |

## 실행 방법

사전 준비: Docker Desktop, Java 21, Node.js 20 이상

```bash
cp .env.example .env
docker compose up -d
cd backend && ./gradlew bootRun
```

다른 터미널에서 화면을 실행한다.

```bash
cd frontend && npm install && npm run dev
```

- 화면: http://localhost:5173 (API 요청은 Vite 프록시로 8080에 전달)

- API 문서: http://localhost:8080/swagger-ui.html
- 개발용 메일함(Mailpit): http://localhost:8025 — 알림 메일은 실제로 나가지 않고 여기에 쌓인다
- 기본 프로파일은 `dev`이며, DB가 비어 있으면 가상 샘플 데이터가 자동으로 들어간다.
- DB 초기화: `docker compose down -v` 후 다시 실행
- 시연용 기준일 변경: admin으로 로그인 후 상단 "기준일" 태그를 눌러 날짜를 옮긴다 (dev 프로파일 전용, 서버 재시작 시 실제 날짜로 복귀)

## 시연 계정

비밀번호는 `.env.example`의 `SEED_PASSWORD` 값이다.

| 아이디 | 이름 | 역할 | 권한 |
|---|---|---|---|
| admin | 관리자 | ADMIN | 전체 + 기준정보·시연 도구 |
| manager1 | 김담당 | MANAGER | 조회, 등록·수정 |
| manager2 | 이담당 | MANAGER | 조회, 등록·수정 |
| viewer | 박조회 | VIEWER | 조회만 |

## 구현 현황

| 단계 | 기능 | 요구사항 | 상태 |
|---|---|---|---|
| 1 | 로그인·역할, 협력업체 등록·조회·수정·검색·엑셀 | FR-001~003, FR-013 | 완료 (API + 화면) |
| 2 | 계약·인증 등록·수정·갱신(이력 연결)·처리상태, 업체별 조회, 문서 링크 | FR-004~006, FR-012 | 완료 (API + 화면) |
| 3 | 만료 판정(D-day)·만료 예정 목록·대시보드·시연용 기준일 변경 | FR-007~009 | 완료 (API + 화면) |
| 4 | 만료 점검·알림 생성(중복 방지)·아웃박스 발송(화면·이메일, 재시도)·알림함·알림 기준 설정 | FR-010, FR-011 | 완료 (API + 화면) |
| 5 | 만료 캘린더: 월 달력에 만료일 표시, 날짜별 목록에서 처리상태 변경·갱신 등록 | FR-008 확장 (벤치마킹) | 완료 (API + 화면) |
| 6 | 갱신 현황판: 처리상태별 칸에 카드를 끌어 놓아 상태 변경, 완료 칸에 놓으면 갱신 등록 | FR-006 확장 (벤치마킹) | 완료 (API + 화면) |

## 서버 배포 (담당자 체험용)

AWS EC2 한 대에 Docker Compose로 올려 URL로 접속하게 한다. 절차는 [docs/deploy-aws.md](docs/deploy-aws.md)를 따른다.

## 사용 설명서

메뉴별 화면과 사용법은 스크린샷과 함께 [docs/user-guide.md](docs/user-guide.md)에 정리했다.

## 시연 순서 (약 5분)

1. **manager1**로 로그인 → 대시보드에서 오늘 처리할 일(만료·7일 이내 건수) 확인
2. 다른 브라우저(또는 시크릿 창)에서 **admin**으로 로그인 → [알림 설정] → [만료 점검 지금 실행]
3. manager1 화면에 새로고침 없이 "새 만료 알림 N건" 팝업과 🔔 배지가 뜬다 → 알림함에서 확인
4. Mailpit(http://localhost:8025)에서 담당자에게 간 알림 메일 확인
5. 알림함 → [업체 보기] → 계약 탭에서 [갱신] 등록 → 해당 알림이 자동으로 "완료"
6. admin이 상단 기준일을 3개월 뒤로 옮기고 다시 점검 → 새로 임박한 항목의 알림이 생성됨
7. 같은 날짜로 점검을 한 번 더 실행해도 새 알림 0건 (중복 방지)

## 테스트

```bash
cd backend && ./gradlew test
```

Testcontainers로 실제 PostgreSQL을 띄워 실행하므로 Docker가 켜져 있어야 한다. (총 38건)

| 테스트 | 확인 내용 |
|---|---|
| SupplierApiTest | VT-001 정상 등록, VT-002 필수값 누락, 역할별 권한 |
| ContractApiTest | VT-003 종료일 < 시작일, VT-007 업체별 조회, 갱신 이력, 중복 경고, 갱신완료 직접 지정·이력 건 상태 변경 차단 |
| ExpiryPolicyTest | VT-004 오늘 만료, VT-005 7·30·90일 경계 |
| ExpiryApiTest | VT-008 만료 목록 필터, VT-009 대시보드 = 목록 건수, 만료 캘린더 기간 조회·기간 제한, 갱신 현황판 대상 |
| NotificationScanTest | VT-006 중복 알림 방지, 가장 작은 기준일 적용, 제외 대상, 발송 실패 재시도, 갱신 시 자동 완료 |
