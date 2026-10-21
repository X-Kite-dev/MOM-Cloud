package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.dto.*;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import static com.kitex.mom.iot.service.impl.IotAlarmService.*;

@Service @RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('iot_dataScope_view')")
public class IotDataScopeService {
    private final IotDeviceDataAccess access;
    private final IotDataScopeSourceMapper sources;
    private final IotDataScopeClaimMapper claims;
    private final IotDataScopeItemMapper items;
    private final IotDataScopeOperationMapper operations;

    public Map<String,Object> candidates(Long deviceId,String kind,Long beforeId) {
        var device=access.device(deviceId,null,"read");kind(kind);
        require(beforeId==null||beforeId>0,"来源游标须为正数");
        var args=args(device,kind);args.put("beforeId",beforeId);
        var rows=sources.candidates(args);boolean more=rows.size()>50;
        if(more)rows=new ArrayList<>(rows.subList(0,50));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("records",rows);result.put("hasMore",more);
        result.put("nextBeforeId",rows.isEmpty()?null:String.valueOf(rows.get(rows.size()-1).getId()));
        result.put("deviceSnapshot",deviceSnapshot(device));return result;
    }
    public Map<String,Object> preview(IotDataScopeCommand c) {
        require(c!=null&&c.getDeviceId()!=null,"请选择设备及原始记录");
        var device=access.device(c.getDeviceId(),null,"read");
        return evidence(device,c.getSourceKind(),ids(c),false);
    }
    public Page<IotDataScopeClaim> page(String factory,long current) {
        access.check(factory,"read");
        return claims.selectPage(new Page<>(Math.max(1,current),20),new LambdaQueryWrapper<IotDataScopeClaim>()
                .eq(IotDataScopeClaim::getFactoryCode,factory).orderByDesc(IotDataScopeClaim::getId));
    }
    public Map<String,Object> detail(Long id) {
        var claim=find(id,"read");
        return Map.of("claim",claim,"items",items.selectList(new LambdaQueryWrapper<IotDataScopeItem>()
                .eq(IotDataScopeItem::getClaimId,id).orderByAsc(IotDataScopeItem::getSourceId)),
                "operations",operations.selectList(new LambdaQueryWrapper<IotDataScopeOperation>()
                .eq(IotDataScopeOperation::getClaimId,id).orderByAsc(IotDataScopeOperation::getId)));
    }
    @Transactional(rollbackFor=Exception.class)
    @PreAuthorize("@pms.hasPermission('iot_dataScope_view') and @pms.hasPermission('iot_dataScope_submit')")
    public IotDataScopeClaim submit(IotDataScopeCommand c) {
        command(c);require(c.getClaimId()==null,"认领申请不能覆盖已有申请");
        var device=access.device(c.getDeviceId(),null,"write");
        var old=replay(c,"SUBMIT");if(old!=null)return find(old.getClaimId(),"write");
        require(StrUtil.isNotBlank(c.getEvidenceRefs())&&c.getEvidenceRefs().length()<=2000,"请提供可供复核的外部归属证据编号/文件路径及说明（2000字以内）");
        require(Boolean.TRUE.equals(c.getOwnershipAttested()),"须明确确认已核对原始采集时归属，不能仅凭设备当前归属推断");
        var ev=evidence(device,c.getSourceKind(),ids(c),true);
        require(items.selectCount(new LambdaQueryWrapper<IotDataScopeItem>().eq(IotDataScopeItem::getSourceKind,c.getSourceKind())
                .in(IotDataScopeItem::getSourceId,ids(c)).in(IotDataScopeItem::getBindingState,List.of("PENDING","APPROVED")))==0,"所选来源已存在待审或批准认领，请刷新来源列表");
        require(Objects.equals(c.getFingerprint(),ev.get("fingerprint")),"原始记录或设备归属发生变化，请重新预览，不批准陈旧来源");
        var claim=new IotDataScopeClaim().setDeviceId(device.getId()).setDeviceCode(device.getDeviceCode()).setFactoryCode(device.getFactoryCode())
                .setSourceKind(c.getSourceKind()).setStatus("PENDING").setReason(c.getReason()).setEvidenceRefs(c.getEvidenceRefs())
                .setFingerprint(c.getFingerprint()).setDeviceSnapshot(deviceSnapshot(device)).setApplicantId(actor()).setApplicantAttested(true).setReviewerAttested(false)
                .setSubmittedTime(LocalDateTime.now()).setSourceCount(ids(c).size());
        require(claims.insert(claim)==1,"认领申请保存失败");
        @SuppressWarnings("unchecked") var rows=(List<IotDataScopeSource>)ev.get("sources");
        for(var row:rows)require(items.insert(new IotDataScopeItem().setClaimId(claim.getId()).setSourceKind(claim.getSourceKind())
                .setSourceId(row.getId()).setSourceSnapshot(row.getSnapshot()).setFingerprint(hash(row.getSnapshot()))
                .setBindingState("PENDING").setFactoryCode(claim.getFactoryCode()))==1,"原始行认领占用失败");
        audit(c,"SUBMIT",claim);return claim;
    }
    @Transactional(rollbackFor=Exception.class)
    @PreAuthorize("@pms.hasPermission('iot_dataScope_view') and @pms.hasPermission('iot_dataScope_submit')")
    public IotDataScopeClaim withdraw(IotDataScopeCommand c) {return finish(c,"WITHDRAW");}
    @Transactional(rollbackFor=Exception.class)
    @PreAuthorize("@pms.hasPermission('iot_dataScope_view') and @pms.hasPermission('iot_dataScope_review')")
    public IotDataScopeClaim approve(IotDataScopeCommand c) {return finish(c,"APPROVE");}
    @Transactional(rollbackFor=Exception.class)
    @PreAuthorize("@pms.hasPermission('iot_dataScope_view') and @pms.hasPermission('iot_dataScope_review')")
    public IotDataScopeClaim reject(IotDataScopeCommand c) {return finish(c,"REJECT");}

