import { useState } from 'react'
import { App } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { changeCertificationRenewalStatus, fetchCertification } from '../../api/certifications'
import { toProblem } from '../../api/client'
import { changeContractRenewalStatus, fetchContract } from '../../api/contracts'
import type { CertificationItem, ContractItem, ExpiryItem, RenewalStatus } from '../../api/types'
import { CertificationFormModal } from '../certification/CertificationFormModal'
import { ContractFormModal } from '../contract/ContractFormModal'

/** 처리상태가 바뀌면 새로 불러와야 하는 화면 데이터 */
const AFFECTED_QUERIES = ['expiries', 'expiryCalendar', 'renewalBoard', 'dashboard', 'contracts', 'certifications']

/**
 * 만료 항목(계약·인증 공통)에 대한 작업 묶음.
 * 만료 예정 목록과 만료 캘린더가 같은 동작(처리상태 변경, 갱신 등록)을 쓰도록 한곳에 모았다.
 * 반환한 modals를 화면 어딘가에 그려 두어야 갱신 창이 열린다.
 */
export function useExpiryActions() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [renewContract, setRenewContract] = useState<ContractItem>()
  const [renewCert, setRenewCert] = useState<CertificationItem>()

  /** 관련 목록·대시보드를 모두 새로 고친다. */
  const refresh = () => Promise.all(AFFECTED_QUERIES.map((k) => queryClient.invalidateQueries({ queryKey: [k] })))

  /** 처리상태를 바꾸고 관련 화면을 새로 고친다. */
  const changeStatus = async (row: ExpiryItem, status: RenewalStatus) => {
    try {
      if (row.targetType === 'CONTRACT') await changeContractRenewalStatus(row.targetId, status)
      else await changeCertificationRenewalStatus(row.targetId, status)
      await refresh()
      message.success(`${row.supplierName} · ${row.name} 처리상태를 변경했습니다.`)
    } catch (e) {
      message.error(toProblem(e).detail)
    }
  }

  /** [갱신 등록]: 원본 계약·인증을 불러와 갱신 창을 연다. */
  const renew = async (row: ExpiryItem) => {
    try {
      if (row.targetType === 'CONTRACT') setRenewContract(await fetchContract(row.targetId))
      else setRenewCert(await fetchCertification(row.targetId))
    } catch (e) {
      message.error(toProblem(e).detail)
    }
  }

  /** 갱신 창을 닫고, 목록이 바뀌었을 수 있으니 관련 화면을 새로 고친다. */
  const closeRenew = () => {
    setRenewContract(undefined)
    setRenewCert(undefined)
    refresh()
  }

  const modals = (
    <>
      <ContractFormModal open={!!renewContract} mode="renew" contract={renewContract} onClose={closeRenew} />
      <CertificationFormModal open={!!renewCert} mode="renew" certification={renewCert} onClose={closeRenew} />
    </>
  )

  return { changeStatus, renew, modals }
}
