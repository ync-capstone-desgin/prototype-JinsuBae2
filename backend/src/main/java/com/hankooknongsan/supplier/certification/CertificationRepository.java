package com.hankooknongsan.supplier.certification;

import java.time.LocalDate;
import java.util.List;

import com.hankooknongsan.supplier.common.RenewalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 인증 저장소 */
public interface CertificationRepository extends JpaRepository<Certification, Long> {

    /**
     * 인증을 검색한다.
     * 검색어는 인증번호·업체명에 부분 일치, 나머지는 일치 조건이며 빈 값·null이면 무시한다.
     * includeInactive가 false면 갱신되어 비활성이 된 이전 인증은 제외한다.
     */
    @EntityGraph(attributePaths = {"supplier", "owner", "certType"})
    @Query("""
            select x from Certification x
            where (:supplierId is null or x.supplier.id = :supplierId)
              and (:keyword = '' or lower(x.certificateNumber) like lower(concat('%', :keyword, '%'))
                                 or lower(x.supplier.name) like lower(concat('%', :keyword, '%')))
              and (:certType = '' or x.certType.code = :certType)
              and (:ownerId is null or x.owner.id = :ownerId)
              and (:renewalStatus is null or x.renewalStatus = :renewalStatus)
              and (:includeInactive = true or x.active = true)
            """)
    Page<Certification> search(@Param("supplierId") Long supplierId, @Param("keyword") String keyword,
                               @Param("certType") String certType, @Param("ownerId") Long ownerId,
                               @Param("renewalStatus") RenewalStatus renewalStatus,
                               @Param("includeInactive") boolean includeInactive, Pageable pageable);

    /**
     * 만료일이 기간(from ~ until) 안에 있는 유효 인증을 조회한다. (만료 예정 목록·대시보드용)
     */
    @EntityGraph(attributePaths = {"supplier", "owner", "certType"})
    @Query("""
            select x from Certification x
            where x.active = true and x.expiryDate between :from and :until
              and (:ownerId is null or x.owner.id = :ownerId)
              and (:renewalStatus is null or x.renewalStatus = :renewalStatus)
            """)
    List<Certification> findExpiring(@Param("from") LocalDate from, @Param("until") LocalDate until,
                                     @Param("ownerId") Long ownerId,
                                     @Param("renewalStatus") RenewalStatus renewalStatus);

    /** 만료일이 비어 있는 유효 인증을 조회한다. ("확인필요" 대상) */
    @EntityGraph(attributePaths = {"supplier", "owner", "certType"})
    @Query("""
            select x from Certification x
            where x.active = true and x.expiryDate is null
              and (:ownerId is null or x.owner.id = :ownerId)
              and (:renewalStatus is null or x.renewalStatus = :renewalStatus)
            """)
    List<Certification> findWithoutExpiry(@Param("ownerId") Long ownerId,
                                          @Param("renewalStatus") RenewalStatus renewalStatus);

    /**
     * 갱신되어 이력이 된(비활성·갱신완료) 인증 중 만료일이 기간(from ~ until) 안에 있는 것을 조회한다. (갱신 현황판의 완료 칸용)
     */
    @EntityGraph(attributePaths = {"supplier", "owner"})
    @Query("""
            select x from Certification x
            where x.active = false and x.renewalStatus = com.hankooknongsan.supplier.common.RenewalStatus.DONE
              and x.expiryDate between :from and :until
              and (:ownerId is null or x.owner.id = :ownerId)
            """)
    List<Certification> findRenewed(@Param("from") LocalDate from, @Param("until") LocalDate until,
                                     @Param("ownerId") Long ownerId);

    /** 유효 인증 수를 센다. (대시보드용) */
    long countByActiveTrue();

    /** 같은 인증 종류·인증번호가 이미 등록돼 있는지 찾는다. (중복 경고용, 자기 자신은 제외) */
    @Query("""
            select count(x) > 0 from Certification x
            where x.certType.code = :certType and x.certificateNumber = :number and x.active = true
              and (:exceptId is null or x.id <> :exceptId)
            """)
    boolean existsDuplicate(@Param("certType") String certType, @Param("number") String number,
                            @Param("exceptId") Long exceptId);
}
