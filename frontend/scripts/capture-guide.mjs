/**
 * 사용 설명서(docs/user-guide.md)용 화면 캡처 스크립트.
 *
 * 준비: 샘플 데이터만 있는 깨끗한 DB로 백엔드(8080)·프론트(5173)·Mailpit(8025)을 띄운다.
 *   docker compose down -v && docker compose up -d
 *   (backend) ./gradlew bootRun   (frontend) npm run dev
 * 실행: (frontend) npm run capture:guide
 *
 * 설치된 Google Chrome을 사용하므로 브라우저를 따로 내려받지 않는다.
 * 실행하면 만료 점검이 한 번 돌아 알림이 생성되므로, 끝난 뒤 DB를 다시 초기화하면 된다.
 */
import { readFileSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL ?? 'http://localhost:5173'
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025'
const OUT = new URL('../../docs/images/', import.meta.url).pathname
const CHROME = process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

/** 시연 계정 비밀번호를 환경변수 또는 .env.example에서 읽는다. */
function seedPassword() {
  if (process.env.SEED_PASSWORD) return process.env.SEED_PASSWORD
  const env = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8')
  return env.match(/^SEED_PASSWORD=(.*)$/m)[1].trim()
}

const PASSWORD = seedPassword()
const VIEWPORT = { width: 1440, height: 900 }

/** 화면을 PNG로 저장한다. 애니메이션이 끝나도록 잠깐 기다린 뒤 찍는다. */
async function shot(page, name, options = {}) {
  await page.waitForTimeout(options.wait ?? 500)
  await page.screenshot({ path: `${OUT}${name}.png`, ...options.screenshot })
  console.log(`  ✓ ${name}.png`)
}

/** 새 브라우저 창(쿠키가 분리된 컨텍스트)을 열고 화면에서 로그인한다. */
async function loginPage(browser, username) {
  const context = await browser.newContext({ viewport: VIEWPORT, locale: 'ko-KR' })
  const page = await context.newPage()
  await page.goto(`${BASE}/login`)
  await page.getByLabel('아이디').fill(username)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '로그인' }).click()
  await page.waitForURL('**/dashboard')
  await page.waitForSelector('.ant-card')
  return page
}

/** 메뉴를 눌러 화면을 열고 그리드가 그려질 때까지 기다린다. */
async function openMenu(page, label, { grid = true } = {}) {
  await page.locator('.ant-layout-sider .ant-menu-item', { hasText: label }).click()
  if (grid) await page.waitForSelector('.ag-row')
}

/** 관리자 권한으로 발송 대기열이 빌 때까지(최대 30초) 기다린다. 늦게 도착한 알림 팝업이 다른 화면을 가리지 않게 하기 위함. */
async function waitDeliveriesDone(adminPage) {
  for (let i = 0; i < 30; i++) {
    const pending = await adminPage.evaluate(async () =>
      (await (await fetch('/api/admin/deliveries')).json()).filter((d) => d.status === 'PENDING').length)
    if (pending === 0) return
    await adminPage.waitForTimeout(1000)
  }
}

/** 화면 오른쪽 위에 떠 있는 알림 팝업을 모두 닫는다. */
async function closeToasts(page) {
  await page.waitForTimeout(1500)
  for (const button of await page.locator('.ant-notification-notice-close').all()) {
    await button.click().catch(() => {})
  }
  await page.waitForTimeout(500)
}

