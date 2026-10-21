package com.hankooknongsan.supplier.contract;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hankooknongsan.supplier.TestcontainersConfig;
import com.hankooknongsan.supplier.supplier.Supplier;
import com.hankooknongsan.supplier.supplier.SupplierRepository;
import com.hankooknongsan.supplier.supplier.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 계약·인증 API 통합 테스트.
 * VT-003(종료일 < 시작일 차단), VT-007(업체별 조회), 갱신 이력 연결, 중복 경고를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@WithMockUser(username = "manager1", roles = "MANAGER")
class ContractApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SupplierRepository suppliers;

    Long supplierA;
    Long supplierB;

    /** 테스트마다 서로 다른 협력업체 두 곳을 새로 만든다. */
    @BeforeEach
    void setUp() {
        supplierA = suppliers.save(new Supplier("테스트A", SupplierStatus.ACTIVE)).getId();
        supplierB = suppliers.save(new Supplier("테스트B", SupplierStatus.ACTIVE)).getId();
    }

    /** 계약 등록 요청을 보낸다. */
    ResultActions postContract(Long supplierId, String number, String start, String end) throws Exception {
        String endJson = end == null ? "null" : "\"" + end + "\"";
        return mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content("""
                {"supplierId":%d,"contractNumber":"%s","title":"테스트 계약","startDate":"%s","endDate":%s}"""
                .formatted(supplierId, number, start, endJson)));
    }

    /** 응답 본문에서 등록된 항목의 id를 꺼낸다. */
    static long idOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*?\"item\":\\{\"id\":(\\d+).*", "$1"));
    }

    /** VT-003: 종료일이 시작일보다 빠르면 저장하지 않고 종료일 칸 오류를 알려준다. */
    @Test
    void 종료일이_시작일보다_빠르면_저장하지_않는다() throws Exception {
        postContract(supplierA, "CT-1", "2026-10-01", "2026-09-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
        mvc.perform(get("/api/contracts").param("supplierId", supplierA.toString()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** 종료일이 비어 있으면 무기한 계약으로 저장된다. */
    @Test
    void 종료일이_없으면_무기한_계약으로_저장된다() throws Exception {
        postContract(supplierA, "CT-1", "2026-10-01", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.endDate").doesNotExist())
                .andExpect(jsonPath("$.item.autoRenew").value(false));
    }

    /** VT-007: 업체별 조회 시 해당 업체의 계약만 보인다. */
    @Test
    void 업체별_조회는_해당_업체의_계약만_보여준다() throws Exception {
        postContract(supplierA, "CT-A1", "2026-01-01", "2026-12-31").andExpect(status().isCreated());
        postContract(supplierA, "CT-A2", "2026-01-01", "2027-12-31").andExpect(status().isCreated());
        postContract(supplierB, "CT-B1", "2026-01-01", "2026-12-31").andExpect(status().isCreated());

        mvc.perform(get("/api/contracts").param("supplierId", supplierA.toString()))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].supplierName", everyItem(is("테스트A"))));
    }

    /** 같은 업체에 같은 계약번호로 등록하면 저장은 되지만 경고가 붙는다. */
    @Test
    void 계약번호가_중복되면_경고한다() throws Exception {
        postContract(supplierA, "CT-DUP", "2026-01-01", "2026-12-31").andExpect(jsonPath("$.warnings", empty()));
        postContract(supplierA, "CT-DUP", "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings", hasSize(1)));
    }

    /** 갱신하면 새 계약이 이전 계약과 연결되고, 이전 계약은 갱신완료·비활성이 되어 기본 목록에서 빠진다. */
    @Test
    void 갱신하면_이전_계약이_비활성화되고_이력이_연결된다() throws Exception {
        long oldId = idOf(postContract(supplierA, "CT-OLD", "2025-01-01", "2025-12-31"));

        mvc.perform(post("/api/contracts/" + oldId + "/renew").contentType(MediaType.APPLICATION_JSON).content("""
                        {"supplierId":%d,"contractNumber":"CT-NEW","title":"갱신 계약","startDate":"2026-01-01","endDate":"2026-12-31"}"""
                        .formatted(supplierA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.prevId").value(oldId));

        mvc.perform(get("/api/contracts/" + oldId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.renewalStatus").value("DONE"));
        mvc.perform(get("/api/contracts").param("supplierId", supplierA.toString()))
                .andExpect(jsonPath("$.content[*].contractNumber", contains("CT-NEW")));
        mvc.perform(get("/api/contracts").param("supplierId", supplierA.toString()).param("includeInactive", "true"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** 처리상태 API로는 갱신완료를 지정할 수 없고, 이미 갱신된 이력 계약의 처리상태도 바꿀 수 없다. (갱신 현황판 끌어 놓기 보호) */
    @Test
    void 갱신완료_직접_지정과_이력_계약_상태_변경을_막는다() throws Exception {
        long id = idOf(postContract(supplierA, "CT-ST", "2025-01-01", "2025-12-31"));
        mvc.perform(patch("/api/contracts/" + id + "/renewal-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renewalStatus\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("renewalStatus"));
        mvc.perform(patch("/api/contracts/" + id + "/renewal-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renewalStatus\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/contracts/" + id + "/renew").contentType(MediaType.APPLICATION_JSON).content("""
                        {"supplierId":%d,"contractNumber":"CT-ST2","title":"갱신 계약","startDate":"2026-01-01","endDate":"2026-12-31"}"""
                        .formatted(supplierA)))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/contracts/" + id + "/renewal-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renewalStatus\":\"NONE\"}"))
                .andExpect(status().isConflict());
    }

    /** 인증도 만료일이 발급일보다 빠르면 저장하지 않는다. */
    @Test
    void 인증_만료일이_발급일보다_빠르면_저장하지_않는다() throws Exception {
        mvc.perform(post("/api/certifications").contentType(MediaType.APPLICATION_JSON).content("""
                        {"supplierId":%d,"certTypeCode":"GAP","certificateNumber":"GAP-1","issuedDate":"2026-10-01","expiryDate":"2026-01-01"}"""
                        .formatted(supplierA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expiryDate"));
    }

    /** 업체 목록에 유효 계약 수와 만료·임박 건수가 함께 나온다. */
    @Test
    void 업체_목록에_계약수와_만료건수가_나온다() throws Exception {
        postContract(supplierB, "CT-EXPIRED", "2020-01-01", "2020-12-31");
        postContract(supplierB, "CT-FAR", "2020-01-01", "2099-12-31");

        mvc.perform(get("/api/suppliers/" + supplierB))
                .andExpect(jsonPath("$.contractCount").value(2))
                .andExpect(jsonPath("$.expiringCount").value(1));
    }
}
