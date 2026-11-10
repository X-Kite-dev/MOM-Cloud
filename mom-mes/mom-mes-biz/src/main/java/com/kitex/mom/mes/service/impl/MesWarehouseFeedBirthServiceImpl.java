package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWarehouseFeedBirthDTO;
import com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesWarehouseFeedBirthLineMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedBirthMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 首次实际投料的永久出生全集。
 * <p>只消费本事务真实OUT和数据库OLD/NEW，不再次扣库、不拼旧批次来源。
 * 源库位在每条OLD全文内，实际接收工位独立冻结；两者编码相同不是通用假设。</p>
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseFeedBirthServiceImpl
        extends ServiceImpl<MesWarehouseFeedBirthMapper, MesWarehouseFeedBirthEntity>
        implements MesWarehouseFeedBirthService {

    public static final String VERSION = "MES_FEED_BIRTH_V1";
    public static final String LINE_VERSION = "MES_FEED_SOURCE_V1";
    private static final int MAX_DOCUMENT = 16 * 1024 * 1024;

    private final MesWarehouseFeedBirthLineMapper lines;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;

    @Override
    public void assertReady() {
        if (!Boolean.TRUE.equals(baseMapper.ready()))
            throw new CheckedException("实际投料出生保护未完整部署，请先执行V2第260节；禁止降级为无来源投料");
    }

    /**
     * 生产状态变化或源库存耗尽后仍读原出生事实；不拿当前MDM重算原单位。
     */
    @Override
    @Transactional(readOnly = true)
    public MesWarehouseFeedBirthDTO evidence(Long feedId) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        if (feedId == null || feedId <= 0) throw new CheckedException("投料ID必须明确");
        assertReady();
        var found = list(new LambdaQueryWrapper<MesWarehouseFeedBirthEntity>()
                .eq(MesWarehouseFeedBirthEntity::getTenantId, tenant)
                .eq(MesWarehouseFeedBirthEntity::getFactoryCode, factory)
                .eq(MesWarehouseFeedBirthEntity::getFeedId, feedId).last("LIMIT 2"));
        if (found.isEmpty()) {
            if ("HISTORICAL_UNPROVEN".equals(baseMapper.sourceState(feedId, tenant, factory)))
                return new MesWarehouseFeedBirthDTO("HISTORICAL_UNPROVEN", null, List.of());
            throw new CheckedException("当前组织未找到投料或其首次出生证据缺失，不能猜来源");
        }
        if (found.size() != 1) throw new CheckedException("当前组织投料出生未唯一");
        var birth = found.get(0);
        var children = lines.selectList(new LambdaQueryWrapper<MesWarehouseFeedBirthLineEntity>()
                .eq(MesWarehouseFeedBirthLineEntity::getTenantId, tenant)
                .eq(MesWarehouseFeedBirthLineEntity::getFactoryCode, factory)
                .eq(MesWarehouseFeedBirthLineEntity::getBirthId, birth.getId())
                .orderByAsc(MesWarehouseFeedBirthLineEntity::getSequenceNo).last("LIMIT 5001"));
        var document = codec.objectDocument(birth.getEvidenceSnapshot(), MAX_DOCUMENT);
        var rawLines = document.get("lines");
        if (!VERSION.equals(birth.getEvidenceVersion()) || !"RECORDED".equals(birth.getStatus())
                || !Objects.equals(codec.fingerprint(VERSION, birth.getEvidenceSnapshot()), birth.getEvidenceFingerprint())
                || rawLines == null || !rawLines.isArray() || children.isEmpty() || children.size() > 5000
                || !Objects.equals(birth.getSourceCount(), children.size()) || rawLines.size() != children.size())
            throw new CheckedException("原投料出生固定证据或完整来源无法核验");
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < children.size(); index++) {
            var line = children.get(index);
            if (!Objects.equals(line.getSequenceNo(), index + 1)
                    || !Objects.equals(line.getUnit(), birth.getUnit())
                    || !LINE_VERSION.equals(line.getEvidenceVersion())
                    || !rawLines.get(index).isTextual()
                    || !Objects.equals(rawLines.get(index).textValue(), line.getEvidenceSnapshot())
                    || !Objects.equals(codec.fingerprint(LINE_VERSION, line.getEvidenceSnapshot()), line.getEvidenceFingerprint())
                    || line.getQuantity() == null || line.getQuantity().signum() <= 0)
                throw new CheckedException("原投料出生逐源原文、单位或原量无法核验");
            total = total.add(line.getQuantity());
        }
        if (birth.getQuantity() == null || total.compareTo(birth.getQuantity()) != 0)
            throw new CheckedException("原投料出生完整来源数量不守恒");
        return new MesWarehouseFeedBirthDTO("RECORDED", birth, children);
    }

    /**
     * feed保存、OUT、出生主/子账和原操作履历必须共用当前事务。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesWarehouseFeedBirthEntity record(MesWarehouseFeedEntity feed, MesTaskEntity task,
                                              MesTaskArrangeEntity arrange, MesWarehouseStockOutReceiptDTO receipt) {
        assertReady();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = access.require("write");
        var actor = SecurityUtils.getUser();
        if (actor == null || actor.getId() == null || actor.getId() <= 0
                || !Objects.equals(tenant, feed.getTenantId()) || !Objects.equals(factory, feed.getFactoryCode())
                || !Objects.equals(feed.getId(), receipt.billId()) || receipt.lines().isEmpty()
                || receipt.lines().size() > 5000)
            throw new CheckedException("投料出生缺真实实名、组织或本次OUT全集");

        var facts = baseMapper.facts(feed.getId(), task.getId(), arrange.getId(), tenant, factory);
        var sources = baseMapper.sources(feed.getId(), tenant, factory);
        if (facts == null || sources.size() != receipt.lines().size())
            throw new CheckedException("投料出生不能认领旧feed或遗漏本次实际OUT");
        Map<Long, MesWarehouseStockOutReceiptDTO.Line> expected = new HashMap<>();
        receipt.lines().forEach(line -> {
            if (expected.put(line.inoutId(), line) != null)
                throw new CheckedException("真实OUT回执不能重复");
        });

        MesWarehouseFeedBirthEntity birth = new MesWarehouseFeedBirthEntity();
        birth.setId(IdWorker.getId());
        birth.setTenantId(tenant);
        birth.setFactoryCode(factory);
        birth.setFeedId(feed.getId());
        birth.setActorId(actor.getId());
        birth.setTaskId(task.getId());
        birth.setArrangeId(arrange.getId());
        birth.setQuantity(feed.getFeedNum().stripTrailingZeros());
        birth.setUnit(feed.getMatUnit());
        birth.setReceivingWorksiteCode(nullableText(facts, "receivingWorksiteCode"));
        birth.setFeedSnapshot(text(facts, "feedSnapshot"));
        birth.setBomSnapshot(text(facts, "bomSnapshot"));
        birth.setArrangeSnapshot(text(facts, "arrangeSnapshot"));
        birth.setSourceCount(sources.size());
        birth.setStatus("RECORDED");
        birth.setCreateBy(actor.getUsername());
        birth.setCreateTime(feed.getFeedTime());
        birth.setDelFlag("0");

        List<MesWarehouseFeedBirthLineEntity> children = new ArrayList<>();
        List<String> originalLines = new ArrayList<>();
        Set<Long> stocks = new HashSet<>();
        Set<String> mutations = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> source : sources) {
            Long flowId = codec.id(text(source, "outflowId"));
            Long stockId = codec.id(text(source, "stockId"));
            String mutation = text(source, "mutationKey");
            var out = expected.remove(flowId);
            BigDecimal quantity = codec.quantity(codec.amount(new BigDecimal(text(source, "quantity"))), false, "出生原量");
            if (out == null || !Objects.equals(out.stockId(), stockId) || !stocks.add(stockId)
                    || !mutations.add(mutation) || quantity.compareTo(out.quantity()) != 0
                    || !Objects.equals(out.materialUnit(), text(source, "unit")))
                throw new CheckedException("出生逐源集合、实际OUT和原量不一致");

            MesWarehouseFeedBirthLineEntity line = new MesWarehouseFeedBirthLineEntity();
            line.setId(IdWorker.getId());
            line.setTenantId(tenant);
            line.setFactoryCode(factory);
            line.setBirthId(birth.getId());
            line.setSequenceNo(children.size() + 1);
            line.setStockId(stockId);
            line.setOutflowId(flowId);
            line.setMutationKey(mutation);
            line.setQuantity(quantity.stripTrailingZeros());
            line.setUnit(text(source, "unit"));
            line.setSourceSnapshot(text(source, "sourceSnapshot"));
            line.setAfterSnapshot(text(source, "afterSnapshot"));
            line.setFlowSnapshot(text(source, "flowSnapshot"));
            TreeMap<String, Object> document = new TreeMap<>();
            document.put("id", line.getId().toString());
            document.put("tenantId", tenant.toString());
            document.put("factoryCode", factory);
            document.put("birthId", birth.getId().toString());
            document.put("sequenceNo", line.getSequenceNo().toString());
            document.put("stockId", stockId.toString());
            document.put("outflowId", flowId.toString());
            document.put("mutationKey", mutation);
            document.put("quantity", codec.amount(line.getQuantity()));
            document.put("unit", line.getUnit());
            document.put("sourceSnapshot", line.getSourceSnapshot());
            document.put("afterSnapshot", line.getAfterSnapshot());
            document.put("flowSnapshot", line.getFlowSnapshot());
            line.setEvidenceVersion(LINE_VERSION);
            line.setEvidenceSnapshot(codec.snapshot(document));
            line.setEvidenceFingerprint(codec.fingerprint(LINE_VERSION, line.getEvidenceSnapshot()));
            codec.objectDocument(line.getEvidenceSnapshot(), 2 * 1024 * 1024);
            children.add(line);
            originalLines.add(line.getEvidenceSnapshot());
            total = total.add(quantity);
        }
        if (!expected.isEmpty() || total.compareTo(feed.getFeedNum()) != 0)
            throw new CheckedException("出生逐源原量全集不守恒");

        TreeMap<String, Object> evidence = new TreeMap<>();
        evidence.put("id", birth.getId().toString());
        evidence.put("tenantId", tenant.toString());
        evidence.put("factoryCode", factory);
        evidence.put("feedId", feed.getId().toString());
        evidence.put("actorId", actor.getId().toString());
        evidence.put("taskId", task.getId().toString());
        evidence.put("arrangeId", arrange.getId().toString());
        evidence.put("quantity", codec.amount(birth.getQuantity()));
        evidence.put("unit", birth.getUnit());
        evidence.put("receivingWorksiteCode", birth.getReceivingWorksiteCode());
        evidence.put("feedSnapshot", birth.getFeedSnapshot());
        evidence.put("bomSnapshot", birth.getBomSnapshot());
        evidence.put("arrangeSnapshot", birth.getArrangeSnapshot());
        evidence.put("lines", originalLines);
        birth.setEvidenceVersion(VERSION);
        birth.setEvidenceSnapshot(codec.snapshot(evidence));
        birth.setEvidenceFingerprint(codec.fingerprint(VERSION, birth.getEvidenceSnapshot()));
        codec.objectDocument(birth.getEvidenceSnapshot(), MAX_DOCUMENT);
		if (baseMapper.insertRecordedBirth(feed.getId(), tenant, factory, birth.getEvidenceSnapshot(),
				birth.getEvidenceFingerprint()) != 1
				|| lines.insertAll(codec.snapshot(originalLines), actor.getUsername(), feed.getFeedTime()) != children.size()) {
			throw new CheckedException("投料出生必要主/子账写入失败，实际领出已回滚");
		}
        verify(birth, children);
        return birth;
    }

    /**
     * 回读原raw/SHA和逐源全集；不能只看insert返回行数就接受静默数值转换。
     */
    private void verify(MesWarehouseFeedBirthEntity expected, List<MesWarehouseFeedBirthLineEntity> children) {
        var stored = getById(expected.getId());
        if (stored == null || !Objects.equals(stored.getEvidenceSnapshot(), expected.getEvidenceSnapshot())
                || !Objects.equals(stored.getEvidenceFingerprint(), expected.getEvidenceFingerprint())
                || !Objects.equals(stored.getEvidenceVersion(), VERSION)
                || !Objects.equals(stored.getFeedId(), expected.getFeedId())
                || !Objects.equals(stored.getTenantId(), expected.getTenantId())
                || !Objects.equals(stored.getFactoryCode(), expected.getFactoryCode())
                || !Objects.equals(stored.getSourceCount(), children.size())
                || stored.getQuantity() == null || stored.getQuantity().compareTo(expected.getQuantity()) != 0)
            throw new CheckedException("投料出生主账原证据或精度回读不一致");
        var actual = lines.selectList(new LambdaQueryWrapper<MesWarehouseFeedBirthLineEntity>()
                .eq(MesWarehouseFeedBirthLineEntity::getTenantId, expected.getTenantId())
                .eq(MesWarehouseFeedBirthLineEntity::getFactoryCode, expected.getFactoryCode())
                .eq(MesWarehouseFeedBirthLineEntity::getBirthId, expected.getId())
                .orderByAsc(MesWarehouseFeedBirthLineEntity::getSequenceNo).last("LIMIT 5001"));
        if (actual.size() != children.size()) throw new CheckedException("投料出生逐源原账缺失或多余");
        for (int index = 0; index < children.size(); index++) {
            var line = actual.get(index);
            var original = children.get(index);
            if (!Objects.equals(line.getId(), original.getId())
                    || !Objects.equals(line.getEvidenceSnapshot(), original.getEvidenceSnapshot())
                    || !Objects.equals(line.getEvidenceFingerprint(), original.getEvidenceFingerprint())
                    || line.getQuantity() == null || line.getQuantity().compareTo(original.getQuantity()) != 0)
                throw new CheckedException("投料出生逐源原证据或精度回读不一致");
        }
    }

    private String text(Map<String, Object> facts, String key) {
        String value = nullableText(facts, key);
        if (value == null) throw new CheckedException("投料出生缺实际原字段" + key);
        return value;
    }

    private String nullableText(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        if (value != null && !(value instanceof String))
            throw new CheckedException("投料出生原字段不能隐式转成文本：" + key);
        return (String) value;
    }
}
