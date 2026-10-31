package com.kitex.mom.wms.service.noticeAcceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeAccess.Scope;
import com.kitex.mom.wms.util.WmsStockOwnership;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 只冻结业务输入，数据库ID、处理累计和审计字段不能改变同源业务身份。
 */
public final class WmsProductionNoticeCanonical {
    public static final String SOURCE = "mes_picking";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule()).disable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WmsProductionNoticeCanonical() {
    }

    public record Prepared(String commandPayload, String noticePayload, String fingerprint, WmsOutNoticeEntity notice) {
    }

    public static Prepared prepare(Command command, Scope scope, boolean legacy) {
        require(command != null, "缺少生产领料受理请求");
        request(command.getRequestId());
        require(Objects.equals(scope.tenant(), command.getTenantId()) &&
                Objects.equals(scope.factory(), command.getFactoryCode()), "当前租户或工厂已变化，请按原来源核对");
        Integer revision = command.getSourceRevision();
        require(revision != null && revision > 0 && revision <= 1000000, "来源修订号无效");
        require(revision == 1 ?
                command.getExpectedPreviousRevision() == null : Objects.equals(command.getExpectedPreviousRevision(),
                revision - 1), "新一轮受理必须明确前一修订");
        var original = command.getNotice();
        require(original != null && original.getDocumentId() != null && original.getDocumentId() > 0 &&
                SOURCE.equals(original.getDocumentTable()), "缺少有效MES领料来源");
        require("produce".equals(original.getBillType()), "领料来源只支持生产领料通知");
        require(original.getSourceAcceptanceRevision() == null ||
                Objects.equals(original.getSourceAcceptanceRevision(), revision), "通知修订与原请求不一致");
        identity(original.getTenantId(), original.getFactoryCode(), scope);
        String warehouse = text(original.getWarehouseCode());
        require(!warehouse.isEmpty() && warehouse.length() <= 100 && !warehouse.matches(".*[?*].*"),
                "请选择实际领料仓库");
        require(original.getDetailList() != null && !original.getDetailList().isEmpty() &&
                original.getDetailList().size() <= 500, "领料明细须为1至500行");
        var notice = new WmsOutNoticeEntity();
        BeanUtils.copyProperties(original, notice);
        normalizeStrings(notice);
        notice.setState(null);
        notice.setCompleteTime(null);
        notice.setId(null);
        notice.setTenantId(scope.tenant());
        notice.setFactoryCode(scope.factory());
        notice.setWarehouseCode(warehouse);
        notice.setSourceAcceptanceRevision(revision);
        notice.setCreateBy(null);
        notice.setCreateTime(null);
        notice.setUpdateBy(null);
        notice.setUpdateTime(null);
        notice.setDelFlag(null);
        var keyed = new ArrayList<Map.Entry<String, WmsOutNoticeDetailEntity>>();
        var sourceIds = new HashSet<Long>();
        for (var input : original.getDetailList()) {
            require(input != null, "领料明细不能为空");
            identity(input.getTenantId(), input.getFactoryCode(), scope);
            require(input.getDocumentId() == null || Objects.equals(input.getDocumentId(), original.getDocumentId()),
                    "领料明细来源与主单不一致");
            require(text(input.getDocumentTable()).isEmpty() || SOURCE.equals(input.getDocumentTable()),
                    "领料明细来源类型不一致");
            require(text(input.getWarehouseCode()).isEmpty() || warehouse.equals(input.getWarehouseCode()),
                    "领料明细不能属于其他仓库");
            require(!text(input.getMatCode()).isEmpty() && !text(input.getMatUnit()).isEmpty(),
                    "物料和标准单位不能为空");
            require(input.getMatCode().length() <= 100 && input.getMatUnit().length() <= 100, "物料或单位编码过长");
            Double quantity = input.getNoticeOutNum();
            require(quantity != null && Double.isFinite(quantity) && quantity > 0, "领料数量必须是有效正数");
            Long sourceId = input.getSourceDocumentDetailId();
            require(legacy || sourceId != null, "新版受理须携带原领料明细标识");
            require(sourceId == null || sourceId > 0 && sourceIds.add(sourceId), "原领料明细重复或无效");
            exactSourceDecimal(input.getRequirementMatNum(), "BOM需求当量");
            exactSourceDecimal(input.getSubstituteRatio(), "替代比例");
            WmsStockOwnership.validateOutboundNotice("produce", input);
            var line = new WmsOutNoticeDetailEntity();
            BeanUtils.copyProperties(input, line);
            normalizeStrings(line);
            line.setId(null);
            line.setNoticeId(null);
            line.setTenantId(scope.tenant());
            line.setFactoryCode(scope.factory());
            line.setDocumentId(notice.getDocumentId());
            line.setDocumentTable(SOURCE);
            line.setWarehouseCode(warehouse);
            line.setWarehouseName(notice.getWarehouseName());
            line.setWorkshop(notice.getWorkshop());
            line.setCreateBy(null);
            line.setCreateTime(null);
            line.setUpdateBy(null);
            line.setUpdateTime(null);
            line.setDelFlag(null);
            var fields = fields(line,
                    List.of("sourceDocumentDetailId", "matCode", "matName", "matUnit", "matSpec", "matGroup",
                            "matGroupName", "noticeOutNum", "stockOwnerCode", "sourceStockId", "requirementMatCode",
                            "requirementMatName", "requirementMatNum", "substituteRatio", "reservationNo",
                            "matProduceDate", "matValidPeriod", "matValidEndDate", "expansion"));
            fields.put("stockOwnerType", (text(line.getStockOwnerType()).isEmpty() ? WmsStockOwnership.OWNED : text(
                    line.getStockOwnerType())));
            fields.put("substituteFlag", Boolean.TRUE.equals(line.getSubstituteFlag()));
            keyed.add(Map.entry(write(fields), line));
        }
        keyed.sort(Map.Entry.comparingByKey());
        notice.setDetailList(keyed.stream().map(Map.Entry::getValue).toList());
        var payload = fields(notice,
                List.of("documentId", "documentTable", "warehouseCode", "workshop", "remark", "expansion"));
        payload.put("lines", keyed.stream().map(entry -> readMap(entry.getKey())).toList());
        String frozen = write(payload);
        var operation = new TreeMap<String, Object>();
        operation.put("action", "ACCEPT");
        operation.put("tenantId", scope.tenant().toString());
        operation.put("factoryCode", scope.factory());
        operation.put("sourceRevision", revision);
        operation.put("expectedPreviousRevision", command.getExpectedPreviousRevision());
        operation.put("notice", payload);
        return new Prepared(write(operation), frozen, hash(frozen), notice);
    }

    public static String cancelPayload(Cancel command, Scope scope) {
        require(command != null, "缺少撤回请求");
        request(command.getRequestId());
        require(Objects.equals(command.getTenantId(), scope.tenant()) &&
                Objects.equals(command.getFactoryCode(), scope.factory()), "撤回请求租户工厂与当前作用域不符");
        require(command.getDocumentId() != null && command.getDocumentId() > 0 && command.getSourceRevision() != null &&
                command.getSourceRevision() > 0, "撤回来源或修订无效");
        require(!text(command.getReason()).isEmpty() && text(command.getReason()).length() <= 500,
                "请填写500字以内的撤回原因");
        return write(new TreeMap<>(
                Map.of("action", "CANCEL", "tenantId", scope.tenant().toString(), "factoryCode", scope.factory(),
                        "documentId", command.getDocumentId().toString(), "sourceRevision", command.getSourceRevision(),
                        "reason", text(command.getReason()))));
    }

    private static Map<String, Object> fields(Object bean, List<String> names) {
        var wrapper = new BeanWrapperImpl(bean);
        Map<String, Object> values = new TreeMap<>();
        for (String name : names) {
            Object value = wrapper.getPropertyValue(name);
            if (value instanceof String string) value = text(string);
            else if (value == null) value = "";
            else if (value instanceof Long number) value = number.toString();
            else if (value instanceof BigDecimal decimal) value = decimal.stripTrailingZeros().toPlainString();
            else if (value instanceof Double number)
                value = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
            values.put(name, value);
        }
        return values;
    }

    /**
     * 与现有numeric(20,8)合同一致，不允许先舍入再签发永久凭据。
     */
    private static void exactSourceDecimal(BigDecimal value, String label) {
        if (value == null) return;
        BigDecimal normalized = value.stripTrailingZeros();
        require(value.signum() > 0 && normalized.scale() <= 8
                        && normalized.precision() - normalized.scale() <= 12,
                label + "须为正数，最多12位整数和8位小数，不能舍入受理");
    }

    private static void normalizeStrings(Object bean) {
        var wrapper = new BeanWrapperImpl(bean);
        for (var property : wrapper.getPropertyDescriptors()) {
            String name = property.getName();
            if (property.getPropertyType() == String.class && wrapper.isReadableProperty(name) &&
                    wrapper.isWritableProperty(name) && wrapper.getPropertyValue(name) instanceof String value)
                wrapper.setPropertyValue(name, value.trim());
        }
    }

    private static void identity(Long tenant, String factory, Scope scope) {
        require(tenant == null || Objects.equals(tenant, scope.tenant()), "领料数据不属于当前租户");
        require(text(factory).isEmpty() || scope.factory().equals(factory), "领料数据不属于当前工厂");
    }

    public static void request(String id) {
        require(id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{15,63}"), "原受理请求号无效");
    }

    public static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception problem) {
            throw new CheckedException("生产领料受理证据无法序列化");
        }
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (Exception problem) {
            throw new CheckedException("生产领料永久回执损坏，请保留原记录核对");
        }
    }

    private static Map readMap(String value) {
        return read(value, Map.class);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception problem) {
            throw new IllegalStateException(problem);
        }
    }

    public static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
