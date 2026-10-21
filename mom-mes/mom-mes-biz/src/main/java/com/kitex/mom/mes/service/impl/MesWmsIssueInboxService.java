package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWmsIssueEvent;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.entity.MesWmsIssueInboxEntity;
import com.kitex.mom.mes.mapper.MesWarehouseMaterialInBillMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.mapper.MesWmsIssueInboxMapper;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesWmsIssueInboxService {
    private final MesWmsIssueInboxMapper mapper;
    private final MesWarehousePickingBillMapper pickingMapper;
    private final MesWarehouseMaterialInBillMapper noticeMapper;
    private final MesWarehousePickingBillService pickingService;

    @Transactional(rollbackFor = Exception.class)
    public MesWmsIssueEvent.Receipt receive(MesWmsIssueEvent event) {
        require(event != null && Objects.equals(event.getFingerprint(), event.calculateFingerprint()), "WMS发料事件指纹不一致");
        MesWarehousePickingBillEntity picking = pickingMapper.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, event.getCallback().getId()).last("FOR UPDATE"));
        require(picking != null && Objects.equals(picking.getFactoryCode(), event.getFactoryCode()), "发料事件与MES领料工厂不一致");
        MesWmsIssueInboxEntity existing = mapper.selectOne(new LambdaQueryWrapper<MesWmsIssueInboxEntity>()
                .eq(MesWmsIssueInboxEntity::getEventId, event.getEventId()));
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint()), "同一发料事件不能覆盖不同来源事实");
            return receipt(existing.getEventId());
        }
        String frozenPayload = JSONUtil.toJsonStr(event);
        // 入站实体的本地ID、租户和收料状态绝不作为写入依据。
        event.getCallback().getMatDetails().forEach(d -> {
            d.setId(null);
            d.setTenantId(null);
            d.setDelFlag("0");
        });
        require(Boolean.TRUE.equals(pickingService.wmsOutBillBack(event.getCallback())), "MES发料通知接收失败");
        List<Long> sources = event.getCallback().getMatDetails().stream()
                .map(MesWarehouseMaterialInBillEntity::getWmsOutDetailId).sorted().toList();
        List<MesWarehouseMaterialInBillEntity> notices = noticeMapper.selectList(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .in(MesWarehouseMaterialInBillEntity::getWmsOutDetailId, sources)
                .orderByAsc(MesWarehouseMaterialInBillEntity::getWmsOutDetailId));
        require(notices.size() == sources.size() && notices.stream().allMatch(n -> Objects.equals(n.getBillId(), picking.getId())
                && Objects.equals(n.getFactoryCode(), event.getFactoryCode())), "发料事件未形成完整同源通知");
        MesWmsIssueEvent actual = JSONUtil.toBean(frozenPayload, MesWmsIssueEvent.class);
        actual.getCallback().setMatDetails(notices);
        require(Objects.equals(actual.calculateFingerprint(), event.getFingerprint()), "MES实际通知与冻结发料事件不一致，禁止返回成功回执");
        MesWmsIssueEvent.Receipt receipt = new MesWmsIssueEvent.Receipt().setEventId(event.getEventId())
                .setFingerprint(event.getFingerprint()).setWmsBillId(event.getWmsBillId()).setPickingBillId(picking.getId())
                .setFactoryCode(event.getFactoryCode()).setDetailCount(notices.size())
                .setNoticeIds(notices.stream().map(MesWarehouseMaterialInBillEntity::getId).toList())
                .setAcceptedTime(LocalDateTime.now());
        require(mapper.insert(new MesWmsIssueInboxEntity().setEventId(event.getEventId()).setWmsBillId(event.getWmsBillId())
                .setPickingBillId(picking.getId()).setFingerprint(event.getFingerprint()).setPayloadJson(frozenPayload)
                .setReceiptJson(JSONUtil.toJsonStr(receipt)).setAcceptedTime(receipt.getAcceptedTime())
                .setFactoryCode(event.getFactoryCode())) == 1, "MES发料永久回执保存失败");
        return receipt;
    }

    public MesWmsIssueEvent.Receipt receipt(String eventId) {
        require(eventId != null && !eventId.isBlank() && eventId.length() <= 64, "发料事件号为空或过长");
        MesWmsIssueInboxEntity row = mapper.selectOne(new LambdaQueryWrapper<MesWmsIssueInboxEntity>()
                .eq(MesWmsIssueInboxEntity::getEventId, eventId));
        if (row == null) return null;
        MesWmsIssueEvent.Receipt receipt = JSONUtil.toBean(row.getReceiptJson(), MesWmsIssueEvent.Receipt.class);
        require(receipt != null && Objects.equals(receipt.getEventId(), row.getEventId())
                && Objects.equals(receipt.getWmsBillId(), row.getWmsBillId())
                && Objects.equals(receipt.getPickingBillId(), row.getPickingBillId())
                && Objects.equals(receipt.getFactoryCode(), row.getFactoryCode())
                && Objects.equals(receipt.getFingerprint(), row.getFingerprint())
                && receipt.getAcceptedTime() != null && receipt.getNoticeIds() != null && !receipt.getNoticeIds().isEmpty()
                && Objects.equals(receipt.getDetailCount(), receipt.getNoticeIds().size())
                && receipt.getNoticeIds().stream().allMatch(Objects::nonNull)
                && receipt.getNoticeIds().stream().distinct().count() == receipt.getNoticeIds().size(), "MES发料回执内容异常");
        List<MesWarehouseMaterialInBillEntity> notices = noticeMapper.selectBatchIds(receipt.getNoticeIds());
        require(notices.size() == receipt.getNoticeIds().size() && notices.stream().allMatch(n ->
                        Objects.equals(n.getBillId(), row.getPickingBillId()) && Objects.equals(n.getFactoryCode(), row.getFactoryCode())),
                "MES回执关联的发料通知已缺失或来源异常，请先对账");
        MesWmsIssueEvent evidence = JSONUtil.toBean(row.getPayloadJson(), MesWmsIssueEvent.class);
        evidence.getCallback().setMatDetails(notices);
        require(Objects.equals(evidence.calculateFingerprint(), row.getFingerprint()), "MES回执关联的发料通知事实已变化");
        return receipt;
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
