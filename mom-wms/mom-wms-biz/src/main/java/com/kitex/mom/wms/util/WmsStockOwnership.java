package com.kitex.mom.wms.util;

import com.kitex.mom.wms.service.impl.externalinventory.ExternalLossContext;
import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 入库、库存和出库共用的实物货权约束；空货权仅用于兼容历史自有库存。
 */
public final class WmsStockOwnership {
    public static final String OWNED = "OWNED";
    public static final String CUSTOMER_SUPPLIED = "CUSTOMER_SUPPLIED";
    public static final String CONSIGNED = "CONSIGNED";
    private static final Set<String> TYPES = Set.of(OWNED, CUSTOMER_SUPPLIED, CONSIGNED);

    private WmsStockOwnership() {
    }

    /** 产品标准货权范围；仓库作业配置只能决定该业务是否开放。 */
    public static List<String> allowedOwnerTypes(String billType, boolean inbound) {
        return supportsExternalOwnership(billType, inbound)
                ? List.of(OWNED, CUSTOMER_SUPPLIED, CONSIGNED)
                : List.of(OWNED);
    }

    /** 前端展示的身份要求与实际校验共用业务类型判定。 */
    public static String externalIdentityRequirement(String billType, boolean inbound) {
        if (!supportsExternalOwnership(billType, inbound)) {
            return "NONE";
        }
        return inbound ? "OWNER_AND_SOURCE_NO" : "OWNER_AND_STOCK_ID";
    }

    private static boolean supportsExternalOwnership(String billType, boolean inbound) {
        return inbound ? "other_in".equals(billType) : "other_out".equals(billType);
    }

    public static void prepareInbound(String billType, WmsInBillDetailEntity detail) {
        if (detail == null) {
            throw new CheckedException("入库明细不能为空");
        }
        InboundIdentity identity = validateInboundIdentity(billType, detail.getStockOwnerType(),
                detail.getStockOwnerCode(), detail.getSourceBusinessNo());
        detail.setStockOwnerType(identity.type());
        detail.setStockOwnerCode(identity.ownerCode());
        detail.setSourceBusinessNo(identity.sourceNo());
    }

    /** 通知单先校验身份，保留原始载荷以兼容已有来源请求指纹。 */
    public static void validateInboundNotice(String billType, WmsInNoticeDetailEntity detail) {
        if (detail == null) {
            throw new CheckedException("入库通知明细不能为空");
        }
        validateInboundIdentity(billType, detail.getStockOwnerType(),
                detail.getStockOwnerCode(), detail.getSourceBusinessNo());
    }

    private static InboundIdentity validateInboundIdentity(String billType, String ownerType,
                                                            String ownerCodeValue, String sourceNoValue) {
        String type = normalizedType(ownerType);
        String ownerCode = StrUtil.trim(ownerCodeValue);
        String sourceNo = StrUtil.trim(sourceNoValue);
        if (!TYPES.contains(type)) {
            throw new CheckedException("库存货权类型无效");
        }
        if (OWNED.equals(type)) {
            if (StrUtil.isNotBlank(ownerCode)) {
                throw new CheckedException("自有库存不能填写外部归属方");
            }
        } else if (!supportsExternalOwnership(billType, true)
                || StrUtil.isBlank(ownerCode) || StrUtil.isBlank(sourceNo)) {
            throw new CheckedException("客供或寄售入库须使用其他入库，并填写归属方和来源单号");
        }
        if (StrUtil.length(ownerCode) > 64 || StrUtil.length(sourceNo) > 128) {
            throw new CheckedException("库存归属方或来源单号过长");
        }
        return new InboundIdentity(type, StrUtil.emptyToNull(ownerCode), StrUtil.emptyToNull(sourceNo));
    }

    /** 来源通知已声明的货权与来源不可在正式入库单中改写。 */
    public static boolean matchesInboundNotice(WmsInNoticeDetailEntity notice, WmsInBillDetailEntity bill) {
        if (notice == null || bill == null
                || !Objects.equals(normalizedType(notice.getStockOwnerType()), normalizedType(bill.getStockOwnerType()))
                || !Objects.equals(trimmed(notice.getStockOwnerCode()), trimmed(bill.getStockOwnerCode()))) {
            return false;
        }
        return StrUtil.isBlank(notice.getSourceBusinessNo())
                || Objects.equals(trimmed(notice.getSourceBusinessNo()), trimmed(bill.getSourceBusinessNo()));
    }

