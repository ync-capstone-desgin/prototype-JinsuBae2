package com.hankooknongsan.supplier.certification;

import java.util.ArrayList;
import java.util.List;

import com.hankooknongsan.supplier.auth.AppUser;
import com.hankooknongsan.supplier.auth.AppUserRepository;
import com.hankooknongsan.supplier.certification.CertificationDtos.CertTypeItem;
import com.hankooknongsan.supplier.certification.CertificationDtos.CertificationItem;
import com.hankooknongsan.supplier.certification.CertificationDtos.CertificationRequest;
import com.hankooknongsan.supplier.certification.CertificationDtos.Saved;
import com.hankooknongsan.supplier.common.BusinessException;
import com.hankooknongsan.supplier.common.ExpiryPolicy;
import com.hankooknongsan.supplier.common.PageResponse;
import com.hankooknongsan.supplier.common.RenewalStatus;
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
 * 인증 등록·조회·수정·갱신 업무 서비스 (FR-006).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertificationService {

    private final CertificationRepository certifications;
    private final CertTypeRepository certTypes;
    private final SupplierService supplierService;
    private final AppUserRepository users;
    private final ExpiryPolicy policy;
    private final ApplicationEventPublisher events;

    /** 인증 종류 목록을 표시 순서대로 조회한다. */
    public List<CertTypeItem> certTypes() {
        return certTypes.findAllByOrderBySortOrder().stream()
                .map(t -> new CertTypeItem(t.getCode(), t.getName()))
                .toList();
    }

    /** 조건에 맞는 인증 목록을 조회한다. */
    public PageResponse<CertificationItem> search(Long supplierId, String keyword, String certType, Long ownerId,
                                                  RenewalStatus renewalStatus, boolean includeInactive,
                                                  Pageable pageable) {
        return PageResponse.of(certifications.search(supplierId, keyword == null ? "" : keyword.trim(),
                certType == null ? "" : certType, ownerId, renewalStatus, includeInactive, pageable),
                this::toItem);
    }

    /** 인증 한 건을 조회한다. 없으면 404. */
    public CertificationItem get(Long id) {
        return toItem(find(id));
    }

    /** 인증을 등록한다. 같은 종류·번호의 인증이 이미 있으면 저장은 하되 경고를 돌려준다. */
    @Transactional
    public Saved create(CertificationRequest req) {
        Certification x = new Certification(supplierService.find(req.supplierId()));
        List<String> warnings = apply(x, req, null);
        certifications.save(x);
        return new Saved(toItem(x), warnings);
    }

    /** 인증 정보를 수정한다. */
    @Transactional
    public Saved update(Long id, CertificationRequest req) {
        Certification x = find(id);
        List<String> warnings = apply(x, req, id);
        certifications.flush();
        return new Saved(toItem(x), warnings);
    }

    /**
     * 갱신 처리상태(미착수·갱신진행·갱신안함)를 바꾼다. 갱신안함이면 관련 알림을 닫도록 이벤트를 보낸다.
     * 갱신완료는 [갱신] 등록으로만 처리되므로 여기서 지정하면 400, 이미 갱신된 이력 건을 바꾸려 하면 409로 막는다.
     */
    @Transactional
    public CertificationItem changeRenewalStatus(Long id, RenewalStatus status) {
        Certification x = find(id);
        if (status == RenewalStatus.DONE) {
            throw BusinessException.invalid("renewalStatus", "갱신완료는 [갱신]으로 새 인증을 등록해야 처리됩니다.");
        }
        if (!x.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 갱신된 인증은 처리상태를 바꿀 수 없습니다.");
        }
        x.changeRenewalStatus(status);
        if (status.closed()) {
            events.publishEvent(new RenewalClosedEvent(TargetType.CERTIFICATION, id));
        }
        certifications.flush();
        return toItem(x);
    }

    /**
     * 인증을 갱신한다.
     * 새 인증을 등록해 이전 인증과 연결하고, 이전 인증은 "갱신완료"·비활성으로 바꾼다.
     * 이미 갱신된(비활성) 인증은 다시 갱신할 수 없다.
     */
    @Transactional
    public Saved renew(Long id, CertificationRequest req) {
        Certification old = find(id);
        if (!old.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 갱신된 인증입니다.");
        }
        old.markRenewed();
        events.publishEvent(new RenewalClosedEvent(TargetType.CERTIFICATION, old.getId()));
        certifications.flush();
        Certification renewed = new Certification(supplierService.find(req.supplierId()));
        List<String> warnings = apply(renewed, req, null);
        renewed.linkPrevious(old);
        certifications.save(renewed);
        return new Saved(toItem(renewed), warnings);
    }

    /** ID로 인증 엔티티를 찾는다. */
    public Certification find(Long id) {
        return certifications.findById(id).orElseThrow(() -> BusinessException.notFound("인증"));
    }

    /**
     * 요청 값을 검증해 엔티티에 반영하고 경고 목록을 돌려준다.
     * <ul>
     *   <li>만료일이 발급일보다 빠르면 저장하지 않는다</li>
     *   <li>담당자를 지정하지 않으면 업체의 내부 담당자를 그대로 쓴다</li>
     *   <li>같은 종류·인증번호가 이미 있으면 경고한다</li>
     * </ul>
     */
    private List<String> apply(Certification x, CertificationRequest req, Long exceptId) {
        if (req.expiryDate() != null && req.expiryDate().isBefore(req.issuedDate())) {
            throw BusinessException.invalid("expiryDate", "만료일은 발급일보다 빠를 수 없습니다.");
        }
        Supplier supplier = supplierService.find(req.supplierId());
        CertType type = certTypes.findById(req.certTypeCode())
                .orElseThrow(() -> BusinessException.invalid("certTypeCode", "인증 종류를 찾을 수 없습니다."));
        AppUser owner = req.ownerId() != null
                ? users.findById(req.ownerId()).orElseThrow(() -> BusinessException.invalid("ownerId", "담당자를 찾을 수 없습니다."))
                : supplier.getOwner();
        String number = req.certificateNumber().trim();

        List<String> warnings = new ArrayList<>();
        if (certifications.existsDuplicate(type.getCode(), number, exceptId)) {
            warnings.add(type.getName() + " 인증번호 " + number + "이(가) 이미 등록돼 있습니다. 번호를 확인해 주세요.");
        }
        x.update(supplier, type, number, req.issuedDate(), req.expiryDate(), owner,
                blankToNull(req.documentUrl()), blankToNull(req.memo()));
        return warnings;
    }

    /** 인증 엔티티를 오늘 기준 D-day가 포함된 응답 형식으로 변환한다. */
    CertificationItem toItem(Certification x) {
        return CertificationItem.of(x, policy);
    }

    /** 빈 문자열을 null로 바꾸고 앞뒤 공백을 제거한다. */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
