package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.dto.IotMesRetryCommand;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import com.kitex.mom.mes.api.feign.RemoteMesAutoReportService;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.dto.MesAutoReportResultDTO;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.kitex.mom.iot.service.impl.IotAlarmService.require;

@Service @RequiredArgsConstructor @Slf4j
public class IotMesOutboxService {
    private final IotMesOutboxMapper boxes;
    private final IotMesOutboxOperationMapper operations;
    private final IotDeviceEventMapper events;
    private final IotDeviceMapper devices;
    private final IotDeviceDataAccess access;
    private final RemoteMesAutoReportService mes;
    private final PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Value("${mom.iot.mes-outbox.enabled:false}")
    private boolean schedulerEnabled;

    /** 原始事件与箱内事实同事务提交，绝不调用远端。历史记录只允许显式、有原因地补建。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public IotMesOutbox enqueue(IotDeviceEventEntity event) {
        require(event != null && event.getId()!=null && Boolean.TRUE.equals(event.getScopeVerified())
                && "PRODUCTION_OUTPUT".equalsIgnoreCase(event.getEventType()), "只接受已核验归属的生产产量原始事件");
        scope(event.getTenantId(),event.getFactoryCode());
        String snapshot=snapshot(event);
        var old=bySource(event.getId());
        if(old!=null) {
            require(Objects.equals(old.getPayloadJson(),snapshot),"原始事实与冻结投递事实不同，禁止覆盖");
            return old;
        }
        var row=new IotMesOutbox();
        row.setTenantId(event.getTenantId()); row.setFactoryCode(event.getFactoryCode());
        row.setSourceId(event.getId()); row.setDeviceId(event.getDeviceId()); row.setSourceEventId("IOT_EVENT:"+event.getId());
        row.setPayloadJson(snapshot); row.setFingerprint(DigestUtil.sha256Hex(snapshot)); row.setStatus("PENDING");
        row.setAttemptCount(0); row.setCycleAttempts(0); row.setNextAttemptTime(LocalDateTime.now());
        require(boxes.insert(row)==1,"设备生产事件待投递事实保存失败");
        audit(row,"ENQUEUE:"+row.getId(),"ENQUEUE","原始事件与待投递事实已冻结",null,null);
        // 不覆盖历史成功/忽略/失败回执。新事件只标记排队，不假称报工成功。
        if(event.getMesForwardStatus()==null) rawStatus(row,"PENDING","等待可靠投递至MES",null);
        return row;
    }

    public Map<String,Object> detail(Long eventId,long current) {
        authorized(eventId,"read");
        var box=bySource(eventId);
        if(box==null)return Map.of("exists",false,"schedulerEnabled",schedulerEnabled,"message","尚无投递箱；历史事件不会自动补报");
        var page=operations.selectPage(new Page<>(Math.max(1,current),20),new LambdaQueryWrapper<IotMesOutboxOperation>()
                .eq(IotMesOutboxOperation::getOutboxId,box.getId()).orderByDesc(IotMesOutboxOperation::getId));
        var blockers=boxes.selectList(new LambdaQueryWrapper<IotMesOutbox>()
                .select(IotMesOutbox::getSourceId,IotMesOutbox::getStatus,IotMesOutbox::getLastError)
                .eq(IotMesOutbox::getDeviceId,box.getDeviceId()).eq(IotMesOutbox::getFactoryCode,box.getFactoryCode())
                .lt(IotMesOutbox::getSourceId,box.getSourceId()).ne(IotMesOutbox::getStatus,"DELIVERED")
                .orderByAsc(IotMesOutbox::getSourceId).last("LIMIT 1"));
        return Map.of("exists",true,"schedulerEnabled",schedulerEnabled,"outbox",box,"operations",page,"blockers",blockers);
    }

    @Transactional(rollbackFor=Exception.class)
    public IotDeviceEventEntity retry(Long eventId,IotMesRetryCommand command) {
        require(command!=null && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length()<=64
                && StrUtil.isNotBlank(command.getReason()) && command.getReason().length()<=1000,"重试必须提供永久请求号和原因（最多1000字）");
        var event=authorized(eventId,"write");
        Long actor=SecurityUtils.getUser().getId();
        var replay=operations.selectOne(new LambdaQueryWrapper<IotMesOutboxOperation>().eq(IotMesOutboxOperation::getRequestId,command.getRequestId()));
        if(replay!=null) {
            var original=boxes.selectById(replay.getOutboxId());
            require(original!=null && Objects.equals(original.getSourceId(),eventId) && "MANUAL_RETRY".equals(replay.getAction())
                    && Objects.equals(replay.getOperatorId(),actor) && Objects.equals(replay.getReason(),command.getReason()),"请求号已被不同操作占用");
            return events.selectById(eventId);
        }
        var existing=bySource(eventId);
        if(existing==null) {
            require(!Set.of("SUCCESS","IGNORED").contains(StrUtil.blankToDefault(event.getMesForwardStatus(),"")),
                    "历史成功或忽略事件不得补报；请在MES台账核验回执");
            existing=enqueue(event);
        }
        var row=lock(existing.getId());
        require(!"DELIVERED".equals(row.getStatus()),"MES已受理；业务失败或忽略请在MES自动报工台账处理，不能重复投递代替业务审批");
        require(!"SENDING".equals(row.getStatus()) || !row.getLeaseUntil().isAfter(LocalDateTime.now()),"正在投递，请等待当前租约结束");
        require(Objects.equals(snapshot(event),row.getPayloadJson()),"原始事实已变更，禁止重新冻结覆盖");
        row.setStatus("PENDING");row.setCycleAttempts(0);row.setNextAttemptTime(LocalDateTime.now());
        row.setLeaseUntil(null);row.setLeaseToken(null);row.setLastError(null);save(row);
        rawStatus(row,"PENDING","人工申请重新投递，尚未确认MES报工结果",null);
        audit(row,command.getRequestId(),"MANUAL_RETRY",command.getReason(),actor,null);
        return events.selectById(eventId);
    }

    /** 仅由后台调度调用。恢复全部上下文，禁止继承跳过租户或工厂的标志。 */
    public void processDue() {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"不能在业务事务中执行远端投递");
        Long previousTenant=TenantContextHolder.getTenantId(); boolean skipTenant=TenantContextHolder.getTenantSkip();
        String previousFactory=FactoryContextHolder.getFactoryCode(); boolean skipFactory=Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();FactoryContextHolder.clear();
            for(var candidate:boxes.due()) {
                try {
                    TenantContextHolder.setTenantId(candidate.getTenantId());FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                    dispatch(candidate.getId());
                } catch(Exception ex) { log.warn("IoT投递执行失败 outboxId={}，异常类型={}",candidate.getId(),ex.getClass().getSimpleName()); }
                finally {TenantContextHolder.clear();FactoryContextHolder.clear();}
            }
        } finally {
            TenantContextHolder.setTenantId(previousTenant);if(skipTenant)TenantContextHolder.setTenantSkip();
            FactoryContextHolder.setFactoryCode(previousFactory);if(skipFactory)FactoryContextHolder.setFactorySkip();
        }
    }

    private void dispatch(Long id) {
        var claimed=tx().execute(txStatus->{
            var row=lock(id);LocalDateTime now=LocalDateTime.now();
            if(Set.of("DELIVERED","DEAD").contains(row.getStatus()))return null;
            if("SENDING".equals(row.getStatus()) && row.getLeaseUntil().isAfter(now))return null;
            if(row.getNextAttemptTime()!=null && row.getNextAttemptTime().isAfter(now))return null;
            if(row.getCycleAttempts()>=5) {
                row.setStatus("DEAD");row.setLeaseToken(null);row.setLeaseUntil(null);row.setNextAttemptTime(null);
                row.setLastError("连续投递次数已用尽，结果可能未知；请人工核验后重试同一事件");save(row);
                rawStatus(row,"DEAD",row.getLastError(),null);audit(row,"EXHAUST:"+row.getAttemptCount(),"EXHAUST",row.getLastError(),null,null);return null;
            }
            row.setStatus("SENDING");row.setLeaseToken(UUID.randomUUID().toString());row.setLeaseUntil(now.plusMinutes(2));
            row.setAttemptCount(row.getAttemptCount()+1);row.setCycleAttempts(row.getCycleAttempts()+1);row.setNextAttemptTime(null);save(row);
            audit(row,"CLAIM:"+row.getLeaseToken(),"CLAIM","第"+row.getAttemptCount()+"次投递（含崩溃或失联尝试）",null,null);
            return row;
        });
        if(claimed==null)return;
        MesAutoReportResultDTO receipt=null;String error=null;
        try {
            require(Objects.equals(claimed.getFingerprint(),DigestUtil.sha256Hex(claimed.getPayloadJson())),"冻结载荷指纹不匹配");
            var source=events.selectById(claimed.getSourceId());
            require(source!=null && Boolean.TRUE.equals(source.getScopeVerified()) && Objects.equals(snapshot(source),claimed.getPayloadJson()),"原始事件归属或事实已变更");
            var device=devices.selectById(claimed.getDeviceId());
            require(device!=null && Objects.equals(device.getFactoryCode(),source.getFactoryCode())
                    && Objects.equals(device.getDeviceCode(),source.getDeviceCode()) && Objects.equals(device.getProductId(),source.getProductId())
                    && Objects.equals(device.getProductCode(),source.getProductCode()),"设备身份或产品归属已变更，请人工核实");
            var payload=command(source);
            var lookup=mes.receipt(payload);
            require(lookup!=null && lookup.isOk(),"MES回执核验失败");
            receipt=lookup.getData();
            if(receipt==null || "RECEIVED".equals(receipt.getStatus())) {
                var response=mes.accept(payload);
                require(response!=null && response.isOk(),"MES未确认受理，请保留原事件号重试");receipt=response.getData();
            }
            require(receipt!=null && Objects.equals(receipt.getSourceEventId(),claimed.getSourceEventId()) && receipt.getEventId()!=null,
                    "MES回执来源事件不一致");
            require(Set.of("SUCCESS","FAILED","IGNORED").contains(StrUtil.blankToDefault(receipt.getStatus(),"")),"MES尚未形成终态回执");
            require(!"SUCCESS".equals(receipt.getStatus()) || receipt.getReportId()!=null,"MES成功回执缺少报工记录");
        } catch(Exception ex) { error=safe(ex.getMessage()); }
        final var accepted=receipt;final String failure=error;
        tx().executeWithoutResult(txStatus->{
            var row=lock(id);
            if(!"SENDING".equals(row.getStatus()) || !Objects.equals(row.getLeaseToken(),claimed.getLeaseToken()))return;
            row.setLeaseToken(null);row.setLeaseUntil(null);
            if(failure==null) {
                row.setStatus("DELIVERED");row.setReceiptJson(JSONUtil.toJsonStr(accepted));row.setDeliveredTime(LocalDateTime.now());
                row.setNextAttemptTime(null);row.setLastError(null);save(row);
                rawStatus(row,accepted.getStatus(),accepted.getMessage(),accepted);
                audit(row,"ACK:"+claimed.getLeaseToken(),"RECEIPT","已取得MES终态回执；投递完成不等于报工成功",null,row.getReceiptJson());
            } else {
                boolean dead=row.getCycleAttempts()>=5;row.setStatus(dead?"DEAD":"RETRY");row.setLastError(failure);
                row.setNextAttemptTime(dead?null:LocalDateTime.now().plusSeconds(30L << Math.min(row.getCycleAttempts(),5)));save(row);
                rawStatus(row,row.getStatus(),failure,null);
                audit(row,"FAIL:"+claimed.getLeaseToken(),"FAILURE",failure,null,accepted==null?null:JSONUtil.toJsonStr(accepted));
            }
        });
    }

    private IotDeviceEventEntity authorized(Long eventId,String permission) {
        access.tenant();var source=events.selectById(eventId);
        require(source!=null && Boolean.TRUE.equals(source.getScopeVerified()),"事件不存在或归属未核验");
        var device=access.device(source.getDeviceId(),null,permission);
        require(Objects.equals(device.getFactoryCode(),source.getFactoryCode()),"历史事件工厂归属不一致");return source;
    }
    private IotMesOutbox bySource(Long sourceId) {
        return boxes.selectOne(new LambdaQueryWrapper<IotMesOutbox>().eq(IotMesOutbox::getSourceId,sourceId));
    }
    private IotMesOutbox lock(Long id) {
        var row=boxes.selectOne(new LambdaQueryWrapper<IotMesOutbox>().eq(IotMesOutbox::getId,id).last("FOR UPDATE"));
        require(row!=null,"投递记录不存在");scope(row.getTenantId(),row.getFactoryCode());return row;
    }
    private void scope(Long tenant,String factory) {
        require(tenant!=null && Objects.equals(tenant,TenantContextHolder.getTenantId()) && !TenantContextHolder.getTenantSkip()
                && StrUtil.isNotBlank(factory) && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && (StrUtil.isBlank(FactoryContextHolder.getFactoryCode()) || Objects.equals(factory,FactoryContextHolder.getFactoryCode())),"投递必须绑定可信租户和工厂");
    }
    private void save(IotMesOutbox row) {require(boxes.updateById(row)==1,"投递结果保存失败");}
    private TransactionTemplate tx() {return new TransactionTemplate(transactionManager);}
    private void rawStatus(IotMesOutbox row,String status,String message,MesAutoReportResultDTO receipt) {
        var update=new LambdaUpdateWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getId,row.getSourceId())
                .eq(IotDeviceEventEntity::getTenantId,row.getTenantId()).eq(IotDeviceEventEntity::getFactoryCode,row.getFactoryCode())
                .eq(IotDeviceEventEntity::getScopeVerified,true).set(IotDeviceEventEntity::getMesForwardStatus,status)
                .set(IotDeviceEventEntity::getMesForwardMessage,StrUtil.isBlank(message)?"MES状态："+status:safe(message));
        if(receipt!=null) update.set(IotDeviceEventEntity::getMesAutoReportEventId,receipt.getEventId())
                .set(IotDeviceEventEntity::getMesReportId,receipt.getReportId());
        if(!"PENDING".equals(status))update.set(IotDeviceEventEntity::getMesForwardTime,LocalDateTime.now());
        int changed=events.update(null,update);
        // 来源被逻辑删除或归属已变化时，只落失败箱和审计，不写入别厂原始行，也不能无限卡在过期租约。
        require(changed==1 || (receipt==null && Set.of("RETRY","DEAD").contains(status)),"原始事件投递结果回写失败");
    }
    private void audit(IotMesOutbox row,String key,String action,String reason,Long actor,String receipt) {
        var op=new IotMesOutboxOperation();op.setTenantId(row.getTenantId());op.setFactoryCode(row.getFactoryCode());
        op.setOutboxId(row.getId());op.setRequestId(key);op.setAction(action);op.setReason(safe(reason));op.setOperatorId(actor);
        op.setResultStatus(row.getStatus());op.setReceiptJson(receipt);op.setOperationTime(LocalDateTime.now());
        require(operations.insert(op)==1,"投递审计写入失败");
    }
    private String snapshot(IotDeviceEventEntity e) {
        return JSONUtil.toJsonStr(Arrays.asList(e.getId(),e.getTenantId(),e.getFactoryCode(),e.getDeviceId(),e.getDeviceCode(),
                e.getProductId(),e.getProductCode(),e.getIdentifier(),e.getEventType(),e.getEventTime()==null?null:e.getEventTime().toString(),e.getEventData()));
    }
    private MesAutoReportEventDTO command(IotDeviceEventEntity event) {
        require(event.getEventTime()!=null && (event.getEventData()==null || event.getEventData().length()<=20000),"事件时间缺失或MES报文超过20000字，不裁剪原始事实");
        JSONObject data=StrUtil.isBlank(event.getEventData())?new JSONObject():JSONUtil.parseObj(event.getEventData());
        var dto=new MesAutoReportEventDTO();dto.setSourceEventId("IOT_EVENT:"+event.getId());dto.setDeviceCode(event.getDeviceCode());
        dto.setEventIdentifier(event.getIdentifier());dto.setEventTime(event.getEventTime().truncatedTo(ChronoUnit.SECONDS));
        for(String key:List.of("value","quantity","counter","pulseCount","pulse_count")) {
            String value=data.getStr(key);if(StrUtil.isBlank(value))continue;
            try {dto.setValue(new BigDecimal(value.trim()));}catch(NumberFormatException ex){throw new IllegalArgumentException("设备产量字段不是有效数值："+key);}
            break;
        }
        dto.setArrangeCode(first(data,"arrangeCode","arrange_code"));dto.setTransferCode(first(data,"transferCode","transfer_code","barcode"));
        dto.setRawPayload(event.getEventData());dto.setFactoryCode(event.getFactoryCode());return dto;
    }
    private String first(JSONObject data,String... keys) {for(String key:keys){String value=data.getStr(key);if(StrUtil.isNotBlank(value))return value.trim();}return null;}
    private String safe(String value) {return StrUtil.isBlank(value)?"投递失败，请核验服务状态":value.substring(0,Math.min(value.length(),1000));}
}
