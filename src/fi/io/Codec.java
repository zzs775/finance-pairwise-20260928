package fi.io;

import fi.AccrualPeriod;
import fi.AccrualSegment;
import fi.BondTerms;
import fi.BusinessDayConvention;
import fi.CashFlow;
import fi.CashFlowSchedule;
import fi.CashFlowType;
import fi.DayCount;
import fi.ErrorCode;
import fi.Frequency;
import fi.HolidayCalendar;
import fi.NotionalStep;
import fi.RateStep;
import fi.ScheduleException;
import fi.StubType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Versioned canonical JSON codec.
 *
 * Encoding is deterministic: keys sorted, arrays keep schedule order, no
 * insignificant whitespace. Unknown fields are rejected on decode; fields
 * missing from older payloads are filled from the frozen defaults table.
 */
public final class Codec {

    public static final int VERSION = 1;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "format", "version", "terms", "periods", "cashFlows");
    private static final Set<String> TERMS_FIELDS = Set.of(
            "id", "valueDate", "maturityDate", "frequency", "stubType",
            "dayCount", "eom", "convention", "exDividendBusinessDays",
            "rateSteps", "notionalSteps", "calendar");
    private static final Set<String> PERIOD_FIELDS = Set.of(
            "index", "unadjustedStart", "unadjustedEnd", "paymentDate",
            "notional", "annualRate", "yearFraction", "couponAmount", "segments");
    private static final Set<String> SEGMENT_FIELDS = Set.of(
            "start", "end", "notional", "annualRate", "yearFraction", "couponAmount");
    private static final Set<String> CASHFLOW_FIELDS = Set.of(
            "paymentDate", "type", "amount", "periodIndex");
    private static final Set<String> CALENDAR_FIELDS = Set.of("name", "holidays");
    private static final Set<String> RATE_FIELDS = Set.of("startDate", "annualRate");
    private static final Set<String> NOTIONAL_FIELDS =
            Set.of("startDate", "outstandingNotional");

    private Codec() {
    }

    public static byte[] encode(CashFlowSchedule schedule) {
        Map<String, Object> root = new TreeMap<>();
        root.put("format", "fixed-income-schedule");
        root.put("version", VERSION);
        root.put("terms", encodeTerms(schedule.terms()));

        List<Object> periods = new ArrayList<>();
        for (AccrualPeriod period : schedule.periods()) {
            Map<String, Object> node = new TreeMap<>();
            node.put("index", period.index());
            node.put("unadjustedStart", period.unadjustedStart().toString());
            node.put("unadjustedEnd", period.unadjustedEnd().toString());
            node.put("paymentDate", period.paymentDate().toString());
            node.put("notional", period.notional());
            node.put("annualRate", period.annualRate());
            node.put("yearFraction", period.yearFraction());
            node.put("couponAmount", period.couponAmount());
            List<Object> segments = new ArrayList<>();
            for (AccrualSegment segment : period.segments()) {
                Map<String, Object> segmentNode = new TreeMap<>();
                segmentNode.put("start", segment.start().toString());
                segmentNode.put("end", segment.end().toString());
                segmentNode.put("notional", segment.notional());
                segmentNode.put("annualRate", segment.annualRate());
                segmentNode.put("yearFraction", segment.yearFraction());
                segmentNode.put("couponAmount", segment.couponAmount());
                segments.add(segmentNode);
            }
            node.put("segments", segments);
            periods.add(node);
        }
        root.put("periods", periods);

        List<Object> cashFlows = new ArrayList<>();
        for (CashFlow cashFlow : schedule.cashFlows()) {
            Map<String, Object> node = new TreeMap<>();
            node.put("paymentDate", cashFlow.paymentDate().toString());
            node.put("type", cashFlow.type().name());
            node.put("amount", cashFlow.amount());
            node.put("periodIndex", cashFlow.periodIndex());
            cashFlows.add(node);
        }
        root.put("cashFlows", cashFlows);

        return Json.writeCanonicalBytes(root);
    }

    public static CashFlowSchedule decode(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "root json node must be an object");
        }
        Object format = root.get("format");
        require("fixed-income-schedule".equals(format), "bad format marker");
        int version = intField(root, "version");
        if (version < 1 || version > VERSION) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "unsupported serialization version: " + version);
        }
        rejectUnknown(root, ROOT_FIELDS, "root");

        BondTerms terms = decodeTerms(asMap(root.get("terms")), version);
        List<AccrualPeriod> periods = new ArrayList<>();
        for (Object node : asList(root.get("periods"))) {
            periods.add(decodePeriod(asMap(node)));
        }
        List<CashFlow> cashFlows = new ArrayList<>();
        for (Object node : asList(root.get("cashFlows"))) {
            cashFlows.add(decodeCashFlow(asMap(node)));
        }
        return new CashFlowSchedule(terms, List.copyOf(periods), List.copyOf(cashFlows));
    }

    private static Map<String, Object> encodeTerms(BondTerms terms) {
        Map<String, Object> node = new TreeMap<>();
        node.put("id", terms.id());
        node.put("valueDate", terms.valueDate().toString());
        node.put("maturityDate", terms.maturityDate().toString());
        node.put("frequency", terms.frequency().name());
        node.put("stubType", terms.stubType().name());
        node.put("dayCount", terms.dayCount().name());
        node.put("eom", terms.eom());
        node.put("convention", terms.convention().name());
        node.put("exDividendBusinessDays", terms.exDividendBusinessDays());

        List<Object> rates = new ArrayList<>();
        for (RateStep step : terms.rateSteps()) {
            Map<String, Object> rate = new TreeMap<>();
            rate.put("startDate", step.startDate().toString());
            rate.put("annualRate", step.annualRate());
            rates.add(rate);
        }
        node.put("rateSteps", rates);

        List<Object> notionals = new ArrayList<>();
        for (NotionalStep step : terms.notionalSteps()) {
            Map<String, Object> notional = new TreeMap<>();
            notional.put("startDate", step.startDate().toString());
            notional.put("outstandingNotional", step.outstandingNotional());
            notionals.add(notional);
        }
        node.put("notionalSteps", notionals);

        Map<String, Object> calendar = new TreeMap<>();
        calendar.put("name", terms.calendar().name());
        List<Object> holidayDates = new ArrayList<>();
        for (LocalDate holiday : terms.calendar().holidays()) {
            holidayDates.add(holiday.toString());
        }
        calendar.put("holidays", holidayDates);
        node.put("calendar", calendar);
        return node;
    }

    private static BondTerms decodeTerms(Map<?, ?> node, int version) {
        rejectUnknown(node, TERMS_FIELDS, "terms");

        StubType stubType = StubType.valueOf(stringField(node, "stubType"));
        BusinessDayConvention convention =
                BusinessDayConvention.valueOf(stringField(node, "convention"));
        int exDays = intField(node, "exDividendBusinessDays", frozenDefault(version));
        boolean eom = boolField(node, "eom", false);

        Map<?, ?> calendarNode = asMap(node.get("calendar"));
        rejectUnknown(calendarNode, CALENDAR_FIELDS, "calendar");
        Set<LocalDate> holidays = new LinkedHashSet<>();
        for (Object dateNode : asList(calendarNode.get("holidays"))) {
            holidays.add(LocalDate.parse(String.valueOf(dateNode)));
        }
        HolidayCalendar calendar =
                HolidayCalendar.of(String.valueOf(calendarNode.get("name")),
                        holidays.toArray(LocalDate[]::new));

        List<RateStep> rates = new ArrayList<>();
        for (Object rateNode : asList(node.get("rateSteps"))) {
            Map<?, ?> rateMap = asMap(rateNode);
            rejectUnknown(rateMap, RATE_FIELDS, "rateStep");
            rates.add(new RateStep(
                    LocalDate.parse(stringField(rateMap, "startDate")),
                    decimalField(rateMap, "annualRate")));
        }

        List<NotionalStep> notionals = new ArrayList<>();
        for (Object notionalNode : asList(node.get("notionalSteps"))) {
            Map<?, ?> notionalMap = asMap(notionalNode);
            rejectUnknown(notionalMap, NOTIONAL_FIELDS, "notionalStep");
            notionals.add(new NotionalStep(
                    LocalDate.parse(stringField(notionalMap, "startDate")),
                    decimalField(notionalMap, "outstandingNotional")));
        }

        return new BondTerms(
                String.valueOf(node.get("id")),
                LocalDate.parse(stringField(node, "valueDate")),
                LocalDate.parse(stringField(node, "maturityDate")),
                Frequency.valueOf(stringField(node, "frequency")),
                stubType,
                DayCount.valueOf(stringField(node, "dayCount")),
                eom,
                convention,
                exDays,
                List.copyOf(rates),
                List.copyOf(notionals),
                calendar);
    }

    private static AccrualPeriod decodePeriod(Map<?, ?> node) {
        rejectUnknown(node, PERIOD_FIELDS, "period");
        List<AccrualSegment> segments = new ArrayList<>();
        for (Object segmentNode : asList(node.get("segments"))) {
            Map<?, ?> segmentMap = asMap(segmentNode);
            rejectUnknown(segmentMap, SEGMENT_FIELDS, "segment");
            segments.add(new AccrualSegment(
                    LocalDate.parse(stringField(segmentMap, "start")),
                    LocalDate.parse(stringField(segmentMap, "end")),
                    decimalField(segmentMap, "notional"),
                    decimalField(segmentMap, "annualRate"),
                    decimalField(segmentMap, "yearFraction"),
                    decimalField(segmentMap, "couponAmount")));
        }
        return new AccrualPeriod(
                intField(node, "index"),
                LocalDate.parse(stringField(node, "unadjustedStart")),
                LocalDate.parse(stringField(node, "unadjustedEnd")),
                LocalDate.parse(stringField(node, "paymentDate")),
                decimalField(node, "notional"),
                decimalField(node, "annualRate"),
                decimalField(node, "yearFraction"),
                decimalField(node, "couponAmount"),
                segments);
    }

    private static CashFlow decodeCashFlow(Map<?, ?> node) {
        rejectUnknown(node, CASHFLOW_FIELDS, "cashFlow");
        return new CashFlow(
                LocalDate.parse(stringField(node, "paymentDate")),
                CashFlowType.valueOf(stringField(node, "type")),
                decimalField(node, "amount"),
                intField(node, "periodIndex"));
    }

    private static int frozenDefault(int version) {
        if (version == 1) {
            return 0;
        }
        throw new ScheduleException(ErrorCode.INVALID_TERMS,
                "no frozen defaults for version " + version);
    }

    private static void rejectUnknown(Map<?, ?> node, Set<String> allowed, String where) {
        for (Object key : node.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new ScheduleException(ErrorCode.INVALID_TERMS,
                        "unknown " + where + " field rejected: " + key);
            }
        }
    }

    private static Map<?, ?> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "expected object node");
        }
        return map;
    }

    private static List<?> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "expected array node");
        }
        return list;
    }

    private static String stringField(Map<?, ?> node, String key) {
        Object value = node.get(key);
        if (!(value instanceof String text)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "missing or invalid field: " + key);
        }
        return text;
    }

    private static int intField(Map<?, ?> node, String key) {
        return intField(node, key, null);
    }

    private static int intField(Map<?, ?> node, String key, Integer fallback) {
        Object value = node.get(key);
        if (value == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "missing field: " + key);
        }
        if (!(value instanceof Number number)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "invalid integer field: " + key);
        }
        return number.intValue();
    }

    private static boolean boolField(Map<?, ?> node, String key, boolean fallback) {
        Object value = node.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "invalid boolean field: " + key);
        }
        return flag;
    }

    private static BigDecimal decimalField(Map<?, ?> node, String key) {
        Object value = node.get(key);
        if (!(value instanceof BigDecimal decimal)) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS,
                    "missing or invalid decimal field: " + key);
        }
        return decimal;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ScheduleException(ErrorCode.INVALID_TERMS, message);
        }
    }
}
