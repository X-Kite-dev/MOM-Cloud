package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipTimeoutPreviewDTO.*;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.mapper.MesWipTimeoutConfigMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;

@Service
public class MesWipTimeoutPreviewService {
    public static final String READ =
            "@pms.hasPermission('mesWipTimeoutConfig:show') and"
                    + " @pms.hasPermission('mes_mesWarehouseLiveStock_view')";
    private static final String SCHEMA = "MES_WIP_TIMEOUT_PREVIEW@1";
    private final MesActualFactoryAccessService access;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesTaskArrangeMapper arrangements;
    private final MesWipTimeoutConfigMapper configs;
    private final Clock clock;

    @Autowired
    public MesWipTimeoutPreviewService(
            MesActualFactoryAccessService a,
            MesWarehouseLiveStockMapper s,
            MesTaskArrangeMapper p,
            MesWipTimeoutConfigMapper c) {
        this(a, s, p, c, Clock.systemDefaultZone());
    }

    MesWipTimeoutPreviewService(
            MesActualFactoryAccessService a,
            MesWarehouseLiveStockMapper s,
            MesTaskArrangeMapper p,
            MesWipTimeoutConfigMapper c,
            Clock clock) {
        access = a;
        stocks = s;
        arrangements = p;
        configs = c;
        this.clock = clock;
    }

    private record Owner(Long tenant, String factory, Long actor) {}

