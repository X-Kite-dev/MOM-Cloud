package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesReportMaterialConsumeEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.enums.WarehouseOutBizTypeEnum;
import com.kitex.mom.mes.event.FeedRecordSaveEvent;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线边库投料记录
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseFeedServiceImpl extends ServiceImpl<MesWarehouseFeedMapper, MesWarehouseFeedEntity> implements MesWarehouseFeedService {

    private final MesWarehouseLiveStockService liveStockService;
    private final MesTaskMapper mesTaskMapper;
    private final MesReworkMaterialPlanService reworkMaterialPlans;
    private final com.kitex.mom.mes.mapper.MesTaskArrangeMapper reworkArranges;
    private final RemoteMaterialService remoteMaterialService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final RemotePurchaseService remotePurchaseService;
    private final RemoteParamService remoteParamService;
    private final MesReportMaterialConsumeService reportMaterialConsumeService;


    /**
     * pad投料接口
     * 投料记录，相同的合并一起；需要出库；校验条码判断物料是不是bom里面的；
     */
    @Override
    @Transactional
    public Boolean saveFeed(MesWarehouseFeedEntity feed) {
        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
        if ("1".equals(backFlushFlag)) {
            throw new RuntimeException("已配置倒冲，投料失败");
        }
        // 1.判断物料是不是bom里面的
        MesTaskEntity mesTask = Optional.ofNullable(mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, feed.getTaskCode()).last("FOR UPDATE")
                , false)).orElseThrow(() -> new CheckedException("未查找到此生产工单！"));
        String bomSnapshot = mesTask.getBomSnapshot();
        reworkMaterialPlans.assertReady(mesTask);
        MesMaterialSubstituteResolver.Binding binding = MesMaterialSubstituteResolver.resolveFeedBinding(
                bomSnapshot, feed.getMatCode(), feed.getRequirementMatCode());
        feed.setRequirementMatCode(binding.requirementMatCode());
        feed.setSubstituteFlag(binding.substitute());
        feed.setSubstituteRatio(binding.ratio());
        feed.setSubstitutePolicySnapshot(JSONUtil.toJsonStr(binding));
        if(mesTask.getReworkMaterialPlanId()!=null)assertReworkFeedBudget(mesTask,feed,binding);
        bindLineStockLineage(feed);
        // 2.判断是否存在相同投料记录并保存
        feed.setFeedTime(LocalDateTime.now());
        MomUser user = SecurityUtils.getUser();
        feed.setFeedPersonCode(user.getUsername());
        feed.setFeedPersonName(user.getName());
        // 查找物料信息
        MdmMaterialEntity material = Optional.ofNullable(remoteMaterialService.getDetails(feed.getMatCode()).getData()).orElseThrow(() -> new CheckedException("未查找到此物料信息！"));
        feed.setMatUnit(material.getBasicUnit());
        feed.setMatSpec(material.getSpecificationModel());
        feed.setMatName(material.getMaterialName());
        // 设置物料锁定状态为未锁定
        feed.setIsLock(Boolean.FALSE);

        // 查询质量 得到质检结果
        QmsInspectionPurchaseTaskEntity inspectionPurchaseTask = remotePurchaseService.getTaskByMatCodeAndSupplierCodeAndBatchNo(feed.getMatCode(), feed.getSupplierCode(), feed.getMatBatch()).getData();
        if (Objects.nonNull(inspectionPurchaseTask)) {
            feed.setInspectionTaskId(inspectionPurchaseTask.getId());
            feed.setInspectionResult(inspectionPurchaseTask.getResult());
        }
        feed.setFactoryCode(mesTask.getFactoryCode());
        feed.setTenantId(mesTask.getTenantId());
        feed.setUsedNum(BigDecimal.ZERO);
        if(!save(feed))throw new CheckedException("投料记录保存失败");
        if(mesTask.getReworkMaterialPlanId()!=null){
            var stored=getById(feed.getId());
            if(stored==null || stored.getFeedNum()==null || stored.getFeedNum().compareTo(feed.getFeedNum())!=0
                    || stored.getSubstituteRatio()==null || stored.getSubstituteRatio().compareTo(feed.getSubstituteRatio())!=0)
                throw new CheckedException("返工投料保存发生数量舍入，已回滚，不扣减实物库存");
        }
        // 3.出库
        MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(feed, inoutDTO);
        inoutDTO.setBillNo(feed.getArrangeCode());
        inoutDTO.setNum(feed.getFeedNum());
        inoutDTO.setProduceTaskCode(null);
        inoutDTO.setBizType(WarehouseOutBizTypeEnum.FEEDING.getCode());
        liveStockService.outStock(inoutDTO);
        // 4.保存操作记录
        MesTaskReportFeedVO params = new MesTaskReportFeedVO();
        BeanUtil.copyProperties(feed, params);
        params.setWorkerCode(feed.getFeedPersonCode());
        params.setWorkerName(feed.getFeedPersonName());
        params.setFeedNum(feed.getFeedNum());
        params.setTransferCode(feed.getMatBarcode());
        params.setFactoryCode(mesTask.getFactoryCode());
        applicationEventPublisher.publishEvent(new FeedRecordSaveEvent(this, params));
        return Boolean.TRUE;
    }

    private void assertReworkFeedBudget(MesTaskEntity task,MesWarehouseFeedEntity feed,MesMaterialSubstituteResolver.Binding binding){
        var arrange=reworkArranges.selectOne(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesTaskArrangeEntity>()
                .eq(com.kitex.mom.mes.entity.MesTaskArrangeEntity::getCode,feed.getArrangeCode())
                .eq(com.kitex.mom.mes.entity.MesTaskArrangeEntity::getTaskCode,task.getCode())
                .eq(com.kitex.mom.mes.entity.MesTaskArrangeEntity::getTenantId,task.getTenantId())
                .eq(com.kitex.mom.mes.entity.MesTaskArrangeEntity::getFactoryCode,task.getFactoryCode()));
        if(arrange==null)throw new CheckedException("返工投料派工不属于当前工单");
        var requirement=MesMaterialSubstituteResolver.flattenBom(task.getBomSnapshot()).stream().filter(x->binding.requirementMatCode().equals(x.getMaterialCode())).findFirst().orElseThrow(()->new CheckedException("物料不在批准返工补料清单"));
        var prior=list(new LambdaQueryWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getTaskCode,task.getCode())
                .eq(MesWarehouseFeedEntity::getFactoryCode,task.getFactoryCode()).eq(MesWarehouseFeedEntity::getTenantId,task.getTenantId())
                .eq(MesWarehouseFeedEntity::getRequirementMatCode,binding.requirementMatCode()).last("LIMIT 5001"));
        if(prior.size()>5000 || feed.getFeedNum()==null || feed.getFeedNum().signum()<=0)throw new CheckedException("返工投料数量无效或历史超过核验容量");
        BigDecimal used=feed.getFeedNum().divide(binding.ratio(),8,java.math.RoundingMode.UNNECESSARY);
        for(var row:prior){if(row.getFeedNum()==null || row.getFeedNum().signum()<0 || row.getSubstituteRatio()==null || row.getSubstituteRatio().signum()<=0)
            throw new CheckedException("返工历史投料量或替代比例不明，须先核对实物");used=used.add(row.getFeedNum().divide(row.getSubstituteRatio(),8,java.math.RoundingMode.UNNECESSARY));}
        if(used.compareTo(requirement.getQuantity().multiply(task.getNoticeNum()))>0)throw new CheckedException("返工累计投料超过批准补料总量，不得以重复投料绕过额度");
    }

    /**
     * 条码投料不能只按物料/批次扣减，否则同批次下不同正式预留可能串用。
     * 由线边实物库存反查并冻结唯一预留来源；无预留号的历史库存保持兼容。
     */
    private void bindLineStockLineage(MesWarehouseFeedEntity feed) {
        List<MesWarehouseLiveStockEntity> candidates = liveStockService.list(
                new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                        .eq(MesWarehouseLiveStockEntity::getMatCode, feed.getMatCode())
                        .eq(StrUtil.isNotBlank(feed.getMatBatch()), MesWarehouseLiveStockEntity::getMatBatch, feed.getMatBatch())
                        .eq(StrUtil.isNotBlank(feed.getSiteCode()), MesWarehouseLiveStockEntity::getSiteCode, feed.getSiteCode())
                        .eq(StrUtil.isNotBlank(feed.getSupplierCode()), MesWarehouseLiveStockEntity::getSupplierCode, feed.getSupplierCode())
                        .eq(StrUtil.isNotBlank(feed.getMatBarcode()), MesWarehouseLiveStockEntity::getMatBarcode, feed.getMatBarcode())
                        .eq(StrUtil.isNotBlank(feed.getReservationNo()), MesWarehouseLiveStockEntity::getReservationNo, feed.getReservationNo())
                        .eq(MesWarehouseLiveStockEntity::getStatus, "0")
                        .gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO)
                        .orderByAsc(MesWarehouseLiveStockEntity::getInTime));
        if (CollUtil.isEmpty(candidates)) {
            throw new CheckedException("线边库存中未找到本次投料对应的实物批次");
        }
        Map<String, List<MesWarehouseLiveStockEntity>> lineages = candidates.stream().collect(Collectors.groupingBy(item ->
                StrUtil.nullToEmpty(item.getReservationNo()) + "#" + StrUtil.nullToEmpty(item.getRequirementMatCode())));
        List<MesWarehouseLiveStockEntity> selected;
        if (lineages.size() == 1) {
            selected = lineages.values().iterator().next();
        } else {
            List<List<MesWarehouseLiveStockEntity>> sufficient = lineages.values().stream()
                    .filter(items -> items.stream().map(MesWarehouseLiveStockEntity::getNum)
                            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(feed.getFeedNum()) >= 0)
                    .toList();
            if (sufficient.size() != 1) {
                throw new CheckedException("同一实物批次存在多个物料预留来源，请按预留单号分开投料");
            }
            selected = sufficient.get(0);
        }
        MesWarehouseLiveStockEntity source = selected.get(0);
        if (StrUtil.isNotBlank(source.getRequirementMatCode())
                && !Objects.equals(source.getRequirementMatCode(), feed.getRequirementMatCode())) {
            throw new CheckedException("该库存已预留给需求物料[" + source.getRequirementMatCode()
                    + "]，不能用于需求物料[" + feed.getRequirementMatCode() + "]");
        }
        feed.setReservationNo(source.getReservationNo());
    }

    /**
     * 退料
     *
     * @param feed
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feedBack(MesWarehouseFeedEntity feed) {
        MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(feed, inoutDTO);
        inoutDTO.setBillNo(feed.getArrangeCode());
        inoutDTO.setProduceTaskCode(feed.getProduceTaskCode());
        inoutDTO.setNum(feed.getFeedNum().subtract(feed.getUsedNum()));
        inoutDTO.setBizType(WarehouseInBizTypeEnum.SURPLUS.getCode());
        liveStockService.inStock(inoutDTO);
    }

    /**
     * 消耗
     *
     * @param useDTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void use(MesWarehouseUseDTO useDTO) {
        if (useDTO.getReportId() == null || StrUtil.isBlank(useDTO.getReportRequestId())) {
            throw new CheckedException("报工物料消耗缺少业务关联号");
        }
        // 检验投料数量是否足够
//        List<MdmProcessProcedureMatEntity> feedConfigList = remoteProcessRouteService.getMatByCode(useDTO.getProcessRouteCode(), useDTO.getRouteCode()).getData();
        // 查询快照中保存的投料配置
        ProcessProcedureDTO route = com.kitex.mom.mes.util.MesTaskUtils.getCurrentProcess(useDTO.getSnapshot(),useDTO.getRouteCode());
        List<ProcessProcedureDTO.ProcessProcedureMatDTO> feedConfigList = route.getMdmProcessProcedureMatList();
        // 若没有配置投料绑定，则不进行消耗
        if (CollUtil.isEmpty(feedConfigList)) {
            return;
        }
        List<MesWarehouseFeedEntity> feedList = this.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getArrangeCode, useDTO.getArrangeCode())
                .ne(MesWarehouseFeedEntity::getIsLock, Boolean.TRUE) // 查询未锁定的
                .and(w->w.isNull(MesWarehouseFeedEntity::getMatValidEndDate).or().ge(MesWarehouseFeedEntity::getMatValidEndDate,LocalDate.now()))
                .orderByAsc(MesWarehouseFeedEntity::getFeedTime).orderByAsc(MesWarehouseFeedEntity::getId)); // 同时刻按主键稳定排序
        List<MesReportMaterialConsumeEntity> consumeList = new ArrayList<>();

        // 需求量以 BOM 主料为口径，实际扣减量按替代比例换算。
        for (ProcessProcedureDTO.ProcessProcedureMatDTO config : feedConfigList) {
            String requirementMatCode = config.getMaterialCode();
            BigDecimal requirementNum = useDTO.getReportNum().multiply(config.getQuantity());
            MesMaterialSubstituteResolver.Policy policy = MesMaterialSubstituteResolver.resolvePolicy(
                    useDTO.getBomSnapshot(), requirementMatCode);
            List<MesMaterialSubstituteResolver.Candidate> selectedCandidates = selectCandidates(
                    policy, feedList, requirementNum);
            BigDecimal remainingRequirement = requirementNum;
            for (MesMaterialSubstituteResolver.Candidate candidate : selectedCandidates) {
                remainingRequirement = consumeCandidate(useDTO, feedList, consumeList, policy,
                        candidate, remainingRequirement);
                if (remainingRequirement.compareTo(BigDecimal.ZERO) == 0) {
                    break;
                }
            }
            if (remainingRequirement.compareTo(BigDecimal.ZERO) > 0) {
                throw new CheckedException(String.format("需求物料[%s]投料不足，尚缺需求当量：%s",
                        requirementMatCode, remainingRequirement.setScale(6, RoundingMode.HALF_UP)));
            }
        }

        // 保存本次报工对实际投料批次的分配，反报工必须按这些记录精确恢复。
        if (!consumeList.isEmpty()) {
            reportMaterialConsumeService.saveBatch(consumeList);
        }
    }

    private List<MesMaterialSubstituteResolver.Candidate> selectCandidates(
            MesMaterialSubstituteResolver.Policy policy,
            List<MesWarehouseFeedEntity> feedList,
            BigDecimal requirementNum) {
        if (policy.allowMixed()) {
            return policy.candidates();
        }
        for (MesMaterialSubstituteResolver.Candidate candidate : policy.candidates()) {
            BigDecimal availableActual = availableActual(feedList, policy.requirementMatCode(), candidate);
            if (availableActual.compareTo(requirementNum.multiply(candidate.ratio())) >= 0) {
                return List.of(candidate);
            }
        }
        throw new CheckedException("需求物料[" + policy.requirementMatCode()
                + "]不允许混用，主料及任一替代料均无法单独满足本次报工");
    }

    private BigDecimal availableActual(List<MesWarehouseFeedEntity> feedList, String requirementMatCode,
                                       MesMaterialSubstituteResolver.Candidate candidate) {
        return feedList.stream()
                .filter(feed -> feedMatches(feed, requirementMatCode, candidate))
                .map(feed -> feed.getFeedNum().subtract(Objects.requireNonNullElse(feed.getUsedNum(), BigDecimal.ZERO)))
                .filter(num -> num.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean feedMatches(MesWarehouseFeedEntity feed, String requirementMatCode,
                                MesMaterialSubstituteResolver.Candidate candidate) {
        if (!Objects.equals(feed.getMatCode(), candidate.actualMatCode())) {
            return false;
        }
        if (StrUtil.isNotBlank(feed.getRequirementMatCode())) {
            return Objects.equals(feed.getRequirementMatCode(), requirementMatCode);
        }
        // 升级前投料没有需求料关联，只允许按主料使用，禁止猜测为替代料。
        return !candidate.substitute() && Objects.equals(feed.getMatCode(), requirementMatCode);
    }

    private BigDecimal consumeCandidate(MesWarehouseUseDTO useDTO,
                                        List<MesWarehouseFeedEntity> feedList,
                                        List<MesReportMaterialConsumeEntity> consumeList,
                                        MesMaterialSubstituteResolver.Policy policy,
                                        MesMaterialSubstituteResolver.Candidate candidate,
                                        BigDecimal remainingRequirement) {
        for (MesWarehouseFeedEntity feed : feedList) {
            if (remainingRequirement.compareTo(BigDecimal.ZERO) == 0) {
                break;
            }
            if (!feedMatches(feed, policy.requirementMatCode(), candidate)) {
                continue;
            }
            BigDecimal storedUsedNum = feed.getUsedNum();
            BigDecimal usedNum = Objects.requireNonNullElse(storedUsedNum, BigDecimal.ZERO);
            BigDecimal availableActual = feed.getFeedNum().subtract(usedNum);
            if (availableActual.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal actualNeeded = remainingRequirement.multiply(candidate.ratio());
            BigDecimal consumeActual = availableActual.min(actualNeeded);
            BigDecimal satisfiedRequirement = consumeActual.compareTo(actualNeeded) == 0
                    ? remainingRequirement
                    : consumeActual.divide(candidate.ratio(), 12, RoundingMode.DOWN);
            if (satisfiedRequirement.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal newUsedNum = usedNum.add(consumeActual);
            LambdaUpdateWrapper<MesWarehouseFeedEntity> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(MesWarehouseFeedEntity::getId, feed.getId());
            if (storedUsedNum == null) {
                updateWrapper.isNull(MesWarehouseFeedEntity::getUsedNum);
            } else {
                updateWrapper.eq(MesWarehouseFeedEntity::getUsedNum, storedUsedNum);
            }
            if (!this.update(updateWrapper.set(MesWarehouseFeedEntity::getUsedNum, newUsedNum))) {
                throw new CheckedException("投料消耗已被其他报工更新，请刷新后重试");
            }
            feed.setUsedNum(newUsedNum);
            consumeList.add(new MesReportMaterialConsumeEntity()
                    .setReportId(useDTO.getReportId())
                    .setReportRequestId(useDTO.getReportRequestId())
                    .setArrangeCode(useDTO.getArrangeCode())
                    .setFeedId(feed.getId())
                    .setRequirementMatCode(policy.requirementMatCode())
                    .setMatCode(feed.getMatCode())
                    .setMatBatch(feed.getMatBatch())
                    .setMatBarcode(feed.getMatBarcode())
                    .setConsumeNum(consumeActual)
                    .setRequirementNum(satisfiedRequirement)
                    .setSubstituteFlag(candidate.substitute())
                    .setSubstituteRatio(candidate.ratio())
                    .setStatus(ReportStatusEnum.EFFECTIVE.name())
                    .setFactoryCode(useDTO.getFactoryCode()));
            remainingRequirement = remainingRequirement.subtract(satisfiedRequirement);
        }
        return remainingRequirement;
    }

    /**
     * 查询投料列表
     *
     * @param query
     * @return
     */
    @Override
    public List<MesWarehouseFeedLeftDTO> queryTaskFeedList(MesWarehouseFeedLeftDTO query) {
        return baseMapper.queryTaskFeedList(query);
    }

    /**
     * 余料分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesWarehouseFeedLeftDTO> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam, MesWarehouseFeedLeftDTO query) {
        return baseMapper.queryLeftPage(pageParam, query);
    }

}
