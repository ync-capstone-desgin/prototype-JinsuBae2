import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import koKR from 'antd/locale/ko_KR'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { AppLayout } from './app/AppLayout'
import { AuthProvider } from './app/AuthContext'
import { RequireAuth } from './app/RequireAuth'
import { TabsProvider } from './app/TabsContext'
import { theme } from './app/theme'
import { CertificationListPage } from './features/certification/CertificationListPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { ExpiryCalendarPage } from './features/expiry/ExpiryCalendarPage'
import { ExpiryListPage } from './features/expiry/ExpiryListPage'
import { RenewalBoardPage } from './features/expiry/RenewalBoardPage'
import { NotificationInboxPage } from './features/notification/NotificationInboxPage'
import { SettingsPage } from './features/settings/SettingsPage'
import { ContractListPage } from './features/contract/ContractListPage'
import { SupplierDetailPage } from './features/supplier/SupplierDetailPage'
import { SupplierListPage } from './features/supplier/SupplierListPage'
import { LoginPage } from './pages/LoginPage'
import './index.css'

dayjs.locale('ko')

/**
 * 서버 데이터 캐시 설정.
 * 같은 데이터를 30초 안에 다시 볼 때는 캐시를 쓰고, 실패한 요청은 1번만 재시도한다.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
  },
})

/**
 * 앱 진입점: 전역 설정(한글 로케일·테마·데이터 캐시·로그인 상태)을 감싸고 화면 경로를 정의한다.
 * 로그인 후 첫 화면은 대시보드다.
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider locale={koKR} theme={theme}>
      <AntApp style={{ height: '100%' }}>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <AuthProvider>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route
                  element={
                    <RequireAuth>
                      <TabsProvider>
                        <AppLayout />
                      </TabsProvider>
                    </RequireAuth>
                  }
                >
                  <Route index element={<Navigate to="/dashboard" replace />} />
                  <Route path="/dashboard" element={<DashboardPage />} />
                  <Route path="/suppliers" element={<SupplierListPage />} />
                  <Route path="/suppliers/:id" element={<SupplierDetailPage />} />
                  <Route path="/contracts" element={<ContractListPage />} />
                  <Route path="/certifications" element={<CertificationListPage />} />
                  <Route path="/expiries" element={<ExpiryListPage />} />
                  <Route path="/expiry-calendar" element={<ExpiryCalendarPage />} />
                  <Route path="/renewal-board" element={<RenewalBoardPage />} />
                  <Route path="/notifications" element={<NotificationInboxPage />} />
                  <Route path="/settings" element={<SettingsPage />} />
                  <Route path="*" element={<Navigate to="/dashboard" replace />} />
                </Route>
              </Routes>
            </AuthProvider>
          </BrowserRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
)
