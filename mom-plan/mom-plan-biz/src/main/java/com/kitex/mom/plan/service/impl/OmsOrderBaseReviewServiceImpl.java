package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBaseReviewPageDto;
import com.kitex.mom.oms.dto.OmsOrderMesInfoDto;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.vo.OmsOrderBaseReviewExportVo;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseReviewMapper;
import com.kitex.mom.plan.service.OmsOrderBaseReviewService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsReviewService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    public Boolean review(Long[] ids, String reviewType, String remark) {
        //根据订单是否变更的状态以及评审的结果 判断处理逻辑
        //获取主表的状态
        List<OmsOrderBaseReviewEntity> omsOrderBaseReviewEntities = this.listByIds(Arrays.asList(ids));
        for (OmsOrderBaseReviewEntity omsOrderBaseReviewEntity : omsOrderBaseReviewEntities) {
            if (!OrderStateEnum.WAIT_REVIEW.getCode().equals(omsOrderBaseReviewEntity.getOrderState())) {
                throw new RuntimeException("只有待评审状态的数据才可以进行评审！");
            }
            omsOrderBaseReviewEntity.setRemark(remark);
            if (YesOrNoStateEnum.NO.getCode().equals(omsOrderBaseReviewEntity.getChangeType())) {
                handleNoChangeType(reviewType, omsOrderBaseReviewEntity);
            } else {
                handleYesChangeType(reviewType, omsOrderBaseReviewEntity);
            }
        }
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
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
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
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    protected Boolean handleYesChangeType(String reviewType, OmsOrderBaseReviewEntity byId) {
        switch (reviewType) {
            case "0" -> {
                return handleYesChangeTypePass(byId);
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
    private Boolean handleYesChangeTypePass(OmsOrderBaseReviewEntity byId) {
        //变更订单通过逻辑 修改订单交付详情数据、订单、订单评审、订单跟踪 状态改为评审完成
        //查询评审子表信息，覆盖交付子表信息
        List<OmsOrderDetailsReviewEntity> detailsReviewList = omsOrderDetailsReviewService.list(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));

        List<OmsOrderDetailsEntity> isSuedList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode())
                .and(wq -> wq.eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED.getCode())
                        .or().eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_PLAN.getCode())
                        .or().eq(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_DELIVER.getCode())));


        List<OmsOrderDetailsEntity> orderDetailsList = detailsReviewList.stream().map(item -> {

            OmsOrderDetailsEntity orderDetails = isSuedList.stream().filter(obj -> obj.getProductCode().equals(item.getProductCode())
                    && obj.getDeliverTime().equals(item.getDeliverTime())).findFirst().orElse(null);

            if (ObjectUtil.isNotEmpty(orderDetails)) {
                if (orderDetails.getDeliverNum().compareTo(item.getDeliverNum()) != 0) {
                    orderDetails.setState(OrderStateEnum.REVIEW.getCode());
                    orderDetails.setDeliverNum(item.getDeliverNum());
                }
                orderDetails.setChangeSign(YesOrNoStateEnum.NO.getCode());
                return orderDetails;
            } else {
                OmsOrderDetailsEntity omsOrderDetails = new OmsOrderDetailsEntity();
                BeanUtils.copyProperties(item, omsOrderDetails, "id");
                omsOrderDetails.setFactoryCode(byId.getFactoryCode());
                omsOrderDetails.setState(OrderStateEnum.REVIEW.getCode());
                omsOrderDetails.setChangeSign(YesOrNoStateEnum.NO.getCode());
                return omsOrderDetails;
            }
        }).collect(Collectors.toList());


        boolean remove = omsOrderDetailsService.remove(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode())
                .and(wq -> wq.ne(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED.getCode())
                        .ne(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_PLAN.getCode())
                        .ne(OmsOrderDetailsEntity::getState, OrderStateEnum.ISSUED_DELIVER.getCode())));

        boolean save = omsOrderDetailsService.saveOrUpdateBatch(orderDetailsList);
        //订单、订单评审、订单跟踪状态改为评审完成
        byId.setOrderState(OrderStateEnum.REVIEW.getCode());
        this.updateById(byId);
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode()));
        omsOrderBaseTrackService.update(new UpdateWrapper<OmsOrderBaseTrackEntity>().lambda()
                .set(OmsOrderBaseTrackEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()));
        return save && remove;
    }

    //变更订单评审 驳回逻辑
    private Boolean handleYesChangeTypeReject(OmsOrderBaseReviewEntity byId) {
        //变更订单驳回逻辑  数据保持不变、交付数据变为之前的数据、订单、订单评审、订单跟踪改为评审完成
        // 数据保持不变、交付数据变为之前的数据
        boolean remove = omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));
        List<OmsOrderDetailsEntity> omsOrderDetailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        List<OmsOrderDetailsReviewEntity> orderDetailsReviewList = omsOrderDetailsList.stream().map(item -> {
            OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
            BeanUtils.copyProperties(item, omsOrderDetailsReview, "id");
            omsOrderDetailsReview.setOrderReviewCode(byId.getOrderReviewCode());
            return omsOrderDetailsReview;
        }).collect(Collectors.toList());
        boolean save = omsOrderDetailsReviewService.saveBatch(orderDetailsReviewList);
        //订单、订单评审、订单跟踪改为评审完成
        byId.setOrderState(OrderStateEnum.REVIEW.getCode());
        this.updateById(byId);
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode()));
        omsOrderBaseTrackService.update(new UpdateWrapper<OmsOrderBaseTrackEntity>().lambda()
                .set(OmsOrderBaseTrackEntity::getOrderState, OrderStateEnum.REVIEW.getCode())
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()));

        omsOrderDetailsList.forEach(item -> {
            item.setChangeSign(YesOrNoStateEnum.NO.getCode());
            if (!item.getState().equals(OrderStateEnum.ISSUED.getCode())
                    && !item.getState().equals(OrderStateEnum.ISSUED_PLAN.getCode())
                    && !item.getState().equals(OrderStateEnum.ISSUED_DELIVER.getCode())) {
                item.setState(OrderStateEnum.REVIEW.getCode());
            }
        });
        omsOrderDetailsService.updateBatchById(omsOrderDetailsList);
        return save && remove;
    }

    //变更订单评审 作废逻辑
    private Boolean handleYesChangeTypeCancel(OmsOrderBaseReviewEntity byId) {

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
        omsOrderBaseTrackService.remove(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, byId.getOrderCode()));
        this.remove(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .eq(OmsOrderBaseReviewEntity::getOrderCode, byId.getOrderCode()));
        omsOrderDetailsReviewService.remove(new LambdaQueryWrapper<OmsOrderDetailsReviewEntity>()
                .eq(OmsOrderDetailsReviewEntity::getOrderCode, byId.getOrderCode()));
        //订单恢复新建状态(初始状态)
        omsOrderBaseMapper.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getOrderState, OrderStateEnum.CREATE.getCode())
                .eq(OmsOrderBaseEntity::getOrderCode, byId.getOrderCode()));

        omsOrderDetailsService.update(new UpdateWrapper<OmsOrderDetailsEntity>()
                .lambda()
                .set(OmsOrderDetailsEntity::getState, OrderStateEnum.CREATE.getCode())
                .set(OmsOrderDetailsEntity::getChangeSign, YesOrNoStateEnum.NO.getCode())
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        return Boolean.TRUE;
    }

}