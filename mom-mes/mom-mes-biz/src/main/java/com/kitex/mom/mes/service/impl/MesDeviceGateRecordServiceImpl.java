package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.entity.MesDeviceGateRecordEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesDeviceGateRecordMapper;
import com.kitex.mom.mes.service.MesDeviceGateRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MesDeviceGateRecordServiceImpl
        extends ServiceImpl<MesDeviceGateRecordMapper, MesDeviceGateRecordEntity>
        implements MesDeviceGateRecordService {

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(MesDeviceGateDecision decision, MesTaskArrangeEntity arrange, String factoryCode) {
        MomUser user = null;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ignored) {
            // 设备自动报工没有登录态，操作主体由阶段标识保留。
        }
        MesDeviceGateRecordEntity record = new MesDeviceGateRecordEntity()
                .setStage(decision.getStage())
                .setAllowed(decision.getAllowed())
                .setArrangeCode(decision.getArrangeCode())
                .setProduceTaskCode(decision.getProduceTaskCode())
                .setTaskCode(arrange == null ? null : arrange.getTaskCode())
                .setWorksiteCode(decision.getWorksiteCode())
                .setDeviceCode(decision.getDeviceCode())
                .setDeviceName(decision.getDeviceName())
                .setDeviceState(decision.getDeviceState())
                .setDeviceStateName(decision.getDeviceStateName())
                .setMessage(decision.getMessage())
                .setUnavailableWindowSnapshot(JSONUtil.toJsonStr(decision.getUnavailableWindows()))
                .setOperatorCode(user == null ? "SYSTEM" : user.getUsername())
                .setOperatorName(user == null ? "设备/系统" : user.getName())
                .setCheckTime(decision.getCheckTime())
                .setFactoryCode(factoryCode);
        save(record);
    }
}