    private IotDataScopeClaim finish(IotDataScopeCommand c,String action) {
        command(c);require(c.getClaimId()!=null&&c.getDeviceId()==null&&c.getSourceIds()==null&&c.getSourceKind()==null
                && c.getEvidenceRefs()==null&&c.getFingerprint()==null,"处置须指定精确申请，不接受替换来源或证据");
        var before=find(c.getClaimId(),"WITHDRAW".equals(action)?"write":"admin");
        var claim=claims.selectOne(new LambdaQueryWrapper<IotDataScopeClaim>().eq(IotDataScopeClaim::getId,before.getId()).last("FOR UPDATE"));
        var old=replay(c,action);if(old!=null)return claims.selectById(old.getClaimId());
        require("PENDING".equals(claim.getStatus()),"申请已经终结");
        require("WITHDRAW".equals(action)?Objects.equals(actor(),claim.getApplicantId()):!Objects.equals(actor(),claim.getApplicantId()),
                "只有申请人可撤回，复核通过或驳回必须由另一岗位执行");
        var bound=items.selectList(new LambdaQueryWrapper<IotDataScopeItem>().eq(IotDataScopeItem::getClaimId,claim.getId()).orderByAsc(IotDataScopeItem::getSourceId));
        require(bound.size()==claim.getSourceCount()&&bound.stream().allMatch(i->"PENDING".equals(i.getBindingState())),"来源绑定数或状态异常，拒绝部分执行");
        if("APPROVE".equals(action)){
            require(Boolean.TRUE.equals(c.getOwnershipAttested()),"复核人须明确确认已经独立核对原始归属证据");
            var device=access.device(claim.getDeviceId(),null,"admin");
            require(Objects.equals(claim.getDeviceSnapshot(),deviceSnapshot(device)),"设备编码、产品或归属已变化，须重新申请");
            var ev=evidence(device,claim.getSourceKind(),bound.stream().map(IotDataScopeItem::getSourceId).toList(),true);
            require(Objects.equals(claim.getFingerprint(),ev.get("fingerprint")),"源数据已变更、已经核验或不再符合归属条件，不能批准");
            @SuppressWarnings("unchecked") var current=(List<IotDataScopeSource>)ev.get("sources");
            for(int i=0;i<current.size();i++)require(Objects.equals(bound.get(i).getSourceSnapshot(),current.get(i).getSnapshot())
                    && Objects.equals(bound.get(i).getFingerprint(),hash(current.get(i).getSnapshot())),"冻结行证据发生变化");
            var args=args(device,claim.getSourceKind());
            for(var item:bound){args.put("sourceId",item.getSourceId());require(sources.approve(args)==1,"原始归属认领失败，整批回滚");}
            claim.setStatus("APPROVED").setReviewerAttested(true);
        }else claim.setStatus("WITHDRAW".equals(action)?"WITHDRAWN":"REJECTED");
        if(!"WITHDRAW".equals(action))claim.setReviewerId(actor());
        claim.setReviewedTime(LocalDateTime.now()).setReviewReason(c.getReason());
        for(var item:bound)require(items.updateById(item.setBindingState("APPROVED".equals(claim.getStatus())?"APPROVED":"RELEASED"))==1,"来源绑定终态保存失败");
        require(claims.updateById(claim)==1,"认领终态保存失败");audit(c,action,claim);return claim;
    }
    private Map<String,Object> evidence(IotDeviceEntity device,String kind,List<Long> ids,boolean lock) {
        kind(kind);var args=args(device,kind);args.put("ids",ids);args.put("lock",lock);args.put("expectedCount",ids.size());
        Long expectedBytes=sources.evidenceBytes(args);
        require(expectedBytes!=null&&expectedBytes>=0,"部分来源不存在、已核验或归属不符，请重新选择");
        require(expectedBytes<=4L*1024*1024,"完整证据超过4MiB，请拆批后核验，不全量加载超大载荷");
        var rows=sources.sources(args);
        require(rows.size()==ids.size(),"包含不存在、已核验、跨域或与设备/产品不一致来源；请核实后重新选择");
        long bytes=0;List<String> hashes=new ArrayList<>();
        for(var row:rows){require(row.getSnapshot()!=null,"原始快照不可核验");bytes+=row.getSnapshot().getBytes(StandardCharsets.UTF_8).length;
            require(bytes<=4L*1024*1024,"认领证据超过4MiB，请拆分批次，不截断审批证据");hashes.add(row.getId()+":"+hash(row.getSnapshot()));}
        return Map.of("deviceSnapshot",deviceSnapshot(device),"sources",rows,
                "fingerprint",hash(kind+deviceSnapshot(device)+JSONUtil.toJsonStr(hashes)));
    }
    private Map<String,Object> args(IotDeviceEntity device,String kind) {
        require(device.getProductId()!=null&&StrUtil.isNotBlank(device.getProductCode())&&StrUtil.isNotBlank(device.getDeviceCode()),"设备编码或产品来源缺失，不能开展历史认领");
        Map<String,Object> args=new HashMap<>();args.put("kind",kind);args.put("deviceId",device.getId());args.put("deviceCode",device.getDeviceCode());
        args.put("productId",device.getProductId());args.put("productCode",device.getProductCode());args.put("tenantId",access.tenant());args.put("factory",device.getFactoryCode());return args;
    }
    private String deviceSnapshot(IotDeviceEntity device) {
        return JSONUtil.toJsonStr(Arrays.asList(device.getId(),device.getTenantId(),device.getFactoryCode(),device.getDeviceCode(),device.getProductId(),device.getProductCode()));
    }
    private void kind(String value){require("PROPERTY".equals(value)||"EVENT".equals(value),"请选择PROPERTY或EVENT来源");}
    private List<Long> ids(IotDataScopeCommand c) {
        require(c!=null&&c.getDeviceId()!=null&&c.getSourceIds()!=null&&!c.getSourceIds().isEmpty()&&c.getSourceIds().size()<=200
                &&c.getSourceIds().stream().allMatch(id->id!=null&&id>0)&&new HashSet<>(c.getSourceIds()).size()==c.getSourceIds().size(),"每批须选择1至200个不重复的原始行ID");
        return c.getSourceIds().stream().sorted().toList();
    }
    private IotDataScopeClaim find(Long id,String permission){
        access.tenant();var c=claims.selectById(id);require(c!=null,"认领申请不存在或不属于当前租户");access.check(c.getFactoryCode(),permission);return c;
    }
    private void command(IotDataScopeCommand c){access.tenant();require(c!=null&&StrUtil.isNotBlank(c.getRequestId())&&c.getRequestId().length()<=64
            &&StrUtil.isNotBlank(c.getReason())&&c.getReason().length()<=1000,"须提供永久请求号及1000字以内依据");}
    private Long actor(){return SecurityUtils.getUser().getId();}
    private IotDataScopeOperation replay(IotDataScopeCommand c,String action){
        var old=operations.selectOne(new LambdaQueryWrapper<IotDataScopeOperation>().eq(IotDataScopeOperation::getRequestId,c.getRequestId()));
        if(old!=null)require(Objects.equals(old.getFingerprint(),hash(action+JSONUtil.toJsonStr(c)))&&Objects.equals(old.getOperatorId(),actor()),"同请求不能换动作、目标、证据、理由或操作者");return old;
    }
    private void audit(IotDataScopeCommand c,String action,IotDataScopeClaim claim){
        require(operations.insert(new IotDataScopeOperation().setClaimId(claim.getId()).setRequestId(c.getRequestId()).setFingerprint(hash(action+JSONUtil.toJsonStr(c)))
                .setAction(action).setReason(c.getReason()).setOperatorId(actor()).setOperationTime(LocalDateTime.now())
                .setResultStatus(claim.getStatus()).setFactoryCode(claim.getFactoryCode()))==1,"认领永久操作账保存失败");
    }
}
