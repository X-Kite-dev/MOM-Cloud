package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.api.feign.RemoteWarehousePickingBillService;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.dto.MesWmsIssueEvent;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.wms.dto.WmsMesIssueCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WmsMesIssueOutboxService {
    private final WmsMesIssueOutboxMapper mapper;
    private final WmsMesIssueOperationMapper operationMapper;
    private final WmsOutBillMapper billMapper;
    private final WmsOutBillDetailMapper detailMapper;
    private final WmsOutNoticeMapper noticeMapper;
    private final RemoteWarehousePickingBillService mesService;
    private final RemoteMdmFactoryAccessService factoryAccessService;
    private final PlatformTransactionManager transactionManager;

    /**
     * 生产审核/移动出库业务事务内只建箱，不调用远端。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WmsOutBillEntity bill) {
        require(bill != null && bill.getId() != null, "生产发料缺少出库单");
        WmsOutNoticeEntity notice = bill.getNoticeId() == null ? null : noticeMapper.selectById(bill.getNoticeId());
        if (notice == null) {
            require(!DocumentEnum.MES_PICKING.getCode().equals(bill.getDocumentTable()), "MES来源发料缺少出库通知");
            return;
        }
        if (!DocumentEnum.MES_PICKING.getCode().equals(notice.getDocumentTable())) return;
        enqueueEvent(buildEvent(bill.getId()));
    }

    private WmsMesIssueOutboxEntity enqueueEvent(MesWmsIssueEvent event) {
        WmsMesIssueOutboxEntity old = mapper.selectOne(new LambdaQueryWrapper<WmsMesIssueOutboxEntity>()
                .eq(WmsMesIssueOutboxEntity::getWmsBillId, event.getWmsBillId()));
        if (old != null) {
            require(Objects.equals(old.getFingerprint(), event.getFingerprint()), "同一出库单已有不同冻结发料事实，不可覆盖");
            return old;
        }
        WmsMesIssueOutboxEntity row = new WmsMesIssueOutboxEntity().setEventId(event.getEventId())
                .setWmsBillId(event.getWmsBillId()).setWmsBillNo(event.getWmsBillNo()).setPickingBillId(event.getCallback().getId())
                .setFingerprint(event.getFingerprint()).setPayloadJson(JSONUtil.toJsonStr(event))
                .setDetailCount(event.getCallback().getMatDetails().size()).setStatus("PENDING")
                .setAttemptCount(0).setCycleAttempts(0).setNextAttemptTime(LocalDateTime.now()).setFactoryCode(event.getFactoryCode());
        require(mapper.insert(row) == 1, "WMS发料待投递事实保存失败，出库事务回滚");
        audit(row, "ENQUEUE:" + row.getId(), "ENQUEUE", null, "发料与Outbox同事务提交");
        return row;
    }

    public Page<WmsMesIssueOutboxEntity> page(String factory, String billNo, String status, long current, long size) {
        access(factory, "read");
        return mapper.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<WmsMesIssueOutboxEntity>()
                        .select(WmsMesIssueOutboxEntity.class, field -> !Set.of("payload_json", "receipt_json").contains(field.getColumn()))
                        .eq(WmsMesIssueOutboxEntity::getFactoryCode, factory)
                        .like(StrUtil.isNotBlank(billNo), WmsMesIssueOutboxEntity::getWmsBillNo, billNo)
                        .eq(StrUtil.isNotBlank(status), WmsMesIssueOutboxEntity::getStatus, status)
                        .orderByDesc(WmsMesIssueOutboxEntity::getId));
    }

    public List<WmsMesIssueOperationEntity> operations(Long id) {
        WmsMesIssueOutboxEntity row = find(id);
        access(row.getFactoryCode(), "read");
        return operationMapper.selectList(new LambdaQueryWrapper<WmsMesIssueOperationEntity>()
                .eq(WmsMesIssueOperationEntity::getOutboxId, id).orderByAsc(WmsMesIssueOperationEntity::getId));
    }

    public WmsMesIssueOutboxEntity detail(Long id) {
        WmsMesIssueOutboxEntity row = find(id);
        access(row.getFactoryCode(), "read");
        return row;
    }

    /**
     * 只读实时核验，不重复创建通知，也不把查询失败改写为已收料。
     */
    public MesWmsIssueEvent.Receipt evidence(Long id) {
        WmsMesIssueOutboxEntity row = detail(id);
        require(Objects.equals(buildEvent(row.getWmsBillId()).getFingerprint(), row.getFingerprint()), "WMS当前事实与冻结事件不一致");
        R<MesWmsIssueEvent.Receipt> response = mesService.issueEventReceipt(row.getEventId());
        require(response != null && response.isOk(), "MES永久回执或关联通知核验失败");
        validateReceipt(row, response.getData());
        return response.getData();
    }

    /**
     * 有真实已完成出库来源的历史补建；不合成历史出库、签收或库存。
     */
    public WmsMesIssueOutboxEntity backfill(WmsMesIssueCommand command) {
        validate(command);
        require(command.getWmsBillId() != null && command.getOutboxId() == null, "补建须指定WMS出库单ID");
        WmsMesIssueOutboxEntity row = tx().execute(status -> {
            WmsOutBillEntity bill = billMapper.selectOne(new LambdaQueryWrapper<WmsOutBillEntity>()
                    .eq(WmsOutBillEntity::getId, command.getWmsBillId()).last("FOR UPDATE"));
            require(bill != null, "WMS出库单不存在");
            access(bill.getFactoryCode(), "write");
            WmsMesIssueOutboxEntity event = enqueueEvent(buildEvent(bill.getId()));
            if (!manualReplay(command, event, "BACKFILL"))
                audit(event, command.getRequestId(), "BACKFILL", event.getStatus(), command.getReason());
            return event;
        });
        if ("DELIVERED".equals(row.getStatus())) evidence(row.getId());
        return dispatch(row.getId());
    }

    public WmsMesIssueOutboxEntity retry(WmsMesIssueCommand command) {
        validate(command);
        require(command.getOutboxId() != null && command.getWmsBillId() == null, "续跑须指定投递事件ID");
        WmsMesIssueOutboxEntity row = tx().execute(status -> {
            WmsMesIssueOutboxEntity locked = lock(command.getOutboxId());
            access(locked.getFactoryCode(), "write");
            if (manualReplay(command, locked, "MANUAL_RETRY")) return locked;
            require(!"SENDING".equals(locked.getStatus()) || locked.getLeaseUntil() == null
                    || !locked.getLeaseUntil().isAfter(LocalDateTime.now()), "发料事件正由其他投递者处理，请等待租约结束");
            String before = locked.getStatus();
            if (!"DELIVERED".equals(before)) {
                locked.setStatus("PENDING").setCycleAttempts(0).setNextAttemptTime(LocalDateTime.now())
                        .setLeaseToken(null).setLeaseUntil(null);
                save(locked);
            }
            audit(locked, command.getRequestId(), "MANUAL_RETRY", before, command.getReason());
            return locked;
        });
        if ("DELIVERED".equals(row.getStatus())) evidence(row.getId());
        return dispatch(row.getId());
    }

    public int processDue(int limit) {
        int delivered = 0;
        for (WmsMesIssueOutboxEntity candidate : mapper.dueAcrossTenants(Math.min(100, Math.max(1, limit)))) {
            String previous = FactoryContextHolder.getFactoryCode();
            try {
                FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                WmsMesIssueOutboxEntity result = TenantBroker.applyAs(candidate.getTenantId(), ignored -> dispatch(candidate.getId()));
                if (result != null && "DELIVERED".equals(result.getStatus())) delivered++;
            } catch (RuntimeException ex) {
                log.warn("WMS发料事件调度失败，outboxId={}，原因={}", candidate.getId(), safeError(ex));
            } finally {
                FactoryContextHolder.setFactoryCode(previous);
            }
        }
        return delivered;
    }

    /**
     * 先提交本地租约，网络调用在数据库事务外；旧投递者不得覆盖新租约结果。
     */
    private WmsMesIssueOutboxEntity dispatch(Long id) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "禁止在WMS业务事务内投递MES发料事件");
        WmsMesIssueOutboxEntity claimed = tx().execute(status -> {
            WmsMesIssueOutboxEntity row = lock(id);
            LocalDateTime now = LocalDateTime.now();
            if ("DELIVERED".equals(row.getStatus()) || "DEAD".equals(row.getStatus())) return null;
            if ("SENDING".equals(row.getStatus()) && row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now))
                return null;
            if (!"SENDING".equals(row.getStatus()) && row.getNextAttemptTime() != null && row.getNextAttemptTime().isAfter(now))
                return null;
            String before = row.getStatus();
            row.setStatus("SENDING").setLeaseToken(UUID.randomUUID().toString()).setLeaseUntil(now.plusMinutes(2))
                    .setAttemptCount(row.getAttemptCount() + 1).setCycleAttempts(row.getCycleAttempts() + 1)
                    .setNextAttemptTime(null);
            save(row);
            audit(row, "CLAIM:" + row.getLeaseToken(), "CLAIM", before, "开始第" + row.getAttemptCount() + "次投递");
            return row;
        });
        if (claimed == null) return find(id);
        try {
            MesWmsIssueEvent payload = JSONUtil.toBean(claimed.getPayloadJson(), MesWmsIssueEvent.class);
            require(Objects.equals(payload.calculateFingerprint(), claimed.getFingerprint())
                    && Objects.equals(payload.getFingerprint(), claimed.getFingerprint()), "冻结的发料载荷已损坏");
            require(Objects.equals(buildEvent(claimed.getWmsBillId()).getFingerprint(), claimed.getFingerprint()),
                    "当前WMS出库事实与冻结事件不一致，禁止用新事实覆盖原事件");
            R<MesWmsIssueEvent.Receipt> lookup = mesService.issueEventReceipt(claimed.getEventId());
            require(lookup != null && lookup.isOk(), "MES永久回执查询失败");
            MesWmsIssueEvent.Receipt receipt = lookup.getData();
            if (receipt == null) {
                R<MesWmsIssueEvent.Receipt> response = mesService.receiveIssueEvent(payload);
                require(response != null && response.isOk(), "MES未成功接收发料通知");
                receipt = response.getData();
            }
            validateReceipt(claimed, receipt);
            MesWmsIssueEvent.Receipt accepted = receipt;
            return tx().execute(status -> {
                WmsMesIssueOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken()) || !"SENDING".equals(row.getStatus()))
                    return row;
                row.setStatus("DELIVERED").setReceiptJson(JSONUtil.toJsonStr(accepted)).setDeliveredTime(LocalDateTime.now())
                        .setLeaseToken(null).setLeaseUntil(null).setNextAttemptTime(null).setLastError(null);
                save(row);
                audit(row, "ACK:" + claimed.getLeaseToken(), "DELIVERED", "SENDING", "MES永久回执已确认；现场签收仍独立进行");
                return row;
            });
        } catch (Exception ex) {
            return tx().execute(status -> {
                WmsMesIssueOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken()) || !"SENDING".equals(row.getStatus()))
                    return row;
                boolean dead = row.getCycleAttempts() >= 5;
                row.setStatus(dead ? "DEAD" : "RETRY").setLastError(safeError(ex)).setLeaseToken(null).setLeaseUntil(null)
                        .setNextAttemptTime(dead ? null : LocalDateTime.now().plusSeconds(Math.min(1800, 30L << Math.min(row.getCycleAttempts(), 6))));
                save(row);
                audit(row, "FAIL:" + claimed.getLeaseToken(), "FAILURE", "SENDING", row.getLastError());
                return row;
            });
        }
    }

    private MesWmsIssueEvent buildEvent(Long billId) {
        WmsOutBillEntity bill = billMapper.selectById(billId);
        require(bill != null && OutBillTypeEnum.PRODUCE_OUT.getCode().equals(bill.getBillType())
                && Set.of("2", "12").contains(StrUtil.blankToDefault(bill.getState(), "")), "只允许已完成生产出库形成MES发料事件");
        WmsOutNoticeEntity notice = bill.getNoticeId() == null ? null : noticeMapper.selectById(bill.getNoticeId());
        require(notice != null && notice.getDocumentId() != null && DocumentEnum.MES_PICKING.getCode().equals(notice.getDocumentTable())
                && Objects.equals(notice.getFactoryCode(), bill.getFactoryCode()), "WMS出库缺少同厂MES领料来源");
        List<WmsOutBillDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                .eq(WmsOutBillDetailEntity::getBillId, billId).orderByAsc(WmsOutBillDetailEntity::getId));
        require(!details.isEmpty(), "WMS出库明细为空");
        List<MesWarehouseMaterialInBillEntity> materials = new ArrayList<>();
        for (WmsOutBillDetailEntity detail : details) {
            require(detail.getMatNum() != null && Double.isFinite(detail.getMatNum()) && detail.getMatNum() > 0,
                    "WMS出库数量缺失或非法");
            MesWarehouseMaterialInBillEntity material = new MesWarehouseMaterialInBillEntity();
            BeanUtil.copyProperties(detail, material, "id", "billId", "siteCode", "siteName", "containerCode",
                    "tenantId", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
            material.setWmsOutDetailId(detail.getId());
            material.setResourceNo(bill.getBillNo());
            material.setNoticeInNum(BigDecimal.valueOf(detail.getMatNum()));
            material.setInspectionStatus(detail.getQualityResult());
            material.setFactoryCode(bill.getFactoryCode());
            materials.add(material);
        }
        MesPickingWmsOutBillBackDTO callback = new MesPickingWmsOutBillBackDTO();
        callback.setId(notice.getDocumentId());
        callback.setMatCodes(materials.stream().map(MesWarehouseMaterialInBillEntity::getMatCode).distinct().toList());
        callback.setMatDetails(materials);
        MesWmsIssueEvent event = new MesWmsIssueEvent().setVersion(1).setWmsBillId(billId).setWmsBillNo(bill.getBillNo())
                .setEventId("WMS-MES-ISSUE:" + billId).setFactoryCode(bill.getFactoryCode()).setCallback(callback);
        event.setFingerprint(event.calculateFingerprint());
        return event;
    }

    private void validateReceipt(WmsMesIssueOutboxEntity row, MesWmsIssueEvent.Receipt receipt) {
        require(receipt != null && Objects.equals(receipt.getEventId(), row.getEventId())
                && Objects.equals(receipt.getFingerprint(), row.getFingerprint())
                && Objects.equals(receipt.getWmsBillId(), row.getWmsBillId())
                && Objects.equals(receipt.getPickingBillId(), row.getPickingBillId())
                && Objects.equals(receipt.getFactoryCode(), row.getFactoryCode())
                && Objects.equals(receipt.getDetailCount(), row.getDetailCount()) && receipt.getAcceptedTime() != null
                && receipt.getNoticeIds() != null && receipt.getNoticeIds().size() == row.getDetailCount()
                && receipt.getNoticeIds().stream().allMatch(Objects::nonNull)
                && receipt.getNoticeIds().stream().distinct().count() == row.getDetailCount(), "MES回执与冻结发料来源不一致");
    }

    private boolean manualReplay(WmsMesIssueCommand c, WmsMesIssueOutboxEntity row, String type) {
        WmsMesIssueOperationEntity old = operationMapper.selectOne(new LambdaQueryWrapper<WmsMesIssueOperationEntity>()
                .eq(WmsMesIssueOperationEntity::getRequestId, c.getRequestId()));
        if (old != null)
            require(Objects.equals(old.getOutboxId(), row.getId()) && Objects.equals(old.getOperationType(), type)
                    && Objects.equals(old.getDetail(), c.getReason()), "同一人工操作请求号不可改变目标、类型或原因");
        return old != null;
    }

    private void audit(WmsMesIssueOutboxEntity row, String request, String type, String before, String detail) {
        String actor = "SYSTEM";
        try {
            if (SecurityUtils.getUser() != null) actor = SecurityUtils.getUser().getUsername();
        } catch (Exception ignored) {
        }
        require(operationMapper.insert(new WmsMesIssueOperationEntity().setOutboxId(row.getId()).setRequestId(request)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(row.getStatus()).setDetail(detail)
                .setOperatorCode(actor).setOperationTime(LocalDateTime.now()).setFactoryCode(row.getFactoryCode())) == 1, "发料投递操作履历保存失败");
    }

    private WmsMesIssueOutboxEntity find(Long id) {
        require(id != null, "请选择投递事件");
        WmsMesIssueOutboxEntity row = mapper.selectById(id);
        require(row != null, "发料投递事件不存在");
        return row;
    }

    private WmsMesIssueOutboxEntity lock(Long id) {
        WmsMesIssueOutboxEntity row = mapper.selectOne(new LambdaQueryWrapper<WmsMesIssueOutboxEntity>()
                .eq(WmsMesIssueOutboxEntity::getId, id).last("FOR UPDATE"));
        require(row != null, "发料投递事件不存在");
        return row;
    }

    private void save(WmsMesIssueOutboxEntity row) {
        require(mapper.updateById(row) == 1, "投递状态保存失败");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String safeError(Exception ex) {
        String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return message.substring(0, Math.min(1000, message.length()));
    }

    private void access(String factory, String permission) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(selected, factory)), "投递事件不在当前工厂范围");
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null, "人工处理发料投递须登录");
        R<Boolean> response = factoryAccessService.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission(permission));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()), "未获该工厂权限或MDM无法核验");
    }

    private void validate(WmsMesIssueCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 64
                && StrUtil.isNotBlank(command.getReason()) && command.getReason().length() <= 500, "人工操作须提供不超过64字请求号及500字原因");
        require(!command.getRequestId().contains(":"), "人工请求号不可使用系统保留格式，请使用UUID");
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