/** 모달의 [저장] 버튼. antd가 두 글자 버튼에 공백을 넣으므로("저 장") 공백을 허용한다. */
const saveButton = (page) => page.locator('.ant-modal-footer').getByRole('button', { name: /저\s?장/ })

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
try {
  console.log('1) 로그인')
  {
    const context = await browser.newContext({ viewport: VIEWPORT, locale: 'ko-KR' })
    const page = await context.newPage()
    await page.goto(`${BASE}/login`)
    await page.waitForSelector('form')
    await shot(page, '01-login')
    await context.close()
  }

  console.log('2) 담당자(manager1): 대시보드 → 관리자가 점검 실행 → 실시간 알림')
  const manager = await loginPage(browser, 'manager1')
  await manager.waitForSelector('.ag-row')
  await shot(manager, '02-dashboard')

  const admin = await loginPage(browser, 'admin')
  const scan = await admin.evaluate(async () => (await fetch('/api/admin/jobs/expiry-scan', { method: 'POST' })).json())
  console.log(`  (점검: 새 알림 ${scan.scan.created}건, 중복 건너뜀 ${scan.scan.skipped}건)`)
  if (scan.scan.created === 0) {
    console.warn('  ! 새 알림이 없어 실시간 팝업이 뜨지 않습니다. DB를 초기화한 뒤 다시 실행하세요.')
  }
  await manager.waitForTimeout(2500)
  await shot(manager, '03-realtime-popup', { wait: 0 })
  await waitDeliveriesDone(admin)
  await closeToasts(manager)

  console.log('3) 협력업체')
  await openMenu(manager, '협력업체')
  await shot(manager, '04-suppliers')
  await manager.getByRole('button', { name: '업체 등록' }).click()
  await manager.waitForSelector('.ant-modal')
  await saveButton(manager).click()
  await shot(manager, '05-supplier-form')
  await manager.keyboard.press('Escape')
  await manager.locator('.ag-row').first().dblclick()
  await manager.waitForURL('**/suppliers/*')
  await manager.getByRole('tab', { name: /계약/ }).click()
  await manager.waitForSelector('text=이전 이력 포함')
  await manager.waitForTimeout(800)
  await shot(manager, '06-supplier-detail')

  console.log('4) 계약 관리 · 인증 관리')
  await openMenu(manager, '계약 관리')
  await shot(manager, '07-contracts')
  await manager.locator('.ag-row').first().getByRole('button', { name: /갱신/ }).click()
  await manager.waitForSelector('.ant-modal')
  await shot(manager, '08-contract-renew')
  await manager.keyboard.press('Escape')
  await openMenu(manager, '인증 관리')
  await shot(manager, '09-certifications')

  console.log('5) 만료 예정')
  await openMenu(manager, '만료 예정')
  await manager.getByText('7일 이내').first().click()
  await manager.waitForTimeout(500)
  await shot(manager, '10-expiries')

  console.log('5-1) 만료 캘린더')
  await openMenu(manager, '만료 캘린더', { grid: false })
  await manager.waitForSelector('.expiry-chip')
  // 이번 달에서 만료 건이 있는 첫 날짜를 눌러 오른쪽 목록 패널까지 보이게 한다.
  await manager.locator('.ant-picker-cell-in-view .expiry-chip').first().click()
  await shot(manager, '17-expiry-calendar', { wait: 800 })

  console.log('5-2) 갱신 현황판')
  await openMenu(manager, '갱신 현황판', { grid: false })
  await manager.waitForSelector('.board-card')
  await shot(manager, '18-renewal-board')

  console.log('6) 알림함 · 알림 미리보기')
  await openMenu(manager, '알림함', { grid: false })
  await manager.waitForSelector('text=업체 보기')
  await shot(manager, '11-notifications')
  await manager.locator('.ant-layout-header .ant-badge button').click()
  await manager.waitForSelector('.ant-popover')
  await shot(manager, '12-bell')
  await manager.keyboard.press('Escape')

  console.log('7) 관리자(admin): 알림 설정 · 기준일 변경')
  await openMenu(admin, '알림 설정', { grid: false })
  await admin.waitForSelector('.ag-row')
  await shot(admin, '13-settings')
  await admin.locator('.ant-layout-header .ant-tag', { hasText: '기준일' }).click()
  await admin.waitForSelector('.ant-popover')
  await shot(admin, '14-clock')

  console.log('8) 조회자(viewer): 등록·수정 버튼이 보이지 않는 화면')
  const viewer = await loginPage(browser, 'viewer')
  await openMenu(viewer, '계약 관리')
  await shot(viewer, '15-viewer-contracts')

  console.log('9) 개발용 메일함(Mailpit)')
  {
    const context = await browser.newContext({ viewport: VIEWPORT, locale: 'ko-KR' })
    const page = await context.newPage()
    await page.goto(MAILPIT)
    await page.locator('a.message, .message').first().click()
    await page.waitForTimeout(1000)
    await shot(page, '16-mailpit')
    await context.close()
  }
  console.log(`완료: ${OUT}`)
} finally {
  await browser.close()
}
