package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBaseReviewPageDto;
import com.kitex.mom.oms.dto.OmsOrderChangeReviewCommand;
import com.kitex.mom.oms.dto.OmsOrderMesInfoDto;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.vo.OmsOrderBaseReviewExportVo;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseReviewMapper;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单评审表
 *
 * @author kitex
 * @date 2025-01-07 14:32:02
 */
@Service
@AllArgsConstructor
public class OmsOrderBaseReviewServiceImpl extends ServiceImpl<OmsOrderBaseReviewMapper, OmsOrderBaseReviewEntity> implements OmsOrderBaseReviewService {

    //订单输入主表Mapper
    private final OmsOrderBaseMapper omsOrderBaseMapper;
    //评审交付子表明细信息
    private final OmsOrderDetailsReviewService omsOrderDetailsReviewService;
    //子表交付明细信息
    private final OmsOrderDetailsService omsOrderDetailsService;
    //实时库存
    private final RemoteLiveStockService remoteLiveStockService;
    //订单跟踪Service
    private final OmsOrderBaseTrackService omsOrderBaseTrackService;
    //序列号生成
    private final RemoteSeqConfigService remoteSeqConfigService;
    //字典
    private final RemoteDictService remoteDictService;
    private final PlnProductDemandService productDemandService;
    private final com.kitex.mom.plan.service.OmsConfigService omsConfigService;
    private final RemoteDeliverService remoteDeliverService;
    private final OmsOrderChangeLedgerService changeLedger;

    /**
     * 订单评审分页查询
     */
    @Override
    public Page<OmsOrderBaseReviewPageDto> getOmsOrderBaseReviewPage(Page<OmsOrderBaseReviewPageDto> page, OmsOrderBaseReviewPageDto omsOrderBaseReview) {
//		omsOrderBaseReview.setOrgIds(QueryGenerator.queryOrgIdList());
        return this.baseMapper.getOmsOrderBaseReviewPage(page, omsOrderBaseReview);
    }


