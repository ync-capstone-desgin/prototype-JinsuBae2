package com.hankooknongsan.supplier.contract;

import java.util.ArrayList;
import java.util.List;

import com.hankooknongsan.supplier.auth.AppUser;
import com.hankooknongsan.supplier.auth.AppUserRepository;
import com.hankooknongsan.supplier.common.BusinessException;
import com.hankooknongsan.supplier.common.ExpiryPolicy;
import com.hankooknongsan.supplier.common.PageResponse;
import com.hankooknongsan.supplier.common.RenewalStatus;
import com.hankooknongsan.supplier.contract.ContractDtos.ContractItem;
import com.hankooknongsan.supplier.contract.ContractDtos.ContractRequest;
import com.hankooknongsan.supplier.contract.ContractDtos.Saved;
import com.hankooknongsan.supplier.supplier.Supplier;
import com.hankooknongsan.supplier.supplier.SupplierService;
import lombok.RequiredArgsConstructor;
import com.hankooknongsan.supplier.common.RenewalClosedEvent;
import com.hankooknongsan.supplier.common.TargetType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 등록·조회·수정·갱신 업무 서비스 (FR-005).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractService {

    private final ContractRepository contracts;
    private final SupplierService supplierService;
    private final AppUserRepository users;
    private final ExpiryPolicy policy;
    private final ApplicationEventPublisher events;

    /** 조건에 맞는 계약 목록을 조회한다. */
    public PageResponse<ContractItem> search(Long supplierId, String keyword, Long ownerId,
                                             RenewalStatus renewalStatus, boolean includeInactive, Pageable pageable) {
        return PageResponse.of(contracts.search(supplierId, keyword == null ? "" : keyword.trim(), ownerId,
                renewalStatus, includeInactive, pageable), this::toItem);
    }

    /** 계약 한 건을 조회한다. 없으면 404. */
    public ContractItem get(Long id) {
        return toItem(find(id));
    }

    /** 계약을 등록한다. 계약번호가 같은 업체에 이미 있으면 저장은 하되 경고를 돌려준다. */
    @Transactional
    public Saved create(ContractRequest req) {
        Contract c = new Contract(supplierService.find(req.supplierId()));
        List<String> warnings = apply(c, req, null);
        contracts.save(c);
        return new Saved(toItem(c), warnings);
    }

    /** 계약 정보를 수정한다. */
    @Transactional
    public Saved update(Long id, ContractRequest req) {
        Contract c = find(id);
        List<String> warnings = apply(c, req, id);
        contracts.flush();
        return new Saved(toItem(c), warnings);
    }

    /**
     * 갱신 처리상태(미착수·갱신진행·갱신안함)를 바꾼다. 갱신안함이면 관련 알림을 닫도록 이벤트를 보낸다.
     * 갱신완료는 [갱신] 등록으로만 처리되므로 여기서 지정하면 400, 이미 갱신된 이력 건을 바꾸려 하면 409로 막는다.
     */
    @Transactional
    public ContractItem changeRenewalStatus(Long id, RenewalStatus status) {
        Contract c = find(id);
        if (status == RenewalStatus.DONE) {
            throw BusinessException.invalid("renewalStatus", "갱신완료는 [갱신]으로 새 계약을 등록해야 처리됩니다.");
        }
        if (!c.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 갱신된 계약은 처리상태를 바꿀 수 없습니다.");
        }
        c.changeRenewalStatus(status);
        if (status.closed()) {
            events.publishEvent(new RenewalClosedEvent(TargetType.CONTRACT, id));
        }
        contracts.flush();
        return toItem(c);
    }

    /**
     * 계약을 갱신한다.
     * 새 계약을 등록해 이전 계약과 연결하고, 이전 계약은 "갱신완료"·비활성으로 바꾼다.
     * 이미 갱신된(비활성) 계약은 다시 갱신할 수 없다.
     */
    @Transactional
    public Saved renew(Long id, ContractRequest req) {
        Contract old = find(id);
        if (!old.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 갱신된 계약입니다.");
        }
        old.markRenewed();
        events.publishEvent(new RenewalClosedEvent(TargetType.CONTRACT, old.getId()));
        contracts.flush();
        Contract renewed = new Contract(supplierService.find(req.supplierId()));
        List<String> warnings = apply(renewed, req, null);
        renewed.linkPrevious(old);
        contracts.save(renewed);
        return new Saved(toItem(renewed), warnings);
    }

    /** ID로 계약 엔티티를 찾는다. */
    public Contract find(Long id) {
        return contracts.findById(id).orElseThrow(() -> BusinessException.notFound("계약"));
    }

    /**
     * 요청 값을 검증해 엔티티에 반영하고 경고 목록을 돌려준다.
     * <ul>
     *   <li>종료일이 시작일보다 빠르면 저장하지 않는다 (VT-003)</li>
     *   <li>담당자를 지정하지 않으면 업체의 내부 담당자를 그대로 쓴다</li>
     *   <li>같은 업체에 같은 계약번호가 있으면 경고한다</li>
     * </ul>
     */
    private List<String> apply(Contract c, ContractRequest req, Long exceptId) {
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw BusinessException.invalid("endDate", "종료일은 시작일보다 빠를 수 없습니다.");
        }
        Supplier supplier = supplierService.find(req.supplierId());
        AppUser owner = req.ownerId() != null
                ? users.findById(req.ownerId()).orElseThrow(() -> BusinessException.invalid("ownerId", "담당자를 찾을 수 없습니다."))
                : supplier.getOwner();
        String number = req.contractNumber().trim();

        List<String> warnings = new ArrayList<>();
        if (contracts.existsDuplicate(supplier.getId(), number, exceptId)) {
            warnings.add("같은 업체에 계약번호 " + number + "이(가) 이미 있습니다. 번호를 확인해 주세요.");
        }
        c.update(supplier, number, req.title().trim(), req.startDate(), req.endDate(), Boolean.TRUE.equals(req.autoRenew()), owner,
                blankToNull(req.documentUrl()), blankToNull(req.memo()));
        return warnings;
    }

    /** 계약 엔티티를 오늘 기준 D-day가 포함된 응답 형식으로 변환한다. */
    ContractItem toItem(Contract c) {
        return ContractItem.of(c, policy);
    }

    /** 빈 문자열을 null로 바꾸고 앞뒤 공백을 제거한다. */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
