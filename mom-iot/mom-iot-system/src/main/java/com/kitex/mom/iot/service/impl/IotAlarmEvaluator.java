package com.kitex.mom.iot.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.kitex.mom.iot.service.impl.IotAlarmService.*;

/** 属性原始行、判定样本、连续计数和告警状态同事务提交。只做观测告警，不发设备控制指令。 */
@Service
@RequiredArgsConstructor
public class IotAlarmEvaluator {
    private final IotAlarmRuleMapper ruleMapper;
    private final IotAlarmCursorMapper cursorMapper;
    private final IotAlarmEpisodeMapper alarmMapper;
    private final IotAlarmSampleMapper sampleMapper;
    private final IotAlarmService service;

    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(IotDeviceEntity device, List<IotDevicePropertyEntity> properties) {
        var rules=ruleMapper.selectList(new LambdaQueryWrapper<IotAlarmRule>().eq(IotAlarmRule::getDeviceId,device.getId())
                .eq(IotAlarmRule::getStatus,"ACTIVE").orderByAsc(IotAlarmRule::getId));
        for(IotAlarmRule rule:rules) for(IotDevicePropertyEntity property:properties)
            if(Objects.equals(rule.getIdentifier(),property.getIdentifier())) apply(device,rule,property);
    }

    private void apply(IotDeviceEntity device,IotAlarmRule rule,IotDevicePropertyEntity p){
        require(p.getId()!=null && Objects.equals(p.getDeviceId(),device.getId()) && Objects.equals(p.getDeviceCode(),device.getDeviceCode()),"告警判定缺少精确属性来源");
        require(Boolean.TRUE.equals(p.getScopeVerified()) && Objects.equals(p.getTenantId(),device.getTenantId())
                && Objects.equals(p.getFactoryCode(),device.getFactoryCode()),"告警原始属性归属尚未核验或与设备不一致");
        require(Objects.equals(rule.getFactoryCode(),device.getFactoryCode())&&Objects.equals(rule.getDeviceCode(),device.getDeviceCode()),"告警规则设备来源漂移，须先核实，不串厂保存");
        String fingerprint=hash(JSONUtil.toJsonStr(Arrays.asList(rule.getId(),p.getId(),p.getDeviceId(),p.getDeviceCode(),p.getProductId(),p.getIdentifier(),p.getValue(),p.getQualityCode(),p.getCollectTime())));
        var old=sampleMapper.selectOne(new LambdaQueryWrapper<IotAlarmSample>().eq(IotAlarmSample::getRuleId,rule.getId()).eq(IotAlarmSample::getPropertyId,p.getId()));
        if(old!=null){require(Objects.equals(old.getFingerprint(),fingerprint),"同一属性来源不能以不同内容重判");return;}
        IotAlarmCursor cursor=cursorMapper.selectOne(new LambdaQueryWrapper<IotAlarmCursor>().eq(IotAlarmCursor::getRuleId,rule.getId()).last("FOR UPDATE"));
        require(cursor!=null,"活动告警规则缺少状态游标");
        String outcome;
        boolean ordered=p.getCollectTime()!=null && (cursor.getLastCollectTime()==null||p.getCollectTime().isAfter(cursor.getLastCollectTime())
                || (p.getCollectTime().equals(cursor.getLastCollectTime()) && (cursor.getLastPropertyId()==null||p.getId()>cursor.getLastPropertyId())))
                && !p.getCollectTime().isAfter(LocalDateTime.now().plusSeconds(5));
        if(!ordered)outcome="STALE_OR_CLOCK_ERROR";
        else outcome=classify(device,rule,p);
        Long alarmId=cursor.getActiveAlarmId();
        if(ordered){
            boolean gap=cursor.getLastCollectTime()!=null && p.getCollectTime().isAfter(cursor.getLastCollectTime().plusSeconds(rule.getMaxGapSeconds()));
            if(gap){cursor.setBreachCount(0).setRecoveryCount(0).setFirstBreachTime(null).setFirstBreachPropertyId(null);}
            if(!Set.of("TRIGGER","CLEAR","HOLD").contains(outcome)){
                cursor.setBreachCount(0).setRecoveryCount(0).setFirstBreachTime(null).setFirstBreachPropertyId(null);
            }else if(cursor.getActiveAlarmId()==null){
                if("TRIGGER".equals(outcome)){
                    if(cursor.getBreachCount()==0)cursor.setFirstBreachTime(p.getCollectTime()).setFirstBreachPropertyId(p.getId());
                    cursor.setBreachCount(cursor.getBreachCount()+1);
                    if(cursor.getBreachCount()>=rule.getTriggerSamples()){
                        IotAlarmEpisode alarm=new IotAlarmEpisode().setRuleId(rule.getId()).setDeviceId(device.getId())
                                .setDeviceCode(device.getDeviceCode()).setFactoryCode(device.getFactoryCode()).setIdentifier(rule.getIdentifier())
                                .setName(rule.getName()).setSeverity(rule.getSeverity()).setStatus("OPEN").setRuleSnapshot(JSONUtil.toJsonStr(rule))
                                .setFirstPropertyId(cursor.getFirstBreachPropertyId()).setLastPropertyId(p.getId()).setFirstBreachTime(cursor.getFirstBreachTime())
                                .setTriggeredTime(p.getCollectTime()).setLastTriggerTime(p.getCollectTime()).setObservationCount(rule.getTriggerSamples().longValue());
                        require(alarmMapper.insert(alarm)==1,"告警实例保存失败");alarmId=alarm.getId();
                        cursor.setActiveAlarmId(alarmId).setBreachCount(0).setRecoveryCount(0).setFirstBreachTime(null).setFirstBreachPropertyId(null);
                    }
                }else cursor.setBreachCount(0).setFirstBreachTime(null).setFirstBreachPropertyId(null);
            }else{
                IotAlarmEpisode alarm=alarmMapper.selectById(cursor.getActiveAlarmId());
                require(alarm!=null&&Set.of("OPEN","ACKNOWLEDGED").contains(alarm.getStatus()),"活动告警游标与告警状态不一致");
                alarm.setLastPropertyId(p.getId());
                if("TRIGGER".equals(outcome)){
                    alarm.setObservationCount(alarm.getObservationCount()+1).setLastTriggerTime(p.getCollectTime()); cursor.setRecoveryCount(0);
                }else if("CLEAR".equals(outcome)){
                    cursor.setRecoveryCount(cursor.getRecoveryCount()+1);
                    if(cursor.getRecoveryCount()>=rule.getRecoverySamples()){
                        alarm.setStatus("RECOVERED").setRecoveredTime(p.getCollectTime()).setRecoveryPropertyId(p.getId());
                        cursor.setActiveAlarmId(null).setRecoveryCount(0).setBreachCount(0).setFirstBreachTime(null).setFirstBreachPropertyId(null);
                    }
                }else cursor.setRecoveryCount(0);
                require(alarmMapper.updateById(alarm)==1,"告警信号状态更新失败");
            }
            cursor.setLastPropertyId(p.getId()).setLastCollectTime(p.getCollectTime()).setLastOutcome(outcome);
            require(cursorMapper.updateById(cursor)==1,"告警判定游标保存失败");
        }
        require(sampleMapper.insert(new IotAlarmSample().setRuleId(rule.getId()).setPropertyId(p.getId()).setAlarmId(alarmId)
                .setFingerprint(fingerprint).setValueText(p.getValue()).setQualityCode(p.getQualityCode()).setOutcome(outcome)
                .setCollectTime(p.getCollectTime()).setFactoryCode(device.getFactoryCode()))==1,"告警来源判定账保存失败");
    }

    private String classify(IotDeviceEntity device,IotAlarmRule rule,IotDevicePropertyEntity p){
        try{if(!Objects.equals(rule.getModelFingerprint(),service.modelFingerprint(device,rule.getIdentifier())))return "MODEL_CHANGED";}
        catch(RuntimeException e){return "MODEL_UNVERIFIABLE";}
        if(!"GOOD".equals(p.getQualityCode())&&!("UNSPECIFIED".equals(p.getQualityCode())&&Boolean.TRUE.equals(rule.getAllowUnspecifiedQuality())))return "BAD_QUALITY";
        BigDecimal value;
        try{if(p.getValue()==null||p.getValue().length()>100)return "INVALID_VALUE";value=new BigDecimal(p.getValue().trim());}
        catch(RuntimeException e){return "INVALID_VALUE";}
        if("UPPER".equals(rule.getDirection())){
            if(value.compareTo(rule.getTriggerValue())>=0)return "TRIGGER";
            if(value.compareTo(rule.getRecoveryValue())<=0)return "CLEAR";
        }else{
            if(value.compareTo(rule.getTriggerValue())<=0)return "TRIGGER";
            if(value.compareTo(rule.getRecoveryValue())>=0)return "CLEAR";
        }
        return "HOLD";
    }
}
