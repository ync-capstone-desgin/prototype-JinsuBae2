package com.hankooknongsan.supplier.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.hankooknongsan.supplier.TestcontainersConfig;
import com.hankooknongsan.supplier.auth.AppUser;
import com.hankooknongsan.supplier.auth.AppUserRepository;
import com.hankooknongsan.supplier.auth.Role;
import com.hankooknongsan.supplier.certification.CertTypeRepository;
import com.hankooknongsan.supplier.certification.Certification;
import com.hankooknongsan.supplier.certification.CertificationRepository;
import com.hankooknongsan.supplier.common.AppClock;
import com.hankooknongsan.supplier.common.RenewalStatus;
import com.hankooknongsan.supplier.common.TargetType;
import com.hankooknongsan.supplier.contract.Contract;
import com.hankooknongsan.supplier.contract.ContractRepository;
import com.hankooknongsan.supplier.dashboard.DashboardService;
import com.hankooknongsan.supplier.dashboard.DashboardSummary;
import com.hankooknongsan.supplier.supplier.Supplier;
import com.hankooknongsan.supplier.supplier.SupplierRepository;
import com.hankooknongsan.supplier.supplier.SupplierStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 만료 예정 조회·만료 캘린더·갱신 현황판·대시보드 통합 테스트 (VT-005, VT-008, VT-009).
 * 기준일을 2030-01-15로 옮기고, 테스트 전용 담당자를 만들어 다른 테스트 데이터와 섞이지 않게 담당자로 걸러 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@WithMockUser(username = "manager1", roles = "MANAGER")
class ExpiryApiTest {

    static final LocalDate TODAY = LocalDate.of(2030, 1, 15);

    @Autowired MockMvc mvc;
    @Autowired AppClock clock;
    @Autowired ExpiryService expiryService;
    @Autowired DashboardService dashboardService;
    @Autowired AppUserRepository users;
    @Autowired SupplierRepository suppliers;
    @Autowired ContractRepository contracts;
    @Autowired CertificationRepository certifications;
    @Autowired CertTypeRepository certTypes;

    Long ownerId;
    Supplier supplier;

    /**
     * 기준일을 고정하고, 경계일 계약 9건(-1, 0, 7, 8, 30, 31, 90, 91일, 무기한)과
     * 만료일 없는 인증 1건, 3일 뒤 만료 인증 1건을 테스트 담당자 앞으로 만든다.
     */
    @BeforeEach
    void setUp() {
        clock.setToday(TODAY);
        AppUser owner = users.save(new AppUser("expiry-" + System.nanoTime(), "{noop}x", "테스트담당", null, Role.MANAGER));
        ownerId = owner.getId();
        Supplier s = new Supplier("만료테스트업체", SupplierStatus.ACTIVE);
        s.update("만료테스트업체", null, SupplierStatus.ACTIVE, null, null, null, owner, null);
        suppliers.save(s);
        supplier = s;

        for (Integer offset : new Integer[] {-1, 0, 7, 8, 30, 31, 90, 91, null}) {
            Contract c = new Contract(s);
            LocalDate end = offset == null ? null : TODAY.plusDays(offset);
            c.update(s, "CT" + offset, "계약" + offset, TODAY.minusYears(1), end, false, owner, null, null);
            contracts.save(c);
        }
        var gap = certTypes.findById("GAP").orElseThrow();
        Certification noExpiry = new Certification(s);
        noExpiry.update(s, gap, "NO-EXP", TODAY.minusYears(1), null, owner, null, null);
        Certification soon = new Certification(s);
        soon.update(s, gap, "SOON", TODAY.minusYears(1), TODAY.plusDays(3), owner, null, null);
        certifications.saveAll(List.of(noExpiry, soon));
    }

    /** 다른 테스트에 영향이 없도록 기준일을 실제 날짜로 되돌린다. */
    @AfterEach
    void tearDown() {
        clock.setToday(null);
    }

    /** 구간의 남은 일수 목록을 조회한다. */
    List<Integer> daysOf(ExpiryPeriod period) {
        return expiryService.find(period, null, ownerId, null).stream().map(ExpiryItem::daysLeft).toList();
    }

    /** VT-005: 7·30·90일 구간은 오늘부터 경계일까지 포함하고, 하루 넘는 항목은 제외한다. 급한 순으로 정렬된다. */
    @Test
    void 구간별로_경계일까지_포함하고_급한_순으로_정렬한다() {
        assertThat(daysOf(ExpiryPeriod.D7)).containsExactly(0, 3, 7);
        assertThat(daysOf(ExpiryPeriod.D30)).containsExactly(0, 3, 7, 8, 30);
        assertThat(daysOf(ExpiryPeriod.D90)).containsExactly(0, 3, 7, 8, 30, 31, 90);
        assertThat(daysOf(ExpiryPeriod.EXPIRED)).containsExactly(-1);
    }

    /** 만료일 없는 인증은 확인필요 구간에만 나오고, 무기한 계약은 어느 구간에도 나오지 않는다. */
    @Test
    void 만료일_없는_인증만_확인필요로_분류한다() {
        List<ExpiryItem> unknown = expiryService.find(ExpiryPeriod.UNKNOWN, null, ownerId, null);
        assertThat(unknown).extracting(ExpiryItem::number).containsExactly("NO-EXP");
    }

    /** VT-008: 대상 종류 필터를 적용하면 해당 종류만 나온다. */
    @Test
    void 대상_종류로_거를_수_있다() {
        List<ExpiryItem> certs = expiryService.find(ExpiryPeriod.D30, TargetType.CERTIFICATION, ownerId, null);
        assertThat(certs).extracting(ExpiryItem::number).containsExactly("SOON");
    }

    /** VT-009: 대시보드 카드 숫자는 만료 예정 목록의 건수와 같다. */
    @Test
    void 대시보드_집계는_목록_건수와_일치한다() {
        DashboardSummary summary = dashboardService.summary(ownerId);
        assertThat(summary.today()).isEqualTo(TODAY);
        assertThat(summary.expired()).isEqualTo(expiryService.find(ExpiryPeriod.EXPIRED, null, null, null).size());
        assertThat(summary.within7()).isEqualTo(expiryService.find(ExpiryPeriod.D7, null, null, null).size());
        assertThat(summary.within30()).isEqualTo(expiryService.find(ExpiryPeriod.D30, null, null, null).size());
        assertThat(summary.within90()).isEqualTo(expiryService.find(ExpiryPeriod.D90, null, null, null).size());
        assertThat(summary.unknown()).isEqualTo(expiryService.find(ExpiryPeriod.UNKNOWN, null, null, null).size());
        // 테스트 담당자 몫: 만료 1 + 90일 이내 7 = 8건
        assertThat(summary.mine()).isEqualTo(8);
        assertThat(summary.urgent()).hasSizeLessThanOrEqualTo(10);
    }

    /** 만료 캘린더: 기간 양 끝 날짜를 포함하고, 이미 지난 날짜와 인증도 함께 날짜순으로 나온다. 무기한·만료일 없음은 제외한다. */
    @Test
    void 캘린더는_기간_안의_만료일만_날짜순으로_보여준다() {
        List<ExpiryItem> items = expiryService.findBetween(TODAY.minusDays(1), TODAY.plusDays(8), null, ownerId, null);
        assertThat(items).extracting(ExpiryItem::daysLeft).containsExactly(-1, 0, 3, 7, 8);
        assertThat(items).extracting(ExpiryItem::targetType).contains(TargetType.CERTIFICATION);
    }

    /** 만료 캘린더: 종료일이 시작일보다 빠르거나 62일 이상을 한 번에 요청하면 400으로 막는다. */
    @Test
    void 캘린더_조회_기간이_잘못되면_막는다() throws Exception {
        mvc.perform(get("/api/expiries/calendar").param("from", "2030-02-01").param("to", "2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        mvc.perform(get("/api/expiries/calendar").param("from", "2030-01-01").param("to", "2030-03-15"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/expiries/calendar").param("from", "2029-12-28").param("to", "2030-02-07"))
                .andExpect(status().isOk());
    }

    /**
     * 갱신 현황판: 조치 대상(만료 + 90일 이내) 유효 건과 최근 30일 안에 갱신된 이력 건만 나온다.
     * 91일 뒤 만료, 무기한, 만료일 없음, 오래전에 갱신된 건은 빠진다.
     */
    @Test
    void 현황판은_조치_대상과_최근_갱신_완료_건만_보여준다() {
        Supplier s = supplier;
        AppUser owner = users.findById(ownerId).orElseThrow();
        for (int offset : new int[] {-10, -40}) {
            Contract renewed = new Contract(s);
            renewed.update(s, "RN" + offset, "갱신된계약" + offset, TODAY.minusYears(1), TODAY.plusDays(offset), false, owner, null, null);
            renewed.markRenewed();
            contracts.save(renewed);
        }

        List<ExpiryItem> board = expiryService.findBoard(null, ownerId);
        assertThat(board).extracting(ExpiryItem::daysLeft).containsExactly(-10, -1, 0, 3, 7, 8, 30, 31, 90);
        assertThat(board).filteredOn(i -> i.daysLeft() == -10)
                .extracting(ExpiryItem::renewalStatus).containsExactly(RenewalStatus.DONE);
    }
}
