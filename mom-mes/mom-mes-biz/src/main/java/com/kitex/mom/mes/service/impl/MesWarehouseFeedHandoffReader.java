package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/** 正式收料到投料的只读导航依据；不分配库存，不将累计签收量当作剩余可投量。 */
@Component
@RequiredArgsConstructor
public class MesWarehouseFeedHandoffReader {
    private static final List<String> OPEN_STATES = List.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE");
    private final MesWarehouseMaterialInBillMapper notices;
    private final MesWarehousePickingBillMapper pickings;
    private final MesTaskMapper tasks;
    private final MesMaterialReceiptMapper receipts;
    private final MesTaskArrangeMapper arrangements;
    private final MesPhysicalTransformCodec codec;

    public MesWarehouseFeedPreparationDTO.ReceiptSeed receiptSeed(Long noticeId, MesWarehouseFeedCommandDTO.Context context) {
        Long tenant = tenant(context);
        require(noticeId != null && noticeId > 0, "请选择正式收料通知");
        var notice = notices.selectOne(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getId, noticeId)
                .eq(MesWarehouseMaterialInBillEntity::getTenantId, tenant)
                .eq(MesWarehouseMaterialInBillEntity::getFactoryCode, context.factoryCode()));
        require(notice != null && Objects.equals(notice.getId(), noticeId)
                        && Objects.equals(notice.getTenantId(), tenant) && Objects.equals(notice.getFactoryCode(), context.factoryCode()),
                "正式收料通知未唯一归属当前租户工厂");
        require(notice.getInNum() != null && notice.getInNum().signum() > 0 && notice.getNoticeInNum() != null
                        && notice.getInNum().compareTo(notice.getNoticeInNum()) <= 0 && notice.getReceiptBaselineQuantity() != null
                        && notice.getReceiptBaselineQuantity().signum() >= 0,
                "尚无已核对的净签收数量，请先完成正式收料或历史对账");
        // 只读核账，不调用可能冻结历史基线的签收写入方法。
        var ledger = receipts.selectList(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getNoticeId, noticeId)
                .eq(MesMaterialReceiptEntity::getTenantId, tenant)
                .eq(MesMaterialReceiptEntity::getFactoryCode, context.factoryCode()).last("LIMIT 10001"));
        require(ledger.size() <= 10000, "单通知履历过多，须扩容后核对，不截断计算");
        BigDecimal net = notice.getReceiptBaselineQuantity();
        for (var row : ledger) {
            require(Objects.equals(row.getTenantId(), tenant) && Objects.equals(row.getFactoryCode(), context.factoryCode())
                            && Objects.equals(row.getNoticeId(), noticeId) && row.getQuantity() != null && row.getQuantity().signum() > 0
                            && List.of("RECEIVE", "REVERSE").contains(row.getOperationType()), "签收履历归属、操作类型或数量异常");
            net = "RECEIVE".equals(row.getOperationType()) ? net.add(row.getQuantity()) : net.subtract(row.getQuantity());
        }
        require(net.compareTo(notice.getInNum()) == 0, "净签收量与正式履历不一致，请先对账");
        String taskCode = null;
        if (notice.getBillId() != null) {
            var picking = pickings.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                    .eq(MesWarehousePickingBillEntity::getId, notice.getBillId()).eq(MesWarehousePickingBillEntity::getTenantId, tenant)
                    .eq(MesWarehousePickingBillEntity::getFactoryCode, context.factoryCode()));
            require(picking != null && Objects.equals(picking.getId(), notice.getBillId())
                            && Objects.equals(picking.getTenantId(), tenant) && Objects.equals(picking.getFactoryCode(), context.factoryCode()),
                    "关联领料单归属不可核验，不能丢弃原工单后改选");
            taskCode = codec.code(picking.getTaskCode(), "原生产工单", 100);
            var task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getCode, taskCode)
                    .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, context.factoryCode()));
            require(task != null && Objects.equals(task.getCode(), taskCode) && Objects.equals(task.getTenantId(), tenant)
                    && Objects.equals(task.getFactoryCode(), context.factoryCode()), "原生产工单不存在或归属已变化");
        }
        return new MesWarehouseFeedPreparationDTO.ReceiptSeed("MES_FEED_RECEIPT_SEED_V1", context, noticeId.toString(), taskCode != null, taskCode,
                codec.code(notice.getMatCode(), "来源物料", 100), optional(notice.getMatBatch(), "来源批次", 100),
                optional(notice.getMatBarcode(), "来源条码", 200), optional(notice.getSupplierCode(), "供应商", 100),
                optional(notice.getReservationNo(), "来源预留", 100));
    }

    public MesWarehouseFeedPreparationDTO.Candidates candidates(long current, long size, String taskCode, String keyword,
                                                               MesWarehouseFeedCommandDTO.Context context) {
        Long tenant = tenant(context);
        require(current >= 1 && current <= 100000 && size >= 1 && size <= 100, "派工分页参数无效");
        optional(taskCode, "原生产工单", 100);
        String search = keyword == null ? "" : keyword.trim();
        optional(search, "派工搜索条件", 100);
        var query = new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getTenantId, tenant)
                .eq(MesTaskArrangeEntity::getFactoryCode, context.factoryCode()).in(MesTaskArrangeEntity::getStatus, OPEN_STATES)
                .eq(taskCode != null && !taskCode.isEmpty(), MesTaskArrangeEntity::getTaskCode, taskCode)
                .and(!search.isEmpty(), group -> group.like(MesTaskArrangeEntity::getCode, search).or().like(MesTaskArrangeEntity::getRouteName, search))
                .orderByDesc(MesTaskArrangeEntity::getId);
        var page = arrangements.selectPage(Page.of(current, size), query);
        var records = page.getRecords().stream().map(row -> {
            require(Objects.equals(row.getTenantId(), tenant) && Objects.equals(row.getFactoryCode(), context.factoryCode())
                    && OPEN_STATES.contains(row.getStatus()) && (taskCode == null || taskCode.isEmpty() || taskCode.equals(row.getTaskCode())),
                    "候选派工归属或原工单不一致");
            require(row.getId() != null && row.getId() > 0, "派工主键无效");
            return new MesWarehouseFeedPreparationDTO.Candidate(row.getId().toString(), codec.code(row.getCode(), "派工编码", 100),
                    codec.code(row.getTaskCode(), "派工工单", 100), Objects.toString(row.getRouteName(), ""),
                    Objects.toString(row.getActualWorksiteCode(), ""), Objects.toString(row.getWorkerName(), ""), row.getStatus());
        }).toList();
        return new MesWarehouseFeedPreparationDTO.Candidates("MES_FEED_TARGETS_V1", context, current, size, page.getTotal(), records);
    }

    private Long tenant(MesWarehouseFeedCommandDTO.Context context) {
        require(context != null && MesWarehouseFeedCommandCodec.COMMAND_VERSION.equals(context.commandVersion()), "未取得正式投料身份");
        codec.code(context.factoryCode(), "实际工厂", 100);
        codec.id(context.actorUserId());
        return codec.id(context.tenantId());
    }

    private String optional(String value, String label, int limit) {
        if (value != null && !value.isEmpty()) codec.code(value, label, limit);
        return value;
    }
}
