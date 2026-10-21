import { useMemo, useState, type DragEvent } from 'react'
import { Badge, Button, Empty, Flex, Segmented, Select, Space, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { findRenewalBoard } from '../../api/expiries'
import type { ExpiryItem, RenewalStatus, TargetType } from '../../api/types'
import { useAuth } from '../../app/AuthContext'
import { DdayBadge } from '../../components/DdayBadge'
import { useCodes } from '../../hooks/useCodes'
import { useOwnerOptions } from '../../hooks/useOptions'
import { usePersistedState } from '../../hooks/usePersistedState'
import { useExpiryActions } from './useExpiryActions'

/** 현황판 칸 구성. 갱신 처리상태 하나가 칸 하나이며, 업무 흐름 순서(미착수 → 진행 → 완료)로 놓고 갱신안함은 맨 끝에 둔다. */
const COLUMNS: { status: RenewalStatus; title: string; hint: string; color: string }[] = [
  { status: 'NONE', title: '미착수', hint: '아직 갱신 작업을 시작하지 않은 건', color: '#8c8c8c' },
  { status: 'IN_PROGRESS', title: '갱신진행', hint: '업체와 협의·서류 준비 중', color: '#1677ff' },
  { status: 'DONE', title: '갱신완료', hint: '최근 30일 안에 갱신 등록된 건', color: '#389e0d' },
  { status: 'NOT_RENEWING', title: '갱신안함', hint: '연장하지 않기로 한 건 (알림 종료)', color: '#d46b08' },
]

/** 현황판 거르기 조건 (브라우저에 저장되어 탭을 옮겨도 유지된다) */
interface BoardFilter {
  targetType?: TargetType
  ownerId?: number
}

/** 카드 식별값. 계약·인증 ID가 겹칠 수 있으므로 종류를 붙인다. */
const cardKey = (i: ExpiryItem) => `${i.targetType}-${i.targetId}`

/**
 * 갱신 현황판 화면.
 * 만료가 다가온(또는 지난) 계약·인증을 갱신 처리상태별 칸에 카드로 놓고, 카드를 끌어 옮겨 상태를 바꾼다.
 * - 미착수·갱신진행·갱신안함 칸 사이 이동: 처리상태를 바로 바꾼다.
 * - 갱신완료 칸에 놓기: 갱신 등록 창을 연다. 새 계약·인증을 저장해야 완료 칸으로 옮겨진다.
 * - 갱신완료 카드는 이미 이력이 되었으므로 옮길 수 없다. 조회자는 보기만 한다.
 */
export function RenewalBoardPage() {
  const navigate = useNavigate()
  const { canEdit, me } = useAuth()
  const { options, label } = useCodes()
  const ownerOptions = useOwnerOptions()
  const { changeStatus, renew, modals } = useExpiryActions()
  const [filter, setFilter] = usePersistedState<BoardFilter>('renewalBoard.filter', {})
  const [dragging, setDragging] = useState<ExpiryItem>()
  const [overColumn, setOverColumn] = useState<RenewalStatus>()
  // 서버 저장이 끝나기 전에도 카드가 놓은 칸에 바로 보이도록 임시로 덮어쓰는 상태
  const [pending, setPending] = useState<{ key: string; status: RenewalStatus }>()

  const { data, isFetching } = useQuery({
    queryKey: ['renewalBoard', filter],
    queryFn: () => findRenewalBoard(filter),
  })

  /** 카드를 처리상태별 칸으로 나눈다. 서버 응답이 급한 순이므로 칸 안 순서도 급한 순이다. */
  const byStatus = useMemo(() => {
    const map = new Map<RenewalStatus, ExpiryItem[]>(COLUMNS.map((c) => [c.status, []]))
    for (const item of data ?? []) {
      const status = pending && pending.key === cardKey(item) ? pending.status : item.renewalStatus
      map.get(status)?.push(item)
    }
    return map
  }, [data, pending])

  /** 카드를 끌 수 있는지: 수정 권한이 있고, 이미 갱신 완료된 이력 카드가 아니어야 한다. */
  const canDrag = (item: ExpiryItem) => canEdit && item.renewalStatus !== 'DONE'

  /** 카드를 다른 칸에 놓았을 때 처리한다. 완료 칸이면 갱신 창을 열고, 그 밖의 칸이면 처리상태를 바꾼다. */
  const handleDrop = async (status: RenewalStatus) => {
    const item = dragging
    setDragging(undefined)
    setOverColumn(undefined)
    if (!item || item.renewalStatus === status) return
    if (status === 'DONE') {
      renew(item)
      return
    }
    setPending({ key: cardKey(item), status })
    await changeStatus(item, status)
    setPending(undefined)
  }

  /** 끌고 있는 카드를 이 칸에 놓을 수 있다고 브라우저에 알리고, 칸을 강조한다. */
  const handleDragOver = (e: DragEvent, status: RenewalStatus) => {
    if (!dragging || dragging.renewalStatus === status) return
    e.preventDefault()
    if (overColumn !== status) setOverColumn(status)
  }

  const total = data?.filter((i) => i.renewalStatus !== 'DONE').length ?? 0

  return (
    <>
      <Flex align="center" justify="space-between" wrap gap={12} style={{ marginBottom: 12 }}>
        <Typography.Text>
          조치 대상 <b className="num">{total}</b>건
          <Typography.Text type="secondary" style={{ marginInlineStart: 8, fontSize: 12 }}>
            이미 만료됐거나 90일 안에 만료되는 계약·인증 · {canEdit ? '카드를 끌어 다른 칸에 놓으면 처리상태가 바뀝니다.' : '조회 전용입니다.'}
          </Typography.Text>
        </Typography.Text>
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

      <div className="board">
        {COLUMNS.map((col) => {
          const items = byStatus.get(col.status) ?? []
          return (
            <section
              key={col.status}
              className={`board-column${overColumn === col.status ? ' board-column-over' : ''}`}
              onDragOver={(e) => handleDragOver(e, col.status)}
              onDragLeave={() => setOverColumn(undefined)}
              onDrop={() => handleDrop(col.status)}
              aria-label={`${col.title} 칸`}
            >
              <header className="board-column-head" style={{ borderTopColor: col.color }}>
                <Flex align="center" gap={8}>
                  <b>{col.title}</b>
                  <Badge count={items.length} showZero overflowCount={999} color={col.color} />
                </Flex>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{col.hint}</Typography.Text>
              </header>
              <div className="board-column-body">
                {items.length === 0 && !isFetching && (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="카드가 없습니다." />
                )}
                {items.map((i) => (
                  <article
                    key={cardKey(i)}
                    className={`board-card${canDrag(i) ? ' board-card-draggable' : ''}${dragging && cardKey(dragging) === cardKey(i) ? ' board-card-dragging' : ''}`}
                    draggable={canDrag(i)}
                    onDragStart={(e) => {
                      e.dataTransfer.effectAllowed = 'move'
                      setDragging(i)
                    }}
                    onDragEnd={() => {
                      setDragging(undefined)
                      setOverColumn(undefined)
                    }}
                  >
                    <Flex align="center" gap={6} wrap>
                      {i.renewalStatus === 'DONE'
                        ? <Tag color="success">갱신 등록됨</Tag>
                        : <DdayBadge daysLeft={i.daysLeft} status={i.expiryStatus} />}
                      <Tag color={i.targetType === 'CONTRACT' ? 'blue' : 'cyan'}>{label('targetType', i.targetType)}</Tag>
                    </Flex>
                    <Typography.Link strong onClick={() => navigate(`/suppliers/${i.supplierId}`)} style={{ display: 'block', marginTop: 6 }}>
                      {i.supplierName}
                    </Typography.Link>
                    <div className="board-card-name">{i.name}</div>
                    <Flex justify="space-between" style={{ fontSize: 12, color: '#6b7280', marginTop: 4 }}>
                      <span className="num">만료 {i.expiryDate}</span>
                      <span>담당 {i.ownerName ?? '미지정'}</span>
                    </Flex>
                  </article>
                ))}
              </div>
            </section>
          )
        })}
      </div>

      {modals}
    </>
  )
}
