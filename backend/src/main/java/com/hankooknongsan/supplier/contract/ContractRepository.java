package com.hankooknongsan.supplier.contract;

import java.time.LocalDate;
import java.util.List;

import com.hankooknongsan.supplier.common.RenewalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 계약 저장소 */
public interface ContractRepository extends JpaRepository<Contract, Long> {

    /**
     * 계약을 검색한다.
     * 검색어는 계약명·계약번호·업체명에 부분 일치, 나머지는 일치 조건이며 null이면 무시한다.
     * includeInactive가 false면 갱신되어 비활성이 된 이전 계약은 제외한다.
     */
    @EntityGraph(attributePaths = {"supplier", "owner"})
    @Query("""
            select c from Contract c
            where (:supplierId is null or c.supplier.id = :supplierId)
              and (:keyword = '' or lower(c.title) like lower(concat('%', :keyword, '%'))
                                 or lower(c.contractNumber) like lower(concat('%', :keyword, '%'))
                                 or lower(c.supplier.name) like lower(concat('%', :keyword, '%')))
              and (:ownerId is null or c.owner.id = :ownerId)
              and (:renewalStatus is null or c.renewalStatus = :renewalStatus)
              and (:includeInactive = true or c.active = true)
            """)
    Page<Contract> search(@Param("supplierId") Long supplierId, @Param("keyword") String keyword,
                          @Param("ownerId") Long ownerId, @Param("renewalStatus") RenewalStatus renewalStatus,
                          @Param("includeInactive") boolean includeInactive, Pageable pageable);

    /**
     * 종료일이 기간(from ~ until) 안에 있는 유효 계약을 조회한다. (만료 예정 목록·대시보드용)
     * 하한이 없으면 아주 이른 날짜를 넘긴다 (PostgreSQL에서 날짜 null 파라미터 형식 추론 문제를 피하기 위함).
     */
    @EntityGraph(attributePaths = {"supplier", "owner"})
    @Query("""
            select c from Contract c
            where c.active = true and c.endDate between :from and :until
              and (:ownerId is null or c.owner.id = :ownerId)
              and (:renewalStatus is null or c.renewalStatus = :renewalStatus)
            """)
    List<Contract> findEnding(@Param("from") LocalDate from, @Param("until") LocalDate until,
                              @Param("ownerId") Long ownerId, @Param("renewalStatus") RenewalStatus renewalStatus);

    /**
     * 갱신되어 이력이 된(비활성·갱신완료) 계약 중 만료일이 기간(from ~ until) 안에 있는 것을 조회한다. (갱신 현황판의 완료 칸용)
     */
    @EntityGraph(attributePaths = {"supplier", "owner"})
    @Query("""
            select c from Contract c
            where c.active = false and c.renewalStatus = com.hankooknongsan.supplier.common.RenewalStatus.DONE
              and c.endDate between :from and :until
              and (:ownerId is null or c.owner.id = :ownerId)
            """)
    List<Contract> findRenewed(@Param("from") LocalDate from, @Param("until") LocalDate until,
                                     @Param("ownerId") Long ownerId);

    /** 유효 계약 수를 센다. (대시보드용) */
    long countByActiveTrue();

    /** 같은 업체에 같은 계약번호가 이미 있는지 찾는다. (중복 경고용, 자기 자신은 제외) */
    @Query("""
            select count(c) > 0 from Contract c
            where c.supplier.id = :supplierId and c.contractNumber = :number and c.active = true
              and (:exceptId is null or c.id <> :exceptId)
            """)
    boolean existsDuplicate(@Param("supplierId") Long supplierId, @Param("number") String number,
                            @Param("exceptId") Long exceptId);
}
