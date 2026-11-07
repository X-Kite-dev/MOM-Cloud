package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipQuantityRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mdm.support.MdmReceivingReferenceFingerprint;
import com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.*;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.*;
import com.kitex.mom.mes.entity.MesWipTransferEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MesWipRedirectReviewCodec {
    static final String SCHEMA = com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.SCHEMA;
    private static final ObjectMapper JSON = new ObjectMapper();

    static Source source(MesWipTransferEntity t) {
        need(
                t.getSourceReportId() != null
                        && t.getSourceStockId() != null
                        && t.getRemainingQuantity() != null
                        && t.getRemainingQuantity().signum() > 0,
                "原转运来源或剩余量缺失");
        return new Source(
                t.getId().toString(),
                t.getRevision().toString(),
                t.getSourceReportId().toString(),
                t.getSourceStockId().toString(),
                t.getFromLocationCode(),
                t.getToLocationCode(),
                t.getRemainingQuantity().stripTrailingZeros().toPlainString(),
                t.getMatUnit(),
                sha(write(Arrays.asList(t.getSourceSnapshot(), t.getLocationSnapshot()))));
    }

    static boolean matchesSource(Source original, MesWipTransferEntity t) {
        if (t == null || !"0".equals(t.getDelFlag()) || !"TRANSIT".equals(t.getStatus()))
            return false;
        try {
            return original.equals(source(t));
        } catch (RuntimeException incompleteCurrentMetadata) {
            return false;
        }
    }

    static void current(Source original, MesWipTransferEntity t) {
        need(matchesSource(original, t), "原单版本、在途量、来源或目标已变化，须重新申请");
    }

    static String write(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("审批原文无法序列化", e);
        }
    }

    static <T> T parse(String raw, Class<T> type) {
        try {
            need(raw != null && raw.length() <= 262144, "审批原文缺失或超出范围");
            var v = JSON.readValue(raw, type);
            need(write(v).equals(raw), "审批原文不是原规范文本");
            return v;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("审批原文损坏", e);
        }
    }

    static String sha(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void key(String v) {
        need(v != null && v.matches("[A-Za-z0-9_-]{16,64}"), "原号须为16至64位安全文本");
    }

    static void text(String v, int limit) {
        need(v != null && !v.isBlank() && v.length() <= limit, "原文为空或超出长度");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            need(c >= 32 || c == '\n' || c == '\t', "原文含非法控制字符");
            if (Character.isHighSurrogate(c)) {
                need(++i < v.length() && Character.isLowSurrogate(v.charAt(i)), "原文含孤立代理项");
            } else need(!Character.isLowSurrogate(c), "原文含孤立代理项");
        }
    }

    static void command(Command c) {
        need(c != null && SCHEMA.equals(c.schemaVersion()), "审批协议不正确");
        id(c.tenantId());
        id(c.actorId());
        id(c.transferId());
        text(c.factoryCode(), 100);
        key(c.requestId());
        key(c.proposalId());
        text(c.reason(), 500);
        need(
                Set.of("SUBMIT", "APPROVE", "REJECT", "WITHDRAW", "ACTIVATE")
                                .contains(Objects.toString(c.action(), ""))
                        && Set.of("0", "1", "2")
                                .contains(Objects.toString(c.expectedVersion(), "")),
                "审批动作或版本不正确");
        if ("SUBMIT".equals(c.action())) {
            need(
                    c.requestId().equals(c.proposalId())
                            && "0".equals(c.expectedVersion())
                            && c.selection() != null
                            && c.reviewHash() == null,
                    "申请原号或选择不完整");
            var s = c.selection();
            need(
                    Objects.equals(s.tenantId(), c.tenantId())
                            && Objects.equals(s.factoryCode(), c.factoryCode())
                            && Objects.equals(s.actorId(), c.actorId())
                            && Objects.equals(s.transferId(), c.transferId()),
                    "申请选择身份不一致");
        } else {
            need(c.selection() == null && !"0".equals(c.expectedVersion()), "审核/撤回不得改写申请选择");
            need(
                    Set.of("APPROVE", "ACTIVATE").contains(c.action())
                            ? c.reviewHash() != null && c.reviewHash().matches("[0-9a-f]{64}")
                            : c.reviewHash() == null,
                    "批准须有本次预览摘要");
        }
    }

    static Choice actor(Choice c, String actor) {
        return new Choice(
                c.schemaVersion(),
                c.tenantId(),
                c.factoryCode(),
                actor,
                c.transferId(),
                c.transferRevision(),
                c.arrangeId(),
                c.worksiteCode(),
                c.locationId(),
                c.bindingRevision(),
                c.referenceHash(),
                c.quantity(),
                c.unit());
    }

    static void check(Check v, Choice expected, String original) {
        need(
                com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.SCHEMA.equals(
                        expected.schemaVersion()),
                "资格选择协议不正确");
        need(
                v != null
                        && com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.SCHEMA.equals(
                                v.schemaVersion())
                        && expected.equals(v.selection())
                        && expected.tenantId().equals(v.tenantId())
                        && expected.factoryCode().equals(v.factoryCode())
                        && expected.actorId().equals(v.actorId())
                        && "TARGET_CHANGE_CHECKED".equals(v.state())
                        && !v.applied()
                        && !v.reserved()
                        && !v.productionRelease()
                        && Objects.equals(original, v.originalLocationCode()),
                "资格原件身份或生效标记不一致");
        need(v.nodeCode() != null && v.nodeCode().matches("RN-[1-9][0-9]{0,18}"), "资格节点缺失");
        java.time.Instant.parse(v.observedAt());
        var r = v.reference();
        need(
                r != null
                        && r.station() != null
                        && r.point() != null
                        && r.station().valid()
                        && r.point().valid()
                        && expected.worksiteCode().equals(r.station().code())
                        && expected.locationId().equals(r.point().id())
                        && !original.equals(r.point().code())
                        && Objects.equals(r.hash(), expected.referenceHash())
                        && Objects.equals(
                                r.hash(),
                                MdmReceivingReferenceFingerprint.of(
                                        v.tenantId(), v.factoryCode(), r.station(), r.point())),
                "资格位置原件不一致");
        var q = v.capacity();
        need(
                q != null
                        && expected.unit().equals(q.unit())
                        && expected.quantity().equals(q.deliveryQuantity()),
                "资格数量单位不一致");
        var p = number(q.plannedQuantity(), false);
        var done = number(q.completedQuantity(), true);
        var max = number(q.maximumReportQuantity(), false);
        var rem = number(q.remainingReportQuantity(), true);
        var rate = number(q.overReportRate(), true);
        need(
                rate.compareTo(BigDecimal.ONE) <= 0
                        && (q.overReportAllowed() || rate.signum() == 0)
                        && same(max, p.multiply(BigDecimal.ONE.add(rate)))
                        && same(rem, max.subtract(done))
                        && delivery(q.deliveryQuantity()).compareTo(rem) <= 0,
                "资格数量与冻结策略不守恒");
    }

    static String semantic(Check c) {
        return sha(semanticText(c));
    }

    static String semanticText(Check c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("schemaVersion", c.schemaVersion());
        m.put("tenantId", c.tenantId());
        m.put("factoryCode", c.factoryCode());
        m.put("actorId", c.actorId());
        m.put("selection", c.selection());
        m.put("nodeCode", c.nodeCode());
        m.put("reference", c.reference());
        m.put("originalLocationCode", c.originalLocationCode());
        m.put("capacity", c.capacity());
        m.put("state", c.state());
        m.put("applied", c.applied());
        m.put("reserved", c.reserved());
        m.put("productionRelease", c.productionRelease());
        return write(m);
    }

    static String next(String state, String action, String applicant, String actor) {
        return switch (action) {
            case "SUBMIT" -> {
                need("NONE".equals(state), "申请已存在");
                yield "PENDING";
            }
            case "APPROVE" -> {
                need("PENDING".equals(state) && !applicant.equals(actor), "必须由另一人审核待审申请");
                yield "APPROVED_AWAITING_ACTIVATION";
            }
            case "REJECT" -> {
                need(
                        Set.of("PENDING", "APPROVED_AWAITING_ACTIVATION").contains(state)
                                && !applicant.equals(actor),
                        "须由另一人驳回或取消未生效批准");
                yield "REJECTED";
            }
            case "WITHDRAW" -> {
                need(
                        Set.of("PENDING", "APPROVED_AWAITING_ACTIVATION").contains(state)
                                && applicant.equals(actor),
                        "仅申请人可撤回未生效方案");
                yield "WITHDRAWN";
            }
            default -> throw new IllegalArgumentException("未知审批动作");
        };
    }
}
