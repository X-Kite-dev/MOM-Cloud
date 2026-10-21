package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmDevicePauseCommand;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.entity.MdmDevicePauseReceipt;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.mapper.MdmDeviceMapper;
import com.kitex.mom.mdm.mapper.MdmDevicePauseReceiptMapper;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * 两个实际设备行在同一MDM事务内核验、变更及留证，TPM不再分两次通用更新。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MdmDevicePauseService {
    private final MdmDeviceMapper devices;
    private final MdmDevicePauseReceiptMapper receipts;
    private final MdmFactoryModelMapper factories;
    private final MdmUserFactoryModelBindMapper bindings;
    private final SqlSessionTemplate session;

    @Transactional(rollbackFor = Exception.class)
    public MdmDevicePauseReceipt pause(MdmDevicePauseCommand c) {
        return execute(c, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public MdmDevicePauseReceipt close(MdmDevicePauseCommand c) {
        return execute(c, true);
    }

    private MdmDevicePauseReceipt execute(MdmDevicePauseCommand c, boolean closing) {
        String factory = access("write");
        require(c != null && text(c.getRequestId(), 64) && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}")
                && text(c.getDeviceCode(), 100) && text(c.getReason(), 500), "须明确设备、永久请求号及暂停依据");
        c.setDeviceCode(c.getDeviceCode().trim());
        c.setMoldCode(StrUtil.trimToNull(c.getMoldCode()));
        c.setReason(c.getReason().trim());
        require(c.getMoldCode() == null || text(c.getMoldCode(), 100) && !c.getMoldCode().equals(c.getDeviceCode()), "模具编码无效或与设备相同");
        var actor = SecurityUtils.getUser();
        // 保持首版PAUSE永久请求哈希不变；CLOSE使用不同命名空间内容，不能重放成暂停。
        String action = closing ? "CLOSE" : "PAUSE";
        String hash = hash(JSONUtil.toJsonStr(closing ? Arrays.asList(c, actor.getId(), "CLOSE") : Arrays.asList(c, actor.getId())));
        receipts.lockRequest("MDM-DEVICE-PAUSE:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + c.getRequestId());
        session.clearCache();
        var old = receipts.selectOne(scope(factory).eq(MdmDevicePauseReceipt::getRequestId, c.getRequestId()));
        if (old != null) {
            require(hash.equals(old.getFingerprint()) && action.equals(old.getAction()), "设备操作请求号已用于其他动作、内容或人员");
            return old;
        }
        var codes = new TreeSet<String>();
        codes.add(c.getDeviceCode());
        if (c.getMoldCode() != null) codes.add(c.getMoldCode());
        var rows = devices.selectList(new LambdaQueryWrapper<MdmDeviceEntity>().eq(MdmDeviceEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmDeviceEntity::getFactoryCode, factory).in(MdmDeviceEntity::getDeviceCode, codes).orderByAsc(MdmDeviceEntity::getId).last("FOR UPDATE"));
        require(rows.size() == codes.size() && rows.stream().map(MdmDeviceEntity::getDeviceCode).distinct().count() == codes.size(), "当前工厂设备/模具缺失或编码不唯一，不允许部分暂停");
        // 先核验全部对象，再修改任何对象；未知状态不能猜成已停止。
        for (var d : rows)
            require(Set.of("0", "1", "2", "3", "4", "5").contains(Objects.toString(d.getDeviceState(), "")), "设备状态缺失或未知，须现场核对");
        List<Object> evidence = new ArrayList<>();
        List<MdmDeviceEntity> changed = new ArrayList<>();
        for (var d : rows) {
            var before = facts(d);
            String target = closing ? "0" : "5";
            boolean transition = "4".equals(d.getDeviceState()) || closing && "5".equals(d.getDeviceState());
            String mode = transition ? "TRANSITION" : target.equals(d.getDeviceState()) ? (closing ? "ALREADY_IDLE" : "ALREADY_PAUSED") : "NON_RUNNING";
            if ("TRANSITION".equals(mode)) {
                require(devices.update(null, new LambdaUpdateWrapper<MdmDeviceEntity>().eq(MdmDeviceEntity::getId, d.getId())
                        .eq(MdmDeviceEntity::getTenantId, d.getTenantId()).eq(MdmDeviceEntity::getFactoryCode, factory)
                        .eq(MdmDeviceEntity::getDeviceState, d.getDeviceState()).set(MdmDeviceEntity::getDeviceState, target)) == 1, "设备状态转换未成功");
                session.clearCache();
                var stored = devices.selectById(d.getId());
                require(stored != null && target.equals(stored.getDeviceState()) && Objects.equals(stored.getDeviceUsable(), d.getDeviceUsable())
                        && Objects.equals(stored.getStatus(), d.getStatus()), "暂停结果核验失败或错误改变可用/启停标记");
                changed.add(stored);
                d = stored;
            }
            evidence.add(Map.of("mode", mode, "before", before, "after", facts(d)));
        }
        var r = new MdmDevicePauseReceipt();
        r.setTenantId(TenantContextHolder.getTenantId());
        r.setFactoryCode(factory);
        r.setRequestId(c.getRequestId());
        r.setFingerprint(hash);
        r.setDeviceCode(c.getDeviceCode());
        r.setMoldCode(c.getMoldCode());
        r.setOperatorId(actor.getId());
        r.setOperatorCode(actor.getUsername());
        r.setReason(c.getReason());
        r.setAction(action);
        r.setEvidenceJson(JSONUtil.toJsonStr(evidence));
        require(receipts.insert(r) == 1, "暂停永久回执保存失败");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (var d : changed)
                    try {
                        DynamicRefreshUtils.refreshMdm(d);
                    } catch (RuntimeException e) {
                        log.warn("设备暂停已提交但刷新通知失败，deviceId={}，类型={}", d.getId(), e.getClass().getSimpleName());
                    }
            }
        });
        return r;
    }

    public Page<MdmDevicePauseReceipt> page(String deviceCode, long page) {
        String factory = access("read");
        require(text(deviceCode, 100) && page > 0 && page <= 100000, "请明确设备编码及有效页码");
        return receipts.selectPage(new Page<>(page, 30), scope(factory).and(q -> q.eq(MdmDevicePauseReceipt::getDeviceCode, deviceCode.trim())
                .or().eq(MdmDevicePauseReceipt::getMoldCode, deviceCode.trim())).orderByDesc(MdmDevicePauseReceipt::getId));
    }

    private Map<String, Object> facts(MdmDeviceEntity d) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", d.getId().toString());
        m.put("tenantId", d.getTenantId().toString());
        m.put("factoryCode", d.getFactoryCode());
        m.put("deviceCode", d.getDeviceCode());
        m.put("deviceState", d.getDeviceState());
        m.put("deviceUsable", d.getDeviceUsable());
        m.put("status", d.getStatus());
        return m;
    }

    private String access(String permission) {
        var actor = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory), "设备暂停须实名登录并选择实际工厂");
        var fs = factories.selectList(new LambdaQueryWrapper<MdmFactoryModelEntity>().eq(MdmFactoryModelEntity::getCode, factory)
                .eq(MdmFactoryModelEntity::getType, "FACTORY").eq(MdmFactoryModelEntity::getStatus, true));
        require(fs.size() == 1, "实际工厂未启用或编码不唯一");
        var grants = bindings.selectList(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>().eq(MdmUserFactoryModelBindEntity::getUserId, actor.getId())
                .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, fs.get(0).getId()).eq(MdmUserFactoryModelBindEntity::getStatus, true));
        var required = FactoryModelPermissionEnum.getByCode(permission);
        require(grants.stream().anyMatch(b -> {
            var p = FactoryModelPermissionEnum.getByCode(Objects.toString(b.getPermissionType(), "").toLowerCase(Locale.ROOT));
            return p != null && p.hasPermission(required);
        }), "实际工厂权限不足");
        return factory;
    }

    private LambdaQueryWrapper<MdmDevicePauseReceipt> scope(String f) {
        return new LambdaQueryWrapper<MdmDevicePauseReceipt>()
                .eq(MdmDevicePauseReceipt::getTenantId, TenantContextHolder.getTenantId()).eq(MdmDevicePauseReceipt::getFactoryCode, f);
    }

    private boolean text(String s, int max) {
        return StrUtil.isNotBlank(s) && s.length() <= max;
    }

    private String hash(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
