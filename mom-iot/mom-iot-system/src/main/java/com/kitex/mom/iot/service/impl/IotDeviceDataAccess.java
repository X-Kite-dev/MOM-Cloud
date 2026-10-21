package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotProductEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotProductMapper;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Objects;
import static com.kitex.mom.iot.service.impl.IotAlarmService.require;

/** 业务数据读写权限；不用于无登录的网关采集线程。 */
@Service
@RequiredArgsConstructor
public class IotDeviceDataAccess {
    private final IotDeviceMapper devices;
    private final IotProductMapper products;
    private final RemoteMdmFactoryAccessService factories;
    public Long tenant() {
        require(TenantContextHolder.getTenantId()!=null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),"设备数据查询须明确租户且不能跳过数据隔离");
        require(SecurityUtils.getUser()!=null && SecurityUtils.getUser().getId()!=null,"设备数据查询须登录");
        return TenantContextHolder.getTenantId();
    }
    public String factory(String permission) {
        tenant(); String factory=FactoryContextHolder.getFactoryCode();
        check(factory,permission); return factory;
    }
    public void check(String factory,String permission) {
        tenant(); String selected=FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected)||Objects.equals(selected,factory)),"设备数据不属于当前工厂或未选择工厂");
        var r=factories.check(new MdmFactoryAccessCheck().setUserId(SecurityUtils.getUser().getId()).setFactoryCode(factory).setRequiredPermission(permission));
        require(r!=null&&r.isOk()&&Boolean.TRUE.equals(r.getData()),"缺少设备数据所属工厂直接权限或MDM不可核验");
    }
    public IotDeviceEntity device(Long id,String code,String permission) {
        tenant();
        require(id!=null||StrUtil.isNotBlank(code),"请指定精确设备，不允许无设备查询原始数据");
        // 编码不是全局标识；以编码查设备必须明确工厂。
        if(id==null)factory(permission);
        if(!"read".equals(permission))require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"设备数据写入必须在事务中核实来源");
        var found=devices.selectList(new LambdaQueryWrapper<IotDeviceEntity>().eq(id!=null,IotDeviceEntity::getId,id)
                .eq(StrUtil.isNotBlank(code),IotDeviceEntity::getDeviceCode,code).last("read".equals(permission)?"LIMIT 2":"LIMIT 2 FOR UPDATE"));
        require(found.size()==1,"设备不存在、编码不唯一或不在当前租户/工厂");
        var d=found.get(0);check(d.getFactoryCode(),permission);return d;
    }
    public IotProductEntity product(Long id) {
        tenant();require(id!=null,"请选择产品");
        var p=products.selectById(id);require(p!=null,"产品不存在或不在当前租户");check(p.getFactoryCode(),"read");return p;
    }
}
