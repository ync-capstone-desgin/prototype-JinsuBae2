package com.hankooknongsan.supplier.expiry;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hankooknongsan.supplier.common.BusinessException;

import com.hankooknongsan.supplier.common.ExcelExporter;
import com.hankooknongsan.supplier.common.ExcelExporter.Column;
import com.hankooknongsan.supplier.common.RenewalStatus;
import com.hankooknongsan.supplier.common.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 만료 예정 목록 API (FR-008).
 * 기간·대상 종류·담당자·갱신 처리상태로 계약과 인증을 함께 조회한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/expiries")
public class ExpiryController {

    /** 캘린더 한 번에 조회할 수 있는 최대 일수 (한 달 화면은 최대 6주 = 42일) */
    static final int CALENDAR_MAX_DAYS = 62;

    private final ExpiryService service;

    /** 만료 예정 목록을 조회한다. 기본 구간은 30일 이내. */
    @GetMapping
    public List<ExpiryItem> find(@RequestParam(defaultValue = "D30") ExpiryPeriod period,
                                 @RequestParam(required = false) TargetType targetType,
                                 @RequestParam(required = false) Long ownerId,
                                 @RequestParam(required = false) RenewalStatus renewalStatus) {
        return service.find(period, targetType, ownerId, renewalStatus);
    }

    /**
     * 만료 캘린더용으로 기간 안에 만료되는 계약·인증을 날짜순으로 조회한다.
     * 화면이 보이는 달력 범위(최대 62일)만 요청하도록 제한해 과도한 조회를 막는다.
     */
    @GetMapping("/calendar")
    public List<ExpiryItem> calendar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) TargetType targetType,
                                     @RequestParam(required = false) Long ownerId,
                                     @RequestParam(required = false) RenewalStatus renewalStatus) {
        if (to.isBefore(from)) {
            throw BusinessException.invalid("to", "종료일이 시작일보다 빠릅니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= CALENDAR_MAX_DAYS) {
            throw BusinessException.invalid("to", "캘린더는 최대 " + CALENDAR_MAX_DAYS + "일까지 조회할 수 있습니다.");
        }
        return service.findBetween(from, to, targetType, ownerId, renewalStatus);
    }

    /**
     * 갱신 현황판용 카드 목록을 조회한다.
     * 조치 대상(만료 + 만료예정 기간 이내)과 최근 갱신 완료 건을 급한 순으로 돌려주며, 칸 구분은 갱신 처리상태로 한다.
     */
    @GetMapping("/board")
    public List<ExpiryItem> board(@RequestParam(required = false) TargetType targetType,
                                  @RequestParam(required = false) Long ownerId) {
        return service.findBoard(targetType, ownerId);
    }

    /** 현재 조건의 만료 예정 목록을 엑셀 파일로 내려받는다. */
    @GetMapping("/excel")
    public ResponseEntity<byte[]> excel(@RequestParam(defaultValue = "D30") ExpiryPeriod period,
                                        @RequestParam(required = false) TargetType targetType,
                                        @RequestParam(required = false) Long ownerId,
                                        @RequestParam(required = false) RenewalStatus renewalStatus) {
        List<Column<ExpiryItem>> columns = List.of(
                new Column<>("D-day", ExpiryController::dday),
                new Column<>("유형", e -> e.targetType().label()),
                new Column<>("업체명", ExpiryItem::supplierName),
                new Column<>("대상명", ExpiryItem::name),
                new Column<>("번호", ExpiryItem::number),
                new Column<>("만료일", e -> e.expiryDate() == null ? "없음" : e.expiryDate()),
                new Column<>("만료상태", e -> e.expiryStatus().label()),
                new Column<>("갱신 처리상태", e -> e.renewalStatus().label()),
                new Column<>("담당자", ExpiryItem::ownerName));
        return ExcelExporter.export("만료예정_" + period.label(), columns,
                service.find(period, targetType, ownerId, renewalStatus));
    }

    /** 남은 일수를 화면과 같은 D-day 표기로 바꾼다. 예: D-7, D-DAY, 만료+3 */
    static String dday(ExpiryItem e) {
        Integer d = e.daysLeft();
        if (d == null) {
            return "-";
        }
        return d > 0 ? "D-" + d : d == 0 ? "D-DAY" : "만료+" + (-d);
    }
}
