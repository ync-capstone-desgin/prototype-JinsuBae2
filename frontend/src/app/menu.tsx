import type { ReactNode } from 'react'
import {
  AlertOutlined, AuditOutlined, BellOutlined, CalendarOutlined, DashboardOutlined, FileTextOutlined, ProjectOutlined, SettingOutlined, ShopOutlined,
} from '@ant-design/icons'

/** 좌측 메뉴 항목 하나 */
export interface MenuEntry {
  /** 이동할 경로 (탭의 식별자로도 쓴다) */
  path: string
  label: string
  icon: ReactNode
  /** 아직 개발 전인 메뉴. 전체 구성을 보여주기 위해 비활성 상태로 표시한다. */
  planned?: boolean
}

/**
 * 좌측 메뉴 구성. 설계서 6.3 화면 목록 순서를 따른다.
 * 기능이 개발되면 planned 표시를 지운다.
 */
export const MENU: MenuEntry[] = [
  { path: '/dashboard', label: '대시보드', icon: <DashboardOutlined /> },
  { path: '/suppliers', label: '협력업체', icon: <ShopOutlined /> },
  { path: '/contracts', label: '계약 관리', icon: <FileTextOutlined /> },
  { path: '/certifications', label: '인증 관리', icon: <AuditOutlined /> },
  { path: '/expiries', label: '만료 예정', icon: <AlertOutlined /> },
  { path: '/expiry-calendar', label: '만료 캘린더', icon: <CalendarOutlined /> },
  { path: '/renewal-board', label: '갱신 현황판', icon: <ProjectOutlined /> },
  { path: '/notifications', label: '알림함', icon: <BellOutlined /> },
  { path: '/settings', label: '알림 설정', icon: <SettingOutlined /> },
]

/** 경로에 맞는 메뉴 항목을 찾는다. 상세 경로(/suppliers/3)는 상위 메뉴(/suppliers)로 본다. */
export function findMenu(pathname: string): MenuEntry | undefined {
  return MENU.find((m) => pathname === m.path || pathname.startsWith(m.path + '/'))
}