    /** 来源通知锁定的货权和库存行不可在正式出库单中替换。 */
    public static boolean matchesOutboundNotice(WmsOutNoticeDetailEntity notice, WmsOutBillDetailEntity bill) {
        return notice != null && bill != null
                && Objects.equals(normalizedType(notice.getStockOwnerType()), normalizedType(bill.getStockOwnerType()))
                && Objects.equals(trimmed(notice.getStockOwnerCode()), trimmed(bill.getStockOwnerCode()))
                && (notice.getSourceStockId() == null
                    || Objects.equals(notice.getSourceStockId(), bill.getSourceStockId()));
    }

    /** 通知单声明外部货权时，必须先锁定可追溯的库存行。 */
    public static void validateOutboundNotice(String billType, WmsOutNoticeDetailEntity detail) {
        if (detail == null) {
            throw new CheckedException("出库通知明细不能为空");
        }
        String type = normalizedType(detail.getStockOwnerType());
        String ownerCode = StrUtil.trim(detail.getStockOwnerCode());
        if (!TYPES.contains(type)) {
            throw new CheckedException("库存货权类型无效");
        }
        if (OWNED.equals(type)) {
            if (StrUtil.isNotBlank(ownerCode)) {
                throw new CheckedException("自有库存不能填写外部归属方");
            }
        } else if (!supportsExternalOwnership(billType, false) || StrUtil.isBlank(ownerCode)
                || detail.getSourceStockId() == null) {
            throw new CheckedException("客供或寄售出库须使用其他出库，并锁定归属方和库存行");
        }
        if (StrUtil.length(ownerCode) > 64) {
            throw new CheckedException("库存归属方编码过长");
        }
        if (detail.getSourceStockId() != null && detail.getSourceStockId() <= 0) {
            throw new CheckedException("出库锁定的库存行ID无效");
        }
    }

    public static boolean isCompanyOwned(WmsLiveStockEntity stock) {
        return stock != null && OWNED.equals(normalizedType(stock.getStockOwnerType()));
    }

    public static void validateOutbound(String billType, WmsOutBillDetailEntity detail,
                                        WmsLiveStockEntity stock) {
        if (detail == null || stock == null) {
            throw new CheckedException("出库明细或源库存不存在");
        }
        String stockType = normalizedType(stock.getStockOwnerType());
        String requestedType = normalizedType(detail.getStockOwnerType());
        if (!TYPES.contains(stockType) || !TYPES.contains(requestedType)) {
            throw new CheckedException("库存货权类型无效，禁止出库");
        }
        if (!Objects.equals(stockType, requestedType)
                || !Objects.equals(StrUtil.nullToEmpty(stock.getStockOwnerCode()),
                StrUtil.nullToEmpty(detail.getStockOwnerCode()))) {
            throw new CheckedException("出库库存货权与单据不一致");
        }
        if (!OWNED.equals(stockType)) {
            if ((!supportsExternalOwnership(billType, false)
                    && !ExternalLossContext.exact(billType, detail, stock))
                    || !Objects.equals(detail.getSourceStockId(), stock.getId())
                    || StrUtil.isBlank(stock.getStockOwnerCode())) {
                throw new CheckedException("客供或寄售库存只能由其他出库精确锁定原库存行");
            }
            String sourceNo = StrUtil.trim(stock.getSourceBusinessNo());
            if (StrUtil.isBlank(sourceNo)) {
                throw new CheckedException("客供或寄售库存缺少原入库来源单号，禁止出库");
            }
            String requestedSourceNo = StrUtil.trim(detail.getSourceBusinessNo());
            if (StrUtil.isNotBlank(requestedSourceNo) && !Objects.equals(requestedSourceNo, sourceNo)) {
                throw new CheckedException("出库单来源单号与原入库来源不一致");
            }
            detail.setSourceBusinessNo(sourceNo);
        }
    }

    private static String normalizedType(String type) {
        return StrUtil.isBlank(type) ? OWNED : type.trim();
    }

    private static String trimmed(String value) {
        return StrUtil.nullToEmpty(StrUtil.trim(value));
    }

    private record InboundIdentity(String type, String ownerCode, String sourceNo) {
    }
}
