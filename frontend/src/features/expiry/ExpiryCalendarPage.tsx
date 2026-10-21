import { useMemo, useState } from 'react'
import { Button, Calendar, Card, Empty, Flex, Segmented, Select, Space, Tag, Typography } from 'antd'
import { LeftOutlined, RightOutlined, SyncOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useNavigate } from 'react-router'
import { fetchDashboard } from '../../api/dashboard'
import { findExpiryCalendar } from '../../api/expiries'
import type { ExpiryItem, TargetType } from '../../api/types'
import { useAuth } from '../../app/AuthContext'
import { DdayBadge, URGENCY_COLOR } from '../../components/DdayBadge'
import { RenewalStatusSelect, RenewalStatusTag } from '../../components/RenewalStatusTag'
import { useCodes } from '../../hooks/useCodes'
import { useOwnerOptions } from '../../hooks/useOptions'
import { usePersistedState } from '../../hooks/usePersistedState'
import { useExpiryActions } from './useExpiryActions'

/** 날짜 칸 하나에 바로 보여줄 최대 건수. 넘치면 "+N건"으로 줄인다. */
const MAX_IN_CELL = 3

/** 달력 거르기 조건 (브라우저에 저장되어 탭을 옮겨도 유지된다) */
interface CalendarFilter {
  targetType?: TargetType
  ownerId?: number
}

/** 남은 일수로 칸 안 항목의 왼쪽 띠 색을 정한다. 목록 화면의 D-day 배지와 같은 기준이다. */
function chipColor(daysLeft: number | null): string {
  if (daysLeft == null) return URGENCY_COLOR.unknown
  if (daysLeft < 0) return URGENCY_COLOR.expired
  if (daysLeft <= 7) return URGENCY_COLOR.d7
  if (daysLeft <= 30) return URGENCY_COLOR.d30
  if (daysLeft <= 90) return URGENCY_COLOR.d90
  return '#bfbfbf'
}

/**
 * 달력 한 화면(6주 = 42일)의 첫날과 마지막 날을 구한다.
 * 이전·다음 달 날짜도 칸에 보이므로 그 날짜의 만료 건까지 함께 조회한다.
 */
function visibleRange(month: Dayjs) {
  const first = month.startOf('month').startOf('week')
  return { from: first, to: first.add(41, 'day') }
}

/**
 * 만료 캘린더 화면.
 * 계약 종료일·인증 만료일을 월 달력에 표시해 "언제 몰려 있는지"를 한눈에 보여준다.
 * 날짜를 누르면 오른쪽에 그날 만료되는 건이 나오고, 여기서 처리상태 변경과 갱신 등록을 바로 할 수 있다.
 * 처음 열면 기준일(시연 기준일 포함)이 있는 달과 날짜가 선택된다.
 */
