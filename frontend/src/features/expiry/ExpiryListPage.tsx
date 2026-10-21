import { useEffect, useMemo } from 'react'
import { App, Badge, Button, Flex, Segmented, Select, Tag, Typography } from 'antd'
import { DownloadOutlined, SyncOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ColDef } from 'ag-grid-community'
import { useNavigate, useSearchParams } from 'react-router'
import { fetchDashboard } from '../../api/dashboard'
import { downloadExpiryExcel, findExpiries } from '../../api/expiries'
import type { ExpiryItem, ExpiryPeriod, ExpirySearch, TargetType } from '../../api/types'
import { useAuth } from '../../app/AuthContext'
import { DataGrid } from '../../components/DataGrid'
import { DdayBadge } from '../../components/DdayBadge'
import { RenewalStatusSelect, RenewalStatusTag } from '../../components/RenewalStatusTag'
import { SearchField, SearchPanel } from '../../components/SearchPanel'
import { useCodes } from '../../hooks/useCodes'
import { useOwnerOptions } from '../../hooks/useOptions'
import { usePersistedState } from '../../hooks/usePersistedState'
import { useExpiryActions } from './useExpiryActions'

/** 기간 칩 순서와, 대시보드 요약에서 각 칩의 건수를 꺼내는 방법 */
const PERIODS: { value: ExpiryPeriod; label: string; count: (s: { expired: number; within7: number; within30: number; within90: number; unknown: number }) => number; color: string }[] = [
  { value: 'EXPIRED', label: '만료됨', count: (s) => s.expired, color: '#a8071a' },
  { value: 'D7', label: '7일 이내', count: (s) => s.within7, color: '#cf1322' },
  { value: 'D30', label: '30일 이내', count: (s) => s.within30, color: '#d46b08' },
  { value: 'D90', label: '90일 이내', count: (s) => s.within90, color: '#d4b106' },
  { value: 'UNKNOWN', label: '확인 필요', count: (s) => s.unknown, color: '#722ed1' },
]

/** 계약·인증 공통 행을 그리드 행 식별값으로 만든다. */
const rowKey = (row: ExpiryItem) => `${row.targetType}-${row.targetId}`

/**
 * 만료 예정 목록 화면 (FR-007, FR-008).
 * 기간 칩(만료됨·7·30·90일·확인 필요)으로 구간을 고르고, 유형·담당자·처리상태로 좁혀 본다.
 * 행에서 처리상태를 바로 바꾸거나 [갱신 등록]으로 새 계약·인증을 등록할 수 있다.
 * 대시보드 카드에서 들어오면 주소의 ?period= 값으로 구간이 미리 선택된다.
 */