    private Owner owner() {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        var u = SecurityUtils.getUser();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && u != null
                        && u.getId() != null
                        && u.getId() > 0
                        && Objects.equals(tenant, u.getTenantId()),
                "试算缺少可核验的实名组织");
        return new Owner(tenant, factory, u.getId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Sources sources(String barcode) {
        return sources(barcode, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Sources sources(String barcode, String afterId) {
        var o = owner();
        String exact = code(barcode, false);
        Long after = afterId == null ? null : id(afterId);
        var rows =
                stocks.selectList(
                        stockQuery(o)
                                .eq(MesWarehouseLiveStockEntity::getMatBarcode, exact)
                                .gt(after != null, MesWarehouseLiveStockEntity::getId, after)
                                .orderByAsc(MesWarehouseLiveStockEntity::getId)
                                .last("LIMIT 21"));
        require(rows.size() <= 21, "来源候选超过读取上限");
        for (var row : rows) {
            checkStock(row, o);
            require(
                    Objects.equals(row.getMatBarcode(), exact)
                            && (after == null || row.getId() > after),
                    "条码候选返回了其他实物来源或旧页");
        }
        require(
                rows.stream().map(MesWarehouseLiveStockEntity::getId).distinct().count()
                        == rows.size(),
                "来源候选主键重复");
        return new Sources(
                SCHEMA,
                o.tenant().toString(),
                o.factory(),
                o.actor().toString(),
                exact,
                afterId,
                rows.stream().limit(20).map(this::source).toList(),
                rows.size() > 20,
                rows.size() > 20 ? rows.get(19).getId().toString() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Query q) {
        var o = owner();
        require(q != null, "试算草稿不可为空");
        Long stockId = id(q.getStockId());
        Long draftId = q.getDraftId() == null ? null : id(q.getDraftId());
        var draft = new MesWipTimeoutConfigEntity();
        draft.setId(draftId);
        draft.setTenantId(o.tenant());
        draft.setFactoryCode(o.factory());
        draft.setCode("DRAFT");
        draft.setName(
                q.getName() == null || q.getName().isBlank() ? "当前草稿" : code(q.getName(), false));
        draft.setLineCode(code(q.getLineCode(), true));
        draft.setRouteCode(code(q.getRouteCode(), true));
        draft.setProductCode(code(q.getProductCode(), true));
        draft.setTimeoutSec(seconds(q.getTimeoutSec(), false));
        draft.setLevel2Sec(seconds(q.getLevel2Sec(), true));
        draft.setLevel3Sec(seconds(q.getLevel3Sec(), true));
        require(q.getEnabled() != null, "须明确草稿启用状态");
        draft.setEnabled(q.getEnabled());
        MesWipTimeoutRuleEvaluator.level(draft, 0);
        var found =
                stocks.selectList(
                        stockQuery(o)
                                .eq(MesWarehouseLiveStockEntity::getId, stockId)
                                .last("LIMIT 2"));
        require(found.size() == 1, "在制来源不存在、已离站或不唯一");
        var stock = found.get(0);
        checkStock(stock, o);
        require(Objects.equals(stockId, stock.getId()), "试算返回了其他在制主键");
        if (draftId != null) {
            var existing =
                    configs.selectList(
                            new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                                    .select(
                                            MesWipTimeoutConfigEntity::getId,
                                            MesWipTimeoutConfigEntity::getTenantId,
                                            MesWipTimeoutConfigEntity::getFactoryCode,
                                            MesWipTimeoutConfigEntity::getCode)
                                    .eq(MesWipTimeoutConfigEntity::getTenantId, o.tenant())
                                    .eq(MesWipTimeoutConfigEntity::getFactoryCode, o.factory())
                                    .eq(MesWipTimeoutConfigEntity::getId, draftId)
                                    .last("LIMIT 2"));
            require(
                    existing.size() == 1
                            && Objects.equals(existing.get(0).getId(), draftId)
                            && Objects.equals(existing.get(0).getTenantId(), o.tenant())
                            && Objects.equals(existing.get(0).getFactoryCode(), o.factory()),
                    "被编辑配置不存在或不属于当前工厂");
            draft.setCode(existing.get(0).getCode());
        }
        var saved =
                configs.selectList(
                        new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                                .select(
                                        MesWipTimeoutConfigEntity::getId,
                                        MesWipTimeoutConfigEntity::getTenantId,
                                        MesWipTimeoutConfigEntity::getFactoryCode,
                                        MesWipTimeoutConfigEntity::getCode,
                                        MesWipTimeoutConfigEntity::getName,
                                        MesWipTimeoutConfigEntity::getLineCode,
                                        MesWipTimeoutConfigEntity::getRouteCode,
                                        MesWipTimeoutConfigEntity::getProductCode,
                                        MesWipTimeoutConfigEntity::getEnabled,
                                        MesWipTimeoutConfigEntity::getTimeoutSec,
                                        MesWipTimeoutConfigEntity::getLevel2Sec,
                                        MesWipTimeoutConfigEntity::getLevel3Sec)
                                .eq(MesWipTimeoutConfigEntity::getTenantId, o.tenant())
                                .eq(MesWipTimeoutConfigEntity::getFactoryCode, o.factory())
                                .eq(MesWipTimeoutConfigEntity::getEnabled, true)
                                .ne(draftId != null, MesWipTimeoutConfigEntity::getId, draftId)
                                .orderByAsc(MesWipTimeoutConfigEntity::getCode)
                                .last("LIMIT 1001"));
        require(saved.size() <= 1000, "当前工厂规则超过1000条，无法给出完整试算，请先核查规则范围");
        var rules = new ArrayList<MesWipTimeoutConfigEntity>();
        for (var rule : saved) {
            require(
                    Objects.equals(rule.getTenantId(), o.tenant())
                            && Objects.equals(rule.getFactoryCode(), o.factory())
                            && Boolean.TRUE.equals(rule.getEnabled()),
                    "竞争规则返回了越界或停用数据");
            if (!Objects.equals(draftId, rule.getId()) || draftId == null) rules.add(rule);
        }
        require(
                rules.stream().map(MesWipTimeoutConfigEntity::getId).distinct().count()
                        == rules.size(),
                "竞争规则主键不唯一");
        rules.add(draft);
        MesTaskArrangeEntity arrange = null;
        if (stock.getBillNo() != null && !stock.getBillNo().isBlank()) {
            var list =
                    arrangements.selectList(
                            new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                    .select(
                                            MesTaskArrangeEntity::getTenantId,
                                            MesTaskArrangeEntity::getFactoryCode,
                                            MesTaskArrangeEntity::getCode,
                                            MesTaskArrangeEntity::getRouteCode,
                                            MesTaskArrangeEntity::getRouteWorkCenter)
                                    .eq(MesTaskArrangeEntity::getTenantId, o.tenant())
                                    .eq(MesTaskArrangeEntity::getFactoryCode, o.factory())
                                    .eq(MesTaskArrangeEntity::getCode, stock.getBillNo())
                                    .last("LIMIT 2"));
            require(list.size() <= 1, "同组织派工编码不唯一");
            if (!list.isEmpty()) arrange = list.get(0);
        }
        var selection = MesWipTimeoutRuleEvaluator.select(rules, stock, arrange);
        var selected = selection.selected();
        String status =
                !Boolean.TRUE.equals(draft.getEnabled())
                        ? "DISABLED"
                        : selection.matches().stream().noneMatch(m -> m.rule() == draft)
                                ? "OUT_OF_SCOPE"
                                : selection.preferred().stream().anyMatch(m -> m.rule() == draft)
                                        ? (selected == null ? "AMBIGUOUS" : "SELECTED")
                                        : "SHADOWED";
        LocalDateTime observed = LocalDateTime.now(clock);
        String timeStatus =
                stock.getInTime() == null
                        ? "MISSING"
                        : stock.getInTime().isAfter(observed) ? "FUTURE" : "KNOWN";
        Long elapsed =
                "KNOWN".equals(timeStatus)
                        ? Duration.between(stock.getInTime(), observed).getSeconds()
                        : null;
        Integer level =
                selected != null && elapsed != null
                        ? MesWipTimeoutRuleEvaluator.level(selected, elapsed)
                        : null;
        var echo =
                new Draft(
                        draftId == null ? null : draftId.toString(),
                        draft.getName(),
                        draft.getLineCode(),
                        draft.getRouteCode(),
                        draft.getProductCode(),
                        str(draft.getTimeoutSec()),
                        str(draft.getLevel2Sec()),
                        str(draft.getLevel3Sec()),
                        draft.getEnabled());
        var matches =
                selection.matches().stream()
                        .map(m -> rule(m.rule(), m.rule() == draft, m.specificity()))
                        .toList();
        return new Preview(
                SCHEMA,
                o.tenant().toString(),
                o.factory(),
                o.actor().toString(),
                observed.toString(),
                clock.getZone().getId(),
                source(stock),
                echo,
                elapsed == null ? null : elapsed.toString(),
                timeStatus,
                status,
                matches,
                selected == null
                        ? null
                        : rule(
                                selected,
                                selected == draft,
                                selection.preferred().get(0).specificity()),
                str(level),
                level != null);
    }

    private LambdaQueryWrapper<MesWarehouseLiveStockEntity> stockQuery(Owner o) {
        return new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .select(
                        MesWarehouseLiveStockEntity::getId,
                        MesWarehouseLiveStockEntity::getTenantId,
                        MesWarehouseLiveStockEntity::getFactoryCode,
                        MesWarehouseLiveStockEntity::getMatDetailType,
                        MesWarehouseLiveStockEntity::getMatBarcode,
                        MesWarehouseLiveStockEntity::getMatCode,
                        MesWarehouseLiveStockEntity::getMatName,
                        MesWarehouseLiveStockEntity::getMatUnit,
                        MesWarehouseLiveStockEntity::getNum,
                        MesWarehouseLiveStockEntity::getWarehouseCode,
                        MesWarehouseLiveStockEntity::getRouteCode,
                        MesWarehouseLiveStockEntity::getSiteCode,
                        MesWarehouseLiveStockEntity::getBillNo,
                        MesWarehouseLiveStockEntity::getInTime)
                .eq(MesWarehouseLiveStockEntity::getTenantId, o.tenant())
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, o.factory())
                .eq(MesWarehouseLiveStockEntity::getMatDetailType, "WIP")
                .gt(MesWarehouseLiveStockEntity::getNum, 0);
    }

    private void checkStock(MesWarehouseLiveStockEntity s, Owner o) {
        require(
                s != null
                        && s.getId() != null
                        && s.getId() > 0
                        && Objects.equals(s.getTenantId(), o.tenant())
                        && Objects.equals(s.getFactoryCode(), o.factory())
                        && "WIP".equals(s.getMatDetailType())
                        && s.getNum() != null
                        && s.getNum().signum() > 0
                        && s.getMatBarcode() != null
                        && !s.getMatBarcode().isBlank(),
                "在制来源组织、类别、条码或当前余量不可核验");
    }

    private Source source(MesWarehouseLiveStockEntity s) {
        return new Source(
                s.getId().toString(),
                s.getMatBarcode(),
                s.getMatCode(),
                s.getMatName(),
                s.getNum().toPlainString(),
                s.getMatUnit(),
                s.getWarehouseCode(),
                s.getRouteCode(),
                s.getBillNo(),
                s.getInTime() == null ? null : s.getInTime().toString());
    }

    private Rule rule(MesWipTimeoutConfigEntity r, boolean draft, int score) {
        return new Rule(
                r.getCode(),
                r.getName(),
                draft,
                r.getLineCode(),
                r.getRouteCode(),
                r.getProductCode(),
                score,
                str(r.getTimeoutSec()),
                str(r.getLevel2Sec()),
                str(r.getLevel3Sec()));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer seconds(String s, boolean optional) {
        if (optional && s == null) return null;
        require(s != null && s.matches("[1-9][0-9]{0,9}"), "阈值须为正整数秒文本");
        try {
            int n = Integer.parseInt(s);
            require(n > 0, "阈值须大于0秒");
            return n;
        } catch (NumberFormatException e) {
            throw new CheckedException("阈值超过整数秒范围");
        }
    }

    private Long id(String s) {
        require(s != null && s.matches("[1-9][0-9]{0,18}"), "来源/配置主键须为精确正整数文本");
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new CheckedException("来源/配置主键超过范围");
        }
    }

    private String code(String s, boolean optional) {
        if (optional && (s == null || s.isBlank())) return null;
        require(
                s != null
                        && !s.isBlank()
                        && s.trim().length() <= 100
                        && s.chars().noneMatch(Character::isISOControl),
                "编码或名称格式不正确");
        return s.trim();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