export function ExpiryCalendarPage() {
  const navigate = useNavigate()
  const { canEdit, me } = useAuth()
  const { options, label } = useCodes()
  const ownerOptions = useOwnerOptions()
  const { changeStatus, renew, modals } = useExpiryActions()
  const [filter, setFilter] = usePersistedState<CalendarFilter>('expiryCalendar.filter', {})
  const [picked, setPicked] = useState<Dayjs>()

  // 기준일은 시연용으로 바뀔 수 있으므로 브라우저 날짜가 아니라 서버의 기준일을 쓴다.
  const { data: summary } = useQuery({ queryKey: ['dashboard'], queryFn: fetchDashboard })
  const today = useMemo(() => (summary ? dayjs(summary.today) : undefined), [summary])
  // 사용자가 아직 날짜를 고르지 않았으면 기준일을 선택한 것으로 본다.
  const selected = picked ?? today
  const setSelected = setPicked

  const month = (selected ?? dayjs()).startOf('month')
  const range = visibleRange(month)
  const search = {
    from: range.from.format('YYYY-MM-DD'),
    to: range.to.format('YYYY-MM-DD'),
    targetType: filter.targetType,
    ownerId: filter.ownerId,
  }
  const { data, isFetching } = useQuery({
    queryKey: ['expiryCalendar', search],
    queryFn: () => findExpiryCalendar(search),
    enabled: !!selected,
  })

  /** 조회 결과를 날짜(YYYY-MM-DD)별로 묶는다. */
  const byDate = useMemo(() => {
    const map = new Map<string, ExpiryItem[]>()
    for (const item of data ?? []) {
      if (!item.expiryDate) continue
      map.set(item.expiryDate, [...(map.get(item.expiryDate) ?? []), item])
    }
    return map
  }, [data])

  /** 이번 달(1일~말일)에 만료되는 건만 세어 상단 요약에 쓴다. */
  const monthItems = (data ?? []).filter((i) => i.expiryDate && dayjs(i.expiryDate).isSame(month, 'month'))
  const monthContracts = monthItems.filter((i) => i.targetType === 'CONTRACT').length
  const monthNotStarted = monthItems.filter((i) => i.renewalStatus === 'NONE').length
  const selectedItems = selected ? byDate.get(selected.format('YYYY-MM-DD')) ?? [] : []

  /** 달력 날짜 칸 안에 그날 만료되는 건을 색 띠와 함께 그린다. */
  const renderDate = (date: Dayjs) => {
    const items = byDate.get(date.format('YYYY-MM-DD')) ?? []
    return (
      <div className="expiry-cell">
        {today && date.isSame(today, 'day') && <span className="expiry-cell-today">기준일</span>}
        {items.slice(0, MAX_IN_CELL).map((i) => (
          <div key={`${i.targetType}-${i.targetId}`} className="expiry-chip" style={{ borderLeftColor: chipColor(i.daysLeft) }}
            title={`${label('targetType', i.targetType)} · ${i.supplierName} · ${i.name}`}>
            {i.supplierName} · {i.name}
          </div>
        ))}
        {items.length > MAX_IN_CELL && <div className="expiry-more">+{items.length - MAX_IN_CELL}건</div>}
      </div>
    )
  }

  /** 이전·다음 달로 옮긴다. 같은 일(日)을 선택한 상태로 이동한다. */
  const moveMonth = (diff: number) => setSelected((selected ?? dayjs()).add(diff, 'month'))

  return (
    <>
      <Flex align="center" justify="space-between" wrap gap={12} style={{ marginBottom: 12 }}>
        <Flex align="center" gap={8}>
          <Button icon={<LeftOutlined />} onClick={() => moveMonth(-1)} aria-label="이전 달" />
          <Typography.Title level={4} style={{ margin: 0, minWidth: 120, textAlign: 'center' }} className="num">
            {month.format('YYYY년 M월')}
          </Typography.Title>
          <Button icon={<RightOutlined />} onClick={() => moveMonth(1)} aria-label="다음 달" />
          <Button onClick={() => today && setSelected(today)}>기준일로</Button>
        </Flex>
        <Space wrap>
          <Segmented<TargetType | 'ALL'>
            value={filter.targetType ?? 'ALL'}
            onChange={(v) => setFilter({ ...filter, targetType: v === 'ALL' ? undefined : v })}
            options={[{ value: 'ALL', label: '전체' }, ...options('targetType').map((o) => ({ value: o.value as TargetType, label: o.label }))]}
          />
          <Select allowClear style={{ width: 120 }} placeholder="담당자 전체" options={ownerOptions} value={filter.ownerId}
            onChange={(ownerId) => setFilter({ ...filter, ownerId })} />
          {me && me.role !== 'VIEWER' && (
            <Button type="link" size="small" onClick={() => setFilter({ ...filter, ownerId: me.id })}>내 담당만</Button>
          )}
        </Space>
      </Flex>

      <Flex align="center" gap={16} wrap style={{ marginBottom: 8 }}>
        <Typography.Text>
          이번 달 만료 <b className="num">{monthItems.length}</b>건
          <Typography.Text type="secondary"> (계약 {monthContracts} · 인증 {monthItems.length - monthContracts})</Typography.Text>
          {monthNotStarted > 0 && <> · 갱신 미착수 <b className="num" style={{ color: URGENCY_COLOR.d7 }}>{monthNotStarted}</b>건</>}
        </Typography.Text>
        <Flex gap={10} style={{ fontSize: 12, color: '#6b7280' }}>
          {[['만료됨', URGENCY_COLOR.expired], ['7일 이내', URGENCY_COLOR.d7], ['30일 이내', URGENCY_COLOR.d30], ['90일 이내', URGENCY_COLOR.d90], ['그 이후', '#bfbfbf']].map(([text, color]) => (
            <Flex key={text} align="center" gap={4}>
              <span style={{ width: 10, height: 10, borderRadius: 2, background: color, display: 'inline-block' }} />{text}
            </Flex>
          ))}
        </Flex>
      </Flex>

      <Flex gap={16} align="flex-start" className="expiry-calendar-layout">
        <Card size="small" style={{ flex: 1, minWidth: 0 }} loading={!selected}>
          <Calendar
            className="expiry-calendar"
            value={selected}
            headerRender={() => null}
            onSelect={(date) => setSelected(date)}
            cellRender={(date, info) => (info.type === 'date' ? renderDate(date) : info.originNode)}
          />
          {isFetching && <Typography.Text type="secondary" style={{ fontSize: 12 }}>불러오는 중…</Typography.Text>}
        </Card>

        <Card
          size="small"
          className="expiry-day-panel"
          title={selected ? <span className="num">{selected.format('M월 D일 (dd)')} 만료 {selectedItems.length}건</span> : '날짜를 선택하세요'}
        >
          {selectedItems.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="이 날 만료되는 계약·인증이 없습니다." />
          ) : (
            <Flex vertical gap={10}>
              {selectedItems.map((i) => (
                <div key={`${i.targetType}-${i.targetId}`} className="expiry-day-item">
                  <Flex align="center" gap={6} wrap>
                    <DdayBadge daysLeft={i.daysLeft} status={i.expiryStatus} />
                    <Tag color={i.targetType === 'CONTRACT' ? 'blue' : 'cyan'}>{label('targetType', i.targetType)}</Tag>
                    <Typography.Link strong onClick={() => navigate(`/suppliers/${i.supplierId}`)}>{i.supplierName}</Typography.Link>
                  </Flex>
                  <div style={{ margin: '6px 0' }}>
                    {i.name}
                    <Typography.Text type="secondary" className="num" style={{ marginInlineStart: 6, fontSize: 12 }}>{i.number}</Typography.Text>
                  </div>
                  <Flex align="center" justify="space-between" gap={8}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>담당 {i.ownerName ?? '미지정'}</Typography.Text>
                    {canEdit ? (
                      <Flex align="center" gap={4}>
                        <RenewalStatusSelect value={i.renewalStatus} onChange={(s) => changeStatus(i, s)} />
                        <Button size="small" type="primary" ghost icon={<SyncOutlined />} onClick={() => renew(i)}>갱신 등록</Button>
                      </Flex>
                    ) : (
                      <RenewalStatusTag status={i.renewalStatus} />
                    )}
                  </Flex>
                </div>
              ))}
            </Flex>
          )}
        </Card>
      </Flex>

      {modals}
    </>
  )
}
