package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.dto.IotAlarmCommand;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class IotAlarmService {
    private final IotDeviceMapper deviceMapper;
    private final IotProductModelMapper modelMapper;
    private final IotAlarmRuleMapper ruleMapper;
    private final IotAlarmCursorMapper cursorMapper;
    private final IotAlarmEpisodeMapper alarmMapper;
    private final IotAlarmSampleMapper sampleMapper;
    private final IotAlarmOperationMapper operationMapper;
    private final RemoteMdmFactoryAccessService factoryService;

    public List<IotDeviceEntity> devices(String factory, String search) {
        access(factory, "read");
        return deviceMapper.selectPage(new Page<>(1,30),new LambdaQueryWrapper<IotDeviceEntity>()
                .select(IotDeviceEntity::getId,IotDeviceEntity::getDeviceCode,IotDeviceEntity::getDeviceName,IotDeviceEntity::getFactoryCode)
                .eq(IotDeviceEntity::getFactoryCode,factory).ne(IotDeviceEntity::getStatus,4)
                .and(StrUtil.isNotBlank(search),q -> q.like(IotDeviceEntity::getDeviceCode,search).or().like(IotDeviceEntity::getDeviceName,search))
                .orderByAsc(IotDeviceEntity::getDeviceCode)).getRecords();
    }
    public Map<String,Object> ruleDetail(Long id) {
        tenant();
        IotAlarmRule rule=ruleMapper.selectById(id); require(rule!=null,"告警规则不存在"); access(rule.getFactoryCode(),"read");
        return Map.of("rule",rule,"operations",operationMapper.selectList(new LambdaQueryWrapper<IotAlarmOperation>()
                .eq(IotAlarmOperation::getRuleId,id).isNull(IotAlarmOperation::getAlarmId).orderByAsc(IotAlarmOperation::getId)));
    }

    public Page<IotAlarmRule> rules(String factory, long current, long size) {
        access(factory, "read");
        return ruleMapper.selectPage(page(current, size), new LambdaQueryWrapper<IotAlarmRule>()
                .eq(IotAlarmRule::getFactoryCode, factory).orderByDesc(IotAlarmRule::getId));
    }
    public Page<IotAlarmEpisode> alarms(String factory, String status, String device, long current, long size) {
        access(factory, "read");
        return alarmMapper.selectPage(page(current, size), new LambdaQueryWrapper<IotAlarmEpisode>()
                .select(IotAlarmEpisode.class, f -> !"rule_snapshot".equals(f.getColumn()))
                .eq(IotAlarmEpisode::getFactoryCode, factory).eq(StrUtil.isNotBlank(status), IotAlarmEpisode::getStatus, status)
                .like(StrUtil.isNotBlank(device), IotAlarmEpisode::getDeviceCode, device).orderByDesc(IotAlarmEpisode::getId));
    }
    public Map<String, Long> summary(String factory) {
        access(factory, "read");
        Map<String, Long> result = new LinkedHashMap<>();
        for (String state : List.of("OPEN", "ACKNOWLEDGED", "RECOVERED", "CLOSED")) result.put(state,
                alarmMapper.selectCount(new LambdaQueryWrapper<IotAlarmEpisode>().eq(IotAlarmEpisode::getFactoryCode, factory).eq(IotAlarmEpisode::getStatus, state)));
        result.put("TODAY", alarmMapper.selectCount(new LambdaQueryWrapper<IotAlarmEpisode>().eq(IotAlarmEpisode::getFactoryCode, factory)
                .ge(IotAlarmEpisode::getTriggeredTime, java.time.LocalDate.now().atStartOfDay())));
        return result;
    }
    public Map<String, Object> detail(Long id) {
        tenant();
        IotAlarmEpisode alarm = alarmMapper.selectById(id);
        require(alarm != null, "告警不存在"); access(alarm.getFactoryCode(), "read");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("alarm", alarm);
        result.put("cursor", cursorMapper.selectOne(new LambdaQueryWrapper<IotAlarmCursor>().eq(IotAlarmCursor::getRuleId, alarm.getRuleId())));
        result.put("operations", operationMapper.selectList(new LambdaQueryWrapper<IotAlarmOperation>().eq(IotAlarmOperation::getAlarmId, id).orderByAsc(IotAlarmOperation::getId)));
        return result;
    }
    public Page<IotAlarmSample> samples(Long ruleId, long current, long size) {
        tenant();
        IotAlarmRule rule = ruleMapper.selectById(ruleId); require(rule != null, "告警规则不存在"); access(rule.getFactoryCode(), "read");
        return sampleMapper.selectPage(page(current, size), new LambdaQueryWrapper<IotAlarmSample>()
                .eq(IotAlarmSample::getRuleId, ruleId).orderByDesc(IotAlarmSample::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public IotAlarmRule createRule(IotAlarmCommand c) {
        command(c); require(c.getRule() != null && c.getRuleId() == null && c.getAlarmId() == null, "创建规则须提供完整规则参数");
        IotAlarmRule input = c.getRule();
        IotDeviceEntity device = deviceMapper.selectById(input.getDeviceId()); require(device != null, "IoT设备不存在");
        String sourceFactory=device.getFactoryCode();
        access(sourceFactory, "write"); device = lockDevice(device.getId());
        require(Objects.equals(sourceFactory,device.getFactoryCode()),"设备工厂已变化，请重新核实后编制规则");
        IotAlarmOperation old = replay(c, "CREATE_RULE"); if (old != null) return ruleMapper.selectById(old.getRuleId());
        require(StrUtil.isNotBlank(input.getIdentifier()) && input.getIdentifier().length() <= 100 && StrUtil.isNotBlank(input.getName()) && input.getName().length() <= 100
                && StrUtil.isNotBlank(input.getVersion()) && input.getVersion().length() <= 40
                && Set.of("UPPER", "LOWER").contains(StrUtil.blankToDefault(input.getDirection(), ""))
                && Set.of("INFO", "WARNING", "MAJOR", "CRITICAL").contains(StrUtil.blankToDefault(input.getSeverity(), "")), "规则名称、版本、比较方向或等级不合法");
        require(input.getTriggerValue() != null && input.getRecoveryValue() != null
                && input.getTriggerValue().precision() <= 24 && input.getRecoveryValue().precision() <= 24
                && input.getTriggerValue().precision()-input.getTriggerValue().scale() <= 16
                && input.getRecoveryValue().precision()-input.getRecoveryValue().scale() <= 16
                && Math.max(0,input.getTriggerValue().scale()) <= 8 && Math.max(0,input.getRecoveryValue().scale()) <= 8, "触发和恢复阈值须为有效精确小数（24位/8位小数以内）");
        require("UPPER".equals(input.getDirection()) ? input.getRecoveryValue().compareTo(input.getTriggerValue()) < 0
                : input.getRecoveryValue().compareTo(input.getTriggerValue()) > 0, "恢复阈值须与触发阈值形成迟滞区，不能相等或倒置");
        require(inRange(input.getTriggerSamples(),1,100) && inRange(input.getRecoverySamples(),1,100)
                && inRange(input.getMaxGapSeconds(),1,3600), "触发/恢复连续样本为1至100个，最大采样间隔为1至3600秒");
        require(ruleMapper.selectCount(new LambdaQueryWrapper<IotAlarmRule>().eq(IotAlarmRule::getDeviceId, device.getId())
                .eq(IotAlarmRule::getIdentifier, input.getIdentifier()).eq(IotAlarmRule::getVersion, input.getVersion())) == 0, "该设备属性规则版本已存在");
        IotAlarmRule rule = new IotAlarmRule().setDeviceId(device.getId()).setDeviceCode(device.getDeviceCode()).setFactoryCode(device.getFactoryCode())
                .setIdentifier(input.getIdentifier()).setName(input.getName()).setVersion(input.getVersion()).setDirection(input.getDirection())
                .setTriggerValue(input.getTriggerValue()).setRecoveryValue(input.getRecoveryValue()).setTriggerSamples(input.getTriggerSamples())
                .setRecoverySamples(input.getRecoverySamples()).setMaxGapSeconds(input.getMaxGapSeconds())
                .setAllowUnspecifiedQuality(Boolean.TRUE.equals(input.getAllowUnspecifiedQuality())).setSeverity(input.getSeverity())
                .setStatus("DRAFT").setAuthorId(actor());
        rule.setModelFingerprint(modelFingerprint(device, rule.getIdentifier()));
        require(ruleMapper.insert(rule) == 1, "告警规则保存失败"); audit(c,"CREATE_RULE",rule.getId(),null,rule.getFactoryCode(),rule.getStatus());
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public IotAlarmRule ruleAction(IotAlarmCommand c, boolean activate) {
        command(c); require(c.getRuleId() != null && c.getRule() == null && c.getAlarmId() == null, "请选择精确规则版本");
        IotAlarmRule before = ruleMapper.selectById(c.getRuleId()); require(before != null, "告警规则不存在"); access(before.getFactoryCode(),"admin");
        IotDeviceEntity device = lockDevice(before.getDeviceId());
        String action = activate ? "ACTIVATE_RULE" : "RETIRE_RULE";
        IotAlarmOperation old = replay(c,action); if (old != null) return ruleMapper.selectById(old.getRuleId());
        IotAlarmRule rule = ruleMapper.selectById(c.getRuleId());
        require(Objects.equals(rule.getFactoryCode(), device.getFactoryCode()) && Objects.equals(rule.getDeviceCode(),device.getDeviceCode()), "设备工厂或编码已变化，须核实规则来源");
        if (activate) {
            require("DRAFT".equals(rule.getStatus()) && !Objects.equals(rule.getAuthorId(),actor()), "仅未启用草案可由非编制人复核启用");
            require(Objects.equals(rule.getModelFingerprint(), modelFingerprint(device,rule.getIdentifier())), "物模型定义已变化，请按新版本重建核实规则");
            require(ruleMapper.selectCount(new LambdaQueryWrapper<IotAlarmRule>().eq(IotAlarmRule::getDeviceId,rule.getDeviceId())
                    .eq(IotAlarmRule::getIdentifier,rule.getIdentifier()).eq(IotAlarmRule::getStatus,"ACTIVE"))==0, "该设备属性已有活动规则，须先完成旧告警处置再退役切换");
            rule.setStatus("ACTIVE").setReviewerId(actor());
            require(cursorMapper.insert(new IotAlarmCursor().setRuleId(rule.getId()).setBreachCount(0).setRecoveryCount(0).setFactoryCode(rule.getFactoryCode()))==1,"规则状态游标建立失败");
        } else {
            require(Set.of("DRAFT","ACTIVE").contains(rule.getStatus()), "规则已经退役");
            require(alarmMapper.selectCount(new LambdaQueryWrapper<IotAlarmEpisode>().eq(IotAlarmEpisode::getRuleId,rule.getId())
                    .ne(IotAlarmEpisode::getStatus,"CLOSED"))==0, "仍有未确认或未复核关闭告警，不能退役规则遗留孤儿告警");
            rule.setStatus("RETIRED");
        }
        require(ruleMapper.updateById(rule)==1,"规则状态更新失败"); audit(c,action,rule.getId(),null,rule.getFactoryCode(),rule.getStatus()); return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public IotAlarmEpisode alarmAction(IotAlarmCommand c, boolean verify) {
        command(c); require(c.getAlarmId()!=null && c.getRuleId()==null && c.getRule()==null,"请选择告警记录");
        IotAlarmEpisode before=alarmMapper.selectById(c.getAlarmId()); require(before!=null,"告警不存在"); access(before.getFactoryCode(),"write");
        IotDeviceEntity device=lockDevice(before.getDeviceId());
        require(Objects.equals(device.getFactoryCode(),before.getFactoryCode()),"告警设备工厂已变化");
        String action=verify?"VERIFY_RECOVERY":"ACKNOWLEDGE";
        IotAlarmOperation old=replay(c,action); if(old!=null)return alarmMapper.selectById(old.getAlarmId());
        IotAlarmEpisode alarm=alarmMapper.selectById(c.getAlarmId());
        if(!verify){
            require(!"CLOSED".equals(alarm.getStatus()) && alarm.getAcknowledgedBy()==null,"告警已确认或已关闭");
            alarm.setAcknowledgedBy(actor()).setAcknowledgedTime(LocalDateTime.now());
            if("OPEN".equals(alarm.getStatus()))alarm.setStatus("ACKNOWLEDGED");
        } else {
            require("RECOVERED".equals(alarm.getStatus()) && alarm.getAcknowledgedBy()!=null && !Objects.equals(alarm.getAcknowledgedBy(),actor()),
                    "须收到有效恢复信号并由确认人之外的岗位复核关闭");
            IotAlarmRule rule=ruleMapper.selectById(alarm.getRuleId());
            IotAlarmCursor cursor=cursorMapper.selectOne(new LambdaQueryWrapper<IotAlarmCursor>().eq(IotAlarmCursor::getRuleId,rule.getId()));
            require(cursor!=null && cursor.getActiveAlarmId()==null && Set.of("CLEAR","HOLD").contains(StrUtil.blankToDefault(cursor.getLastOutcome(),""))
                    && cursor.getLastCollectTime()!=null && !cursor.getLastCollectTime().isAfter(LocalDateTime.now().plusSeconds(5))
                    && !cursor.getLastCollectTime().isBefore(LocalDateTime.now().minusSeconds(rule.getMaxGapSeconds())),
                    "设备再次告警、数据质量异常或恢复证据已过期，不能仅凭历史恢复关闭");
            require(Objects.equals(rule.getModelFingerprint(), modelFingerprint(device,rule.getIdentifier())),"物模型定义变化，恢复证据须重新核实");
            alarm.setStatus("CLOSED").setClosedTime(LocalDateTime.now()).setVerifiedBy(actor());
        }
        require(alarmMapper.updateById(alarm)==1,"告警处置保存失败"); audit(c,action,alarm.getRuleId(),alarm.getId(),alarm.getFactoryCode(),alarm.getStatus()); return alarm;
    }

    public String modelFingerprint(IotDeviceEntity device,String identifier){
        require(device.getStatus()!=null && device.getStatus()!=4,"设备已禁用或状态不可核验");
        var models=modelMapper.selectList(new LambdaQueryWrapper<IotProductModelEntity>().eq(IotProductModelEntity::getProductId,device.getProductId())
                .eq(IotProductModelEntity::getIdentifier,identifier).eq(IotProductModelEntity::getFuncType,"property"));
        require(models.size()==1,"设备属性物模型不存在或标识不唯一"); var m=models.get(0);
        require(Set.of("int","integer","long","float","double","decimal","number").contains(StrUtil.blankToDefault(m.getDataType(),"").toLowerCase(Locale.ROOT)),"本规则只接受数值型物模型属性");
        return hash(JSONUtil.toJsonStr(Arrays.asList(device.getId(),device.getDeviceCode(),device.getProductId(),device.getFactoryCode(),m.getId(),m.getIdentifier(),m.getDataType(),m.getDataSpecs())));
    }
    private IotDeviceEntity lockDevice(Long id){
        IotDeviceEntity device=deviceMapper.selectOne(new LambdaQueryWrapper<IotDeviceEntity>().eq(IotDeviceEntity::getId,id).last("FOR UPDATE"));
        require(device!=null,"IoT设备不存在"); return device;
    }
    private Long actor(){var user=SecurityUtils.getUser();require(user!=null && user.getId()!=null,"告警管理须登录");return user.getId();}
    private void tenant(){
        require(com.kitex.common.data.tenant.TenantContextHolder.getTenantId()!=null
                && !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip(),"告警管理须明确租户且不能跳过租户隔离");
    }
    private void access(String factory,String permission){
        tenant();
        String selected=FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected)||Objects.equals(factory,selected)),"告警不属于当前工厂");
        var r=factoryService.check(new MdmFactoryAccessCheck().setUserId(actor()).setFactoryCode(factory).setRequiredPermission(permission));
        require(r!=null&&r.isOk()&&Boolean.TRUE.equals(r.getData()),"缺少该工厂直接绑定权限或MDM不可核验");
    }
    private IotAlarmOperation replay(IotAlarmCommand c,String action){
        var old=operationMapper.selectOne(new LambdaQueryWrapper<IotAlarmOperation>().eq(IotAlarmOperation::getRequestId,c.getRequestId()));
        if(old!=null)require(Objects.equals(old.getFingerprint(),hash(action+JSONUtil.toJsonStr(c)))&&Objects.equals(old.getOperatorId(),actor()),"同请求号不得改变目标、参数、原因、动作或操作者");return old;
    }
    private void audit(IotAlarmCommand c,String action,Long rule,Long alarm,String factory,String result){
        require(operationMapper.insert(new IotAlarmOperation().setRuleId(rule).setAlarmId(alarm).setRequestId(c.getRequestId())
                .setFingerprint(hash(action+JSONUtil.toJsonStr(c))).setAction(action).setReason(c.getReason()).setOperatorId(actor())
                .setResultStatus(result).setOperationTime(LocalDateTime.now()).setFactoryCode(factory))==1,"告警永久操作账保存失败");
    }
    private void command(IotAlarmCommand c){tenant();require(c!=null&&StrUtil.isNotBlank(c.getRequestId())&&c.getRequestId().length()<=64
            &&StrUtil.isNotBlank(c.getReason())&&c.getReason().length()<=1000,"请提供请求号和1000字内处理依据");}
    private boolean inRange(Integer n,int min,int max){return n!=null&&n>=min&&n<=max;}
    private <T> Page<T> page(long current,long size){return new Page<>(Math.max(1,current),Math.max(1,Math.min(100,size)));}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static void require(boolean value,String message){if(!value)throw new CheckedException(message);}
}