export function ExpiryListPage() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const { canEdit, me } = useAuth()
  const { options, label } = useCodes()
  const ownerOptions = useOwnerOptions()
  const [params, setParams] = useSearchParams()
  const [search, setSearch] = usePersistedState<ExpirySearch>('expiry.search', { period: 'D30' })
  const { changeStatus: handleStatusChange, renew: handleRenew, modals } = useExpiryActions()

  // 대시보드에서 넘어온 조건(?period=D7&mine=1)을 적용하고 주소는 깨끗하게 되돌린다.
  useEffect(() => {
    const period = params.get('period') as ExpiryPeriod | null
    if (!period) return
    setSearch({ period, ownerId: params.get('mine') && me ? me.id : undefined })
    setParams({}, { replace: true })
  }, [params, setParams, setSearch, me])

  const { data, isFetching } = useQuery({ queryKey: ['expiries', search], queryFn: () => findExpiries(search) })
  const { data: summary } = useQuery({ queryKey: ['dashboard'], queryFn: fetchDashboard })

  /** 그리드 컬럼 정의. 맨 앞에 D-day 배지를 두어 급한 정도가 먼저 보이게 한다. */
  const columns = useMemo<ColDef<ExpiryItem>[]>(() => [
    {
      field: 'daysLeft', headerName: 'D-day', width: 140, pinned: 'left',
      cellRenderer: (p: { data: ExpiryItem }) => <DdayBadge daysLeft={p.data.daysLeft} status={p.data.expiryStatus} />,
    },
    {
      field: 'targetType', headerName: '유형', width: 80,
      cellRenderer: (p: { data: ExpiryItem }) => (
        <Tag color={p.data.targetType === 'CONTRACT' ? 'blue' : 'cyan'}>{label('targetType', p.data.targetType)}</Tag>
      ),
    },
    {
      field: 'supplierName', headerName: '업체명', width: 150,
      cellRenderer: (p: { data: ExpiryItem }) => (
        <Typography.Link onClick={() => navigate(`/suppliers/${p.data.supplierId}`)}>{p.data.supplierName}</Typography.Link>
      ),
    },
    { field: 'name', headerName: '대상명', flex: 1, minWidth: 180 },
    { field: 'number', headerName: '번호', width: 160, cellClass: 'num' },
    { field: 'expiryDate', headerName: '만료일', width: 115, cellClass: 'num', valueFormatter: (p) => p.value ?? '없음' },
    { field: 'ownerName', headerName: '담당자', width: 100 },
    {
      field: 'renewalStatus', headerName: '갱신 처리', width: 160,
      cellRenderer: (p: { data: ExpiryItem }) => canEdit
        ? <RenewalStatusSelect value={p.data.renewalStatus} onChange={(s) => handleStatusChange(p.data, s)} />
        : <RenewalStatusTag status={p.data.renewalStatus} />,
    },
    ...(canEdit ? [{
      headerName: '작업', width: 130, sortable: false, pinned: 'right',
      cellRenderer: (p: { data: ExpiryItem }) => (
        <Button size="small" type="primary" ghost icon={<SyncOutlined />} onClick={() => handleRenew(p.data)}>갱신 등록</Button>
      ),
    } as ColDef<ExpiryItem>] : []),
  // handleStatusChange·handleRenew는 매번 새로 만들어지지만 안에서 쓰는 값이 바뀌지 않으므로 의존성에서 뺀다.
  ], [canEdit, label, navigate])

  return (
    <>
      <Flex align="center" gap={12} wrap style={{ marginBottom: 12 }}>
        <Segmented<ExpiryPeriod>
          size="large"
          value={search.period}
          onChange={(period) => setSearch({ ...search, period })}
          options={PERIODS.map((p) => ({
            value: p.value,
            label: (
              <Flex align="center" gap={6} style={{ padding: '0 4px' }}>
                <span style={{ width: 8, height: 8, borderRadius: 4, background: p.color, display: 'inline-block' }} />
                {p.label}
                <Badge count={summary ? p.count(summary) : 0} showZero overflowCount={999}
                  color={search.period === p.value ? p.color : '#bfbfbf'} />
              </Flex>
            ),
          }))}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          기준일 {summary?.today ?? '-'} · 7·30·90일은 오늘부터 해당 일수 안에 만료되는 항목입니다.
        </Typography.Text>
      </Flex>

      <SearchPanel onSearch={() => queryClient.invalidateQueries({ queryKey: ['expiries'] })} onReset={() => setSearch({ period: search.period })}>
        <SearchField label="유형">
          <Segmented<TargetType | 'ALL'>
            value={search.targetType ?? 'ALL'}
            onChange={(v) => setSearch({ ...search, targetType: v === 'ALL' ? undefined : v })}
            options={[{ value: 'ALL', label: '전체' }, ...options('targetType').map((o) => ({ value: o.value as TargetType, label: o.label }))]}
          />
        </SearchField>
        <SearchField label="담당자">
          <Select allowClear style={{ width: 120 }} placeholder="전체" options={ownerOptions} value={search.ownerId}
            onChange={(ownerId) => setSearch({ ...search, ownerId })} />
        </SearchField>
        <SearchField label="처리상태">
          <Select allowClear style={{ width: 120 }} placeholder="전체" options={options('renewalStatus')}
            value={search.renewalStatus} onChange={(renewalStatus) => setSearch({ ...search, renewalStatus })} />
        </SearchField>
        {me && me.role !== 'VIEWER' && (
          <Button type="link" size="small" onClick={() => setSearch({ ...search, ownerId: me.id })}>내 담당만</Button>
        )}
      </SearchPanel>

      <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
        <Typography.Text>
          <b>{PERIODS.find((p) => p.value === search.period)?.label}</b> 총 <b className="num">{data?.length ?? 0}</b>건
          <Typography.Text type="secondary" style={{ marginInlineStart: 12, fontSize: 12 }}>
            급한 순으로 정렬 · 처리상태는 목록에서 바로 바꿀 수 있습니다.
          </Typography.Text>
        </Typography.Text>
        <Button icon={<DownloadOutlined />} onClick={() => downloadExpiryExcel(search).catch(() => message.error('엑셀 파일을 만들지 못했습니다.'))}>
          엑셀 다운로드
        </Button>
      </Flex>

      <DataGrid rows={data} columns={columns} loading={isFetching && !data} rowId={rowKey} />

      {modals}
    </>
  )
}