    /**
     * 通过id查询订单评审表
     *
     * @param id id
     */
    @Override
    public OmsOrderBaseReviewEntity getOrderReviewById(Long id) {
        //查询主表信息
        OmsOrderBaseReviewEntity byId = this.getById(id);
        //查询子表信息
        List<OmsOrderDetailsReviewEntity> detailsList = omsOrderDetailsReviewService.list(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));
        //查询历史未交订单
        List<String> codeList = detailsList.stream().map(OmsOrderDetailsReviewEntity::getProductCode).distinct().toList();
        List<HistoryDeliverOrderDto> historyDeliverOrderDtoList = omsOrderBaseMapper.getHistoryDeliverOrderByProductCodes(byId.getOrderCode(), codeList);
        byId.setOrderDetailsList(detailsList);
        byId.setHistoryDeliverOrderDtoList(historyDeliverOrderDtoList);
        //todo 生产计划信息(已下发)
        OmsOrderMesInfoDto mesInfoDto = new OmsOrderMesInfoDto();
        mesInfoDto.setPlanNum(0);
        mesInfoDto.setDayAvgProduceNum(BigDecimal.ZERO);
        mesInfoDto.setUnProduceNum(BigDecimal.ZERO);
        mesInfoDto.setProductLiveStockNum(BigDecimal.ZERO);
        byId.setMesInfo(mesInfoDto);
        return byId;
    }

    /**
     * 导出excel 表格
     *
     * @param omsOrderBaseReview 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<OmsOrderBaseReviewExportVo> export(OmsOrderBaseReviewEntity omsOrderBaseReview, Long[] ids) {
        //获取订单类型字典
        Map<String, String> orderTypeMap = remoteDictService.getDictByType("order_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel));
        //订单状态
        List<OmsOrderBaseReviewEntity> orderBaseReviewList = this.list(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .like(StrUtil.isNotBlank(omsOrderBaseReview.getOrderCode()), OmsOrderBaseReviewEntity::getOrderCode, omsOrderBaseReview.getOrderCode())
                .like(StrUtil.isNotBlank(omsOrderBaseReview.getProductCode()), OmsOrderBaseReviewEntity::getProductCode, omsOrderBaseReview.getProductCode())
                .like(StrUtil.isNotBlank(omsOrderBaseReview.getProductName()), OmsOrderBaseReviewEntity::getProductName, omsOrderBaseReview.getProductName())
                .like(StrUtil.isNotBlank(omsOrderBaseReview.getCustomerName()), OmsOrderBaseReviewEntity::getCustomerName, omsOrderBaseReview.getCustomerName())
                .eq(StrUtil.isNotBlank(omsOrderBaseReview.getOrderState()), OmsOrderBaseReviewEntity::getOrderState, omsOrderBaseReview.getOrderState())
                .in(ArrayUtil.isNotEmpty(ids), OmsOrderBaseReviewEntity::getId, ids));
        orderBaseReviewList.forEach(item -> {
            item.setOrderType(orderTypeMap.get(item.getOrderType()));
            item.setOrderState(OrderStateEnum.getNameByCode(item.getOrderState()));
            item.setChangeType(YesOrNoStateEnum.getNameByCode(item.getChangeType()));
        });
        return orderBaseReviewList.stream().map(item -> {
            OmsOrderBaseReviewExportVo exportVo = new OmsOrderBaseReviewExportVo();
            BeanUtils.copyProperties(item, exportVo);
            return exportVo;
        }).collect(Collectors.toList());
    }

    /**
     * 订单评审
     *
     * @param ids        主表ids
     * @param reviewType 评审结果 0 通过 1驳回 2作废
     * @param remark     评审意见
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean review(Long[] ids, String reviewType, String remark) {
        if (ids == null || ids.length == 0 || Arrays.stream(ids).anyMatch(Objects::isNull)
                || reviewType == null || !Set.of("0", "1", "2").contains(reviewType) || StrUtil.isBlank(remark)) {
            throw new CheckedException("订单评审必须提供评审单、结论和意见");
        }
        // 批量评审按ID稳定取锁，同一事务内任一订单失败整批回滚。
        for (Long id : Arrays.stream(ids).distinct().sorted().toList()) {
            OmsOrderBaseReviewEntity selected = this.getById(id);
            if (selected == null) throw new CheckedException("订单评审记录不存在：" + id);
            if (YesOrNoStateEnum.YES.getCode().equals(selected.getChangeType())) {
                // 与OMS变更提交保持“跟踪头→评审头→交付行”的取锁顺序，避免提交/复核互锁。
                OmsOrderBaseTrackEntity track = omsOrderBaseTrackService.getOne(
                        new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                                .eq(OmsOrderBaseTrackEntity::getOrderCode, selected.getOrderCode())
                                .last("FOR UPDATE"), false);
                if (track == null) throw new CheckedException("变更订单缺少跟踪主记录");
            }
            OmsOrderBaseReviewEntity omsOrderBaseReviewEntity = this.getOne(
                    new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                            .eq(OmsOrderBaseReviewEntity::getId, id).last("FOR UPDATE"));
            if (omsOrderBaseReviewEntity == null) throw new CheckedException("订单评审记录不存在：" + id);
            if (!Objects.equals(omsOrderBaseReviewEntity.getOrderCode(), selected.getOrderCode())
                    || !Objects.equals(omsOrderBaseReviewEntity.getChangeType(), selected.getChangeType())) {
                throw new CheckedException("订单评审事实在等待取锁期间发生变化，请刷新重试");
            }
            String factory = FactoryContextHolder.getFactoryCode();
            if (StrUtil.isNotBlank(factory) && !Objects.equals(factory, omsOrderBaseReviewEntity.getFactoryCode())) {
                throw new CheckedException("当前工厂无权评审订单：" + omsOrderBaseReviewEntity.getOrderCode());
            }
            if (!OrderStateEnum.WAIT_REVIEW.getCode().equals(omsOrderBaseReviewEntity.getOrderState())) {
                throw new CheckedException("只有待评审状态的数据才可以进行评审：" + omsOrderBaseReviewEntity.getOrderCode());
            }
            if (YesOrNoStateEnum.YES.getCode().equals(omsOrderBaseReviewEntity.getChangeType())) {
                throw new CheckedException("订单变更必须使用带请求号和版本证据的受控复核入口");
            }
            omsOrderBaseReviewEntity.setRemark(remark);
            Boolean handled = handleNoChangeType(reviewType, omsOrderBaseReviewEntity);
            if (!Boolean.TRUE.equals(handled)) throw new CheckedException("订单评审状态写回失败：" + id);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reviewChange(OmsOrderChangeReviewCommand command) {
        if (command == null || command.getReviewIds() == null || command.getReviewIds().isEmpty()
                || command.getReviewIds().size() > 50 || command.getReviewIds().stream().anyMatch(Objects::isNull)
                || command.getRequestId() == null || command.getRequestId().isBlank()
                || command.getRequestId().length() > 64
                || command.getReviewType() == null || !Set.of("0", "1", "2").contains(command.getReviewType())
                || StrUtil.isBlank(command.getRemark()) || command.getRemark().length() > 500) {
            throw new CheckedException("受控订单变更复核必须提供最多50个评审ID、请求号、结论和500字内意见");
        }
        if (new HashSet<>(command.getReviewIds()).size() != command.getReviewIds().size()) {
            throw new CheckedException("受控订单变更复核不能重复指定同一评审单");
        }
        if (changeLedger.replayBatch(command.getRequestId(), command.getReviewIds(),
                command.getReviewType(), command.getRemark())) return Boolean.TRUE;
        for (Long id : command.getReviewIds().stream().sorted().toList()) {
            String requestId = changeLedger.childReviewRequestId(command.getRequestId(), id);
            if (changeLedger.replayReview(requestId, id, command.getReviewType(), command.getRemark())) continue;
            OmsOrderBaseReviewEntity selected = this.getById(id);
            if (selected == null || !YesOrNoStateEnum.YES.getCode().equals(selected.getChangeType())) {
                throw new CheckedException("受控订单变更评审单不存在或不是变更申请：" + id);
            }
            OmsOrderBaseTrackEntity track = omsOrderBaseTrackService.getOne(
                    new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                            .eq(OmsOrderBaseTrackEntity::getOrderCode, selected.getOrderCode())
                            .last("FOR UPDATE"), false);
            if (track == null) throw new CheckedException("受控订单变更缺少跟踪主记录");
            if (omsOrderBaseTrackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                    .eq(OmsOrderBaseTrackEntity::getOrderCode, selected.getOrderCode())) != 1) {
                throw new CheckedException("订单号跨工厂或重复，旧OMS关联入口不能安全识别来源");
            }
            OmsOrderBaseReviewEntity review = this.getOne(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                    .eq(OmsOrderBaseReviewEntity::getId, id).last("FOR UPDATE"));
            if (review == null || !Objects.equals(review.getOrderCode(), selected.getOrderCode())
                    || !YesOrNoStateEnum.YES.getCode().equals(review.getChangeType())
                    || !OrderStateEnum.WAIT_REVIEW.getCode().equals(review.getOrderState())
                    || !OrderStateEnum.WAIT_REVIEW.getCode().equals(track.getOrderState())
                    || !Objects.equals(track.getFactoryCode(), review.getFactoryCode())) {
                throw new CheckedException("订单变更评审事实已变化或不处于待审状态，请刷新");
            }
            String factory = FactoryContextHolder.getFactoryCode();
            if (StrUtil.isNotBlank(factory) && !Objects.equals(factory, review.getFactoryCode())) {
                throw new CheckedException("当前工厂无权复核该订单变更");
            }
            MomUser reviewer = SecurityUtils.getUser();
            if (reviewer == null || StrUtil.isBlank(reviewer.getUsername())
                    || StrUtil.isBlank(review.getChangeApplicantCode())
                    || Objects.equals(reviewer.getUsername(), review.getChangeApplicantCode())) {
                throw new CheckedException("订单变更申请人与复核人必须分离，历史申请须重新提交");
            }
            List<OmsOrderDetailsReviewEntity> candidates = omsOrderDetailsReviewService.list(
                    new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                            .eq(OmsOrderDetailsReviewEntity::getOrderCode, review.getOrderCode())
                            .orderByAsc(OmsOrderDetailsReviewEntity::getId));
            String before = changeLedger.currentSnapshot(review.getOrderCode(), review.getFactoryCode());
            String proposal = changeLedger.proposalFromReview(candidates);
            OmsOrderChangeVersionEntity version = changeLedger.requireSubmitted(id, review.getOrderCode(),
                    review.getFactoryCode(), before, proposal);
            if (!Objects.equals(version.getApplicantCode(), review.getChangeApplicantCode())) {
                throw new CheckedException("订单变更版本申请人与评审主记录不一致");
            }
            review.setRemark(command.getRemark());
            review.setChangeReviewerCode(reviewer.getUsername());
            review.setChangeReviewerName(StrUtil.blankToDefault(reviewer.getName(), reviewer.getUsername()));
            review.setChangeReviewedTime(java.time.LocalDateTime.now());
            if (!Boolean.TRUE.equals(handleYesChangeType(command.getReviewType(), review, version))) {
                throw new CheckedException("订单变更评审业务写回失败：" + id);
            }
            changeLedger.finish(version, requestId, command.getReviewType(), command.getRemark(),
                    changeLedger.currentSnapshot(review.getOrderCode(), review.getFactoryCode()), reviewer);
        }
        changeLedger.finishBatch(command.getRequestId(), command.getReviewIds(), command.getReviewType(),
                command.getRemark(), SecurityUtils.getUser());
        return Boolean.TRUE;
    }

    /**
     * 查询各订单评审状态的数量
     *
     * @return
     */
    @Override
    public Map<String, Long> getCount(OmsOrderBaseReviewPageDto omsOrderBaseReview) {
//		omsOrderBaseReview.setOrgIds(QueryGenerator.queryOrgIdList());
        List<OmsOrderBaseReviewPageDto> omsOrderBaseReviewCount = this.baseMapper.getOmsOrderBaseReviewCount(omsOrderBaseReview);
        return omsOrderBaseReviewCount.stream().collect(Collectors.groupingBy(OmsOrderBaseReviewPageDto::getOrderState, Collectors.counting()));
    }

    //普通订单评审处理逻辑
    protected Boolean handleNoChangeType(String reviewType, OmsOrderBaseReviewEntity byId) {
        switch (reviewType) {
            case "0" -> {
                return handleNoChangeTypePass(byId);
            }
            case "1" -> {
                return handleNoChangeTypeReject(byId);
            }
            default -> {
                throw new RuntimeException("未变更订单只能进行通过、驳回两种操作！");
            }
        }
    }

    //普通订单评审 通过逻辑
    private Boolean handleNoChangeTypePass(OmsOrderBaseReviewEntity byId) {
        //普通订单评审通过 创建订单跟踪记录、订单以及订单评审状态改为评审完成状态
        //更改状态
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode()));
        boolean update = this.update(new UpdateWrapper<OmsOrderBaseReviewEntity>().lambda()
                .set(OmsOrderBaseReviewEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .set(OmsOrderBaseReviewEntity::getRemark, byId.getRemark())
                .eq(OmsOrderBaseReviewEntity::getOrderCode, byId.getOrderCode()));
        //创建订单跟踪记录
        OmsOrderBaseTrackEntity omsOrderBaseTrack = new OmsOrderBaseTrackEntity();
        BeanUtils.copyProperties(byId, omsOrderBaseTrack, "id", "createTime");
        //独有字段 orderTrackCode  orderState（copy的是更改前的）
        String orderReviewCode = remoteSeqConfigService.create("order_track").getData();
        omsOrderBaseTrack.setOrderTrackCode(orderReviewCode);
        omsOrderBaseTrack.setOrderState(OrderStateEnum.REVIEW.getCode());

        omsOrderDetailsService.update(new UpdateWrapper<OmsOrderDetailsEntity>()
                .lambda().set(OmsOrderDetailsEntity::getState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        boolean save = omsOrderBaseTrackService.save(omsOrderBaseTrack);
        return save && update;
    }

    //普通订单评审 驳回逻辑
    private Boolean handleNoChangeTypeReject(OmsOrderBaseReviewEntity byId) {
        //普通订单评审驳回 删除评审记录、删除评审交付记录、订单改为新建状态(初始状态)
        //删除评审主子表
        boolean removeFather = this.removeById(byId);
        boolean removeChild = omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>().eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));
        //更改状态
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.CREATE.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode()));
        return removeFather && removeChild;
    }

    //变更订单评审处理逻辑
    protected Boolean handleYesChangeType(String reviewType, OmsOrderBaseReviewEntity byId,
                                          OmsOrderChangeVersionEntity version) {
        switch (reviewType) {
            case "0" -> {
                return handleYesChangeTypePass(byId, version);
            }
            case "1" -> {
                return handleYesChangeTypeReject(byId);
            }
            case "2" -> {
                return handleYesChangeTypeCancel(byId);
            }
            default -> {
                throw new RuntimeException("变更订单只能进行通过、驳回、作废三种操作！");
            }
        }
    }

    //变更订单评审 通过逻辑
    private Boolean handleYesChangeTypePass(OmsOrderBaseReviewEntity byId,
                                            OmsOrderChangeVersionEntity version) {
        List<OmsOrderDetailsReviewEntity> candidates = omsOrderDetailsReviewService.list(
                new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                        .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode())
                        .orderByAsc(OmsOrderDetailsReviewEntity::getId));
        if (candidates.isEmpty() || StrUtil.isBlank(byId.getOrderReviewCode())
                || candidates.stream().anyMatch(row ->
                !Objects.equals(row.getOrderReviewCode(), byId.getOrderReviewCode()))) {
            throw new CheckedException("订单变更候选交付行缺失或评审版本不一致");
        }
        List<OmsOrderDetailsEntity> current = omsOrderDetailsService.list(
                new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                        .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode())
                        .orderByAsc(OmsOrderDetailsEntity::getId).last("FOR UPDATE"));
        Map<Long, OmsOrderDetailsEntity> currentById = current.stream()
                .collect(Collectors.toMap(OmsOrderDetailsEntity::getId, row -> row));
        Set<Long> keptIds = new HashSet<>();
        Set<String> productDates = new HashSet<>();
        List<OmsOrderDetailsEntity> approved = new ArrayList<>();
        java.time.LocalDate frozenThrough = OmsOrderChangePolicy.frozenThrough(
                omsConfigService, byId.getFactoryCode());
        for (OmsOrderDetailsReviewEntity candidate : candidates) {
            if (StrUtil.isBlank(candidate.getProductCode()) || candidate.getDeliverTime() == null
                    || candidate.getDeliverNum() == null || candidate.getDeliverNum().signum() <= 0
                    || !Objects.equals(candidate.getFactoryCode(), byId.getFactoryCode())) {
                throw new CheckedException("订单变更候选交付行的工厂、产品、数量或日期不完整");
            }
            if (!productDates.add(candidate.getProductCode() + "\u0001" + candidate.getDeliverTime())) {
                throw new CheckedException("订单变更候选中产品和交期重复");
            }
            OmsOrderDetailsEntity row;
            if (candidate.getSourceDetailId() == null) {
                if (StrUtil.isNotBlank(candidate.getSourceDetailSnapshot())) {
                    throw new CheckedException("新交付行携带了伪造的旧来源快照");
                }
                if (frozenThrough != null && !candidate.getDeliverTime().isAfter(frozenThrough)) {
                    throw new CheckedException("新交付行日期已进入冻结区，须走冻结区例外审批");
                }
                row = new OmsOrderDetailsEntity();
                row.setOrderCode(byId.getOrderCode());
                row.setFactoryCode(byId.getFactoryCode());
                row.setDeliveredNum(BigDecimal.ZERO);
                row.setState(OrderStateEnum.REVIEW.getCode());
            } else {
                if (!keptIds.add(candidate.getSourceDetailId())) {
                    throw new CheckedException("订单变更候选重复引用同一来源行");
                }
                row = currentById.get(candidate.getSourceDetailId());
                if (row == null || StrUtil.isBlank(candidate.getSourceDetailSnapshot())
                        || !Objects.equals(candidate.getSourceDetailSnapshot(), OmsOrderLineEvidence.snapshot(row))) {
                    throw new CheckedException("原订单行已变化或缺少来源快照，请重新提交变更");
                }
                boolean changed = !sameOrderLineCore(row, candidate);
                if (changed && frozenThrough != null && (row.getDeliverTime() == null
                        || !row.getDeliverTime().isAfter(frozenThrough)
                        || !candidate.getDeliverTime().isAfter(frozenThrough))) {
                    throw new CheckedException("订单行[" + row.getId() + "]在复核前进入交期冻结区，申请已失效");
                }
                if (changed && (OrderStateEnum.ISSUED.getCode().equals(row.getState())
                        || OrderStateEnum.ISSUED_DELIVER.getCode().equals(row.getState())
                        || OrderStateEnum.COMPLETE.getCode().equals(row.getState())
                        || Optional.ofNullable(row.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() > 0)) {
                    throw new CheckedException("订单行[" + row.getId() + "]已有发货或交付事实，须走OMS-WMS受控变更");
                }
                if (changed && OrderStateEnum.ISSUED_PLAN.getCode().equals(row.getState())) {
                    synchronizeReviewedPlanDemand(version, byId, row, candidate);
                } else if (changed) {
                    requireNoOrphanPlanDemand(byId.getOrderCode(), row.getId());
                }
            }
            row.setProductCode(candidate.getProductCode());
            row.setProductName(candidate.getProductName());
            row.setProductSpec(candidate.getProductSpec());
            row.setProductUnit(candidate.getProductUnit());
            row.setDeliverTime(candidate.getDeliverTime());
            row.setDeliverNum(candidate.getDeliverNum());
            row.setRemark(candidate.getRemark());
            row.setChangeSign(YesOrNoStateEnum.NO.getCode());
            if (row.getId() != null && !isProtectedIssuedState(row.getState())) {
                row.setState(OrderStateEnum.REVIEW.getCode());
            }
            approved.add(row);
        }
        List<Long> deleted = new ArrayList<>();
        for (OmsOrderDetailsEntity old : current) {
            if (keptIds.contains(old.getId())) continue;
            if (isProtectedIssuedState(old.getState())
                    || Optional.ofNullable(old.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() > 0) {
                throw new CheckedException("订单变更不得删除已下发或有交付事实的来源行：" + old.getId());
            }
            requireNoOrphanPlanDemand(byId.getOrderCode(), old.getId());
            deleted.add(old.getId());
        }
        if (!deleted.isEmpty() && !omsOrderDetailsService.removeByIds(deleted)) {
            throw new CheckedException("订单变更删除未下发行失败");
        }
        if (!omsOrderDetailsService.saveOrUpdateBatch(approved)) {
            throw new CheckedException("订单变更交付行写回失败");
        }
        byId.setOrderState(OrderStateEnum.REVIEW.getCode());
        if (!this.updateById(byId)) throw new CheckedException("订单变更评审回执保存失败");
        if (omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode())) != 1) {
            throw new CheckedException("订单变更主订单状态写回失败");
        }
        if (!omsOrderBaseTrackService.update(new UpdateWrapper<OmsOrderBaseTrackEntity>().lambda()
                .set(OmsOrderBaseTrackEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()))) {
            throw new CheckedException("订单变更跟踪状态写回失败");
        }
        return Boolean.TRUE;
    }

    private boolean sameOrderLineCore(OmsOrderDetailsEntity old, OmsOrderDetailsReviewEntity candidate) {
        return Objects.equals(old.getProductCode(), candidate.getProductCode())
                && Objects.equals(old.getProductName(), candidate.getProductName())
                && Objects.equals(old.getProductSpec(), candidate.getProductSpec())
                && Objects.equals(old.getProductUnit(), candidate.getProductUnit())
                && Objects.equals(old.getDeliverTime(), candidate.getDeliverTime())
                && old.getDeliverNum() != null && candidate.getDeliverNum() != null
                && old.getDeliverNum().compareTo(candidate.getDeliverNum()) == 0;
    }

    private boolean isProtectedIssuedState(String state) {
        return OrderStateEnum.ISSUED.getCode().equals(state)
                || OrderStateEnum.ISSUED_PLAN.getCode().equals(state)
                || OrderStateEnum.ISSUED_DELIVER.getCode().equals(state)
                || OrderStateEnum.COMPLETE.getCode().equals(state);
    }

    private void synchronizeReviewedPlanDemand(OmsOrderChangeVersionEntity version,
                                               OmsOrderBaseReviewEntity review,
                                               OmsOrderDetailsEntity source,
                                               OmsOrderDetailsReviewEntity candidate) {
        if (!Objects.equals(source.getProductCode(), candidate.getProductCode())
                || !Objects.equals(source.getProductName(), candidate.getProductName())
                || !Objects.equals(source.getProductSpec(), candidate.getProductSpec())
                || !Objects.equals(source.getProductUnit(), candidate.getProductUnit())
                || Optional.ofNullable(source.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() != 0) {
            throw new CheckedException("已下发计划的订单行只允许在原产品和原订单行上变更数量或交期");
        }
        List<PlnProductDemandEntity> linked = productDemandService.list(
                new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getSaleOrderCode, review.getOrderCode())
                        .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, source.getId()));
        if (linked.size() != 1) throw new CheckedException("订单行缺少唯一的PLAN来源需求，禁止评审写回");
        PlnProductDemandEntity demand = linked.get(0);
        if (!Objects.equals(demand.getFactoryCode(), review.getFactoryCode())
                || !Objects.equals(demand.getProductCode(), source.getProductCode())
                || !Objects.equals(demand.getPlanEndDate(), source.getDeliverTime())
                || demand.getNeedNum() == null || demand.getNeedNum().compareTo(source.getDeliverNum()) != 0) {
            throw new CheckedException("OMS订单行与PLAN需求来源事实已漂移，请先对账");
        }
        R<List<WmsDeliverGoodsPlanBaseEntity>> delivery = remoteDeliverService.getByOrder(
                review.getOrderCode(), source.getId());
        if (delivery == null || !delivery.isOk() || delivery.getData() == null) {
            throw new CheckedException("WMS发货来源暂不可核验，OMS评审故障关闭");
        }
        if (!delivery.getData().isEmpty()) {
            throw new CheckedException("订单行已有WMS发货计划，须走OMS-WMS受控变更");
        }
        PlnProductDemandEntity before = new PlnProductDemandEntity();
        BeanUtils.copyProperties(demand, before);
        PlnProductDemandEntity after = productDemandService.applyOmsReviewedChange(demand.getId(), review.getOrderCode(), source.getId(), version.getId(),
                source.getDeliverNum(), source.getDeliverTime(), candidate.getDeliverNum(), candidate.getDeliverTime());
        changeLedger.recordPlanRevision(version, source, before, after);
    }

    private void requireNoOrphanPlanDemand(String orderCode, Long detailId) {
        if (productDemandService.count(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, orderCode)
                .eq(PlnProductDemandEntity::getSaleOrderCodeDetails, detailId)) != 0) {
            throw new CheckedException("订单行[" + detailId + "]已有PLAN来源需求，状态不匹配，禁止改写或删除");
        }
    }

    //变更订单评审 驳回逻辑
    private Boolean handleYesChangeTypeReject(OmsOrderBaseReviewEntity byId) {
        //变更订单驳回逻辑  数据保持不变、交付数据变为之前的数据、订单、订单评审、订单跟踪改为评审完成
        // 数据保持不变、交付数据变为之前的数据
        boolean remove = omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));
        if (!remove) throw new CheckedException("订单变更驳回时清除候选交付行失败");
        List<OmsOrderDetailsEntity> omsOrderDetailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        if (omsOrderDetailsList.isEmpty()) throw new CheckedException("订单变更驳回时原交付行缺失");
        List<OmsOrderDetailsReviewEntity> orderDetailsReviewList = omsOrderDetailsList.stream().map(item -> {
            OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
            BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
            omsOrderDetailsReview.setOrderReviewCode(byId.getOrderReviewCode());
            return omsOrderDetailsReview;
        }).collect(Collectors.toList());
        boolean save = omsOrderDetailsReviewService.saveBatch(orderDetailsReviewList);
        if (!save) throw new CheckedException("订单变更驳回时恢复候选交付行失败");
        //订单、订单评审、订单跟踪改为评审完成
        byId.setOrderState(OrderStateEnum.REVIEW.getCode());
        if (!this.updateById(byId)) throw new CheckedException("订单变更驳回回执保存失败");
        if (omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode())) != 1) {
            throw new CheckedException("订单变更驳回时主订单状态写回失败");
        }
        if (!omsOrderBaseTrackService.update(new UpdateWrapper<OmsOrderBaseTrackEntity>().lambda()
                .set(OmsOrderBaseTrackEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()))) {
            throw new CheckedException("订单变更驳回时跟踪状态写回失败");
        }

        omsOrderDetailsList.forEach(item -> {
            item.setChangeSign(YesOrNoStateEnum.NO.getCode());
            if (!isProtectedIssuedState(item.getState())) {
                item.setState(OrderStateEnum.REVIEW.getCode());
            }
        });
        if (!omsOrderDetailsService.updateBatchById(omsOrderDetailsList)) {
            throw new CheckedException("订单变更驳回时原交付行状态写回失败");
        }
        return Boolean.TRUE;
    }

    //变更订单评审 作废逻辑
    private Boolean handleYesChangeTypeCancel(OmsOrderBaseReviewEntity byId) {

        if (productDemandService.count(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, byId.getOrderCode())) != 0) {
            throw new CheckedException("订单存在PLAN来源需求，不允许通过作废评审直接删除订单跟踪");
        }
        List<OmsOrderDetailsEntity> allLines = omsOrderDetailsService.list(
                new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                        .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        for (OmsOrderDetailsEntity line : allLines) {
            if (Optional.ofNullable(line.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() > 0) {
                throw new CheckedException("订单行已有交付数量，不允许作废：" + line.getId());
            }
            R<List<WmsDeliverGoodsPlanBaseEntity>> delivery = remoteDeliverService.getByOrder(
                    byId.getOrderCode(), line.getId());
            if (delivery == null || !delivery.isOk() || delivery.getData() == null) {
                throw new CheckedException("WMS发货计划事实不可核验，订单作废故障关闭");
            }
            if (!delivery.getData().isEmpty()) {
                throw new CheckedException("订单行已有WMS发货计划，须先走OMS-WMS受控撤回：" + line.getId());
            }
        }

        List<OmsOrderDetailsEntity> list = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode())
                .and(wq -> wq.eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED.getCode())
                        .or().eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_PLAN.getCode())
                        .or().eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_DELIVER.getCode())));
        if (CollUtil.isNotEmpty(list)) {
            throw new RuntimeException("作废失败，部分订单明细已经下发！");
        }
        //变更订单评审作废逻辑 删除订单跟踪记录、删除评审所有信息、订单恢复新建状态(初始状态)
        //删除订单跟踪记录、删除评审所有信息
        if (!omsOrderBaseTrackService.remove(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()))) {
            throw new CheckedException("订单变更作废时删除跟踪记录失败");
        }
        if (!this.removeById(byId.getId())) throw new CheckedException("订单变更作废时删除评审记录失败");
        if (!omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()))) {
            throw new CheckedException("订单变更作废时删除候选交付行失败");
        }
        //订单恢复新建状态(初始状态)
        if (omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.CREATE.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode())) != 1) {
            throw new CheckedException("订单变更作废时主订单状态写回失败");
        }

        if (!omsOrderDetailsService.update(new UpdateWrapper<OmsOrderDetailsEntity>()
                .lambda()
                .set(OmsOrderDetailsEntity::getState, OrderStateEnum.CREATE.getCode())
                .set(OmsOrderDetailsEntity::getChangeSign, YesOrNoStateEnum.NO.getCode())
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()))) {
            throw new CheckedException("订单变更作废时交付行状态写回失败");
        }
        return Boolean.TRUE;
    }

}
