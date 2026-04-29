package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnDetailsService;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnService;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 发货退货表
 *
 * @author kitex
 * @date 2025-01-15 11:50:18
 */
@Service
@AllArgsConstructor
public class WmsDeliverGoodsReturnServiceImpl extends ServiceImpl<WmsDeliverGoodsReturnMapper, WmsDeliverGoodsReturnEntity> implements WmsDeliverGoodsReturnService {

    // 销售退货编码
    private final String billType = InBillTypeEnum.SALES_RETURN.getCode();
    //物料基础信息
    private final RemoteMaterialService remoteMaterialService;
    //发货计划
    private final WmsDeliverGoodsPlanBaseMapper wmsDeliverGoodsPlanBaseMapper;
    //发货单
    private final WmsDeliverGoodsBaseMapper wmsDeliverGoodsBaseMapper;
    //发货明细
    private final WmsDeliverGoodsDetailsMapper wmsDeliverGoodsDetailsMapper;
    //入库单明细
    private final WmsInBillDetailMapper wmsInBillDetailMapper;

    private final WmsInNoticeDetailMapper wmsInNoticeDetailMapper;

    private final WmsInNoticeMapper wmsInNoticeMapper;

    private final WmsDeliverGoodsReturnDetailsService wmsDeliverGoodsReturnDetailsService;

    /**
     * 根据id查询
     *
     * @param id id
     */
    @Override
    public WmsDeliverGoodsReturnEntity getReturnById(Long id) {
        //查询主表
        WmsDeliverGoodsReturnEntity byId = this.getById(id);
        List<WmsDeliverGoodsReturnDetailsEntity> detailsList = wmsDeliverGoodsReturnDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsReturnDetailsEntity>()
                .eq(WmsDeliverGoodsReturnDetailsEntity::getReturnId, id));

        List<Long> detailsIds = detailsList.stream().map(WmsDeliverGoodsReturnDetailsEntity::getId).toList();
        //查询入库通知单
        List<WmsInNoticeDetailEntity> detailEntityList = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                .in(WmsInNoticeDetailEntity::getDocumentId, detailsIds)
                .eq(WmsInNoticeDetailEntity::getDocumentTable, DocumentEnum.D_RETURN.getCode()));
        //已有接口 - 通过id集合查询主子表组合明细信息
        List<Long> longs = detailEntityList.stream().map(BaseEntity::getId).toList();
        if (CollUtil.isNotEmpty(longs)) {
            List<WmsInNoticeDetailEntity> detailEntities = wmsInNoticeDetailMapper.fetchDetails(new WmsInNoticeDetailQueryDTO(), longs.toArray(new Long[0]));
            byId.setInNoticeDetailList(detailEntities);
        }


        //计算可退货数量
        List<Long> idList = detailsList.stream().map(WmsDeliverGoodsReturnDetailsEntity::getBaseDetailsId).distinct().toList();
        List<WmsDeliverGoodsDetailsEntity> goodsDetailsList = wmsDeliverGoodsDetailsMapper.selectByIds(idList);
        Map<Long, WmsDeliverGoodsDetailsEntity> baseDetailsMap = goodsDetailsList.stream().collect(Collectors.toMap(WmsDeliverGoodsDetailsEntity::getId, Function.identity(), (k1, k2) -> k1));
        for (WmsDeliverGoodsReturnDetailsEntity returnDetails : detailsList) {
            WmsDeliverGoodsDetailsEntity deliverGoodsDetails = baseDetailsMap.get(returnDetails.getBaseDetailsId());
            BigDecimal subtract = deliverGoodsDetails.getDeliveredNum().subtract(deliverGoodsDetails.getReturnNum());
            returnDetails.setUnReturnNum(subtract);
        }
        byId.setDetailsList(detailsList);
        return byId;
    }

    @Override
    public List<WmsInNoticeDetailEntity> getInNotice(String deliverCode) {
        List<WmsDeliverGoodsReturnEntity> returnList = this.list(new LambdaQueryWrapper<WmsDeliverGoodsReturnEntity>()
                .eq(WmsDeliverGoodsReturnEntity::getDeliverCode, deliverCode));
        if (CollUtil.isNotEmpty(returnList)) {
            List<Long> returnIds = returnList.stream().map(WmsDeliverGoodsReturnEntity::getId).toList();
            List<WmsInNoticeEntity> wmsInNoticeEntities = wmsInNoticeMapper.selectList(new LambdaQueryWrapper<WmsInNoticeEntity>()
                    .in(WmsInNoticeEntity::getDocumentId, returnIds)
                    .eq(WmsInNoticeEntity::getDocumentTable, DocumentEnum.D_RETURN.getCode())
                    .and(wq -> wq.eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())
                            .or().eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())));
            if (CollUtil.isNotEmpty(wmsInNoticeEntities)) {
                List<Long> longs = wmsInNoticeEntities.stream().map(BaseEntity::getId).toList();
                List<WmsInNoticeDetailEntity> detailEntityList = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                        .in(WmsInNoticeDetailEntity::getNoticeId, longs));
                List<Long> detailsIds = detailEntityList.stream().map(BaseEntity::getId).toList();
                List<WmsInNoticeDetailEntity> detailEntities = wmsInNoticeDetailMapper.fetchDetails(new WmsInNoticeDetailQueryDTO(), detailsIds.toArray(new Long[0]));
                return detailEntities;
            }
        }
        return new ArrayList<>();
    }

    /**
     * 新增
     *
     * @param wmsDeliverGoodsReturn 主体
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveReturn(WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        List<WmsDeliverGoodsReturnDetailsEntity> detailsList = wmsDeliverGoodsReturn.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("退货明细不能为空！");
        }
        Long count = detailsList.stream().filter(item -> BigDecimal.ZERO.compareTo(item.getReturnNum()) < 0).count();
        if (count.compareTo(0L) == 0) {
            throw new RuntimeException("退货数量全部为0，请输入合理的退货数量！");
        }
        //查询
        List<WmsDeliverGoodsReturnEntity> returnList = this.list(new LambdaQueryWrapper<WmsDeliverGoodsReturnEntity>()
                .eq(WmsDeliverGoodsReturnEntity::getDeliverCode, wmsDeliverGoodsReturn.getDeliverCode()));
        if (CollUtil.isNotEmpty(returnList)) {
            List<Long> returnIds = returnList.stream().map(WmsDeliverGoodsReturnEntity::getId).toList();
            List<WmsInNoticeEntity> wmsInNoticeEntities = wmsInNoticeMapper.selectList(new LambdaQueryWrapper<WmsInNoticeEntity>()
                    .in(WmsInNoticeEntity::getDocumentId, returnIds)
                    .eq(WmsInNoticeEntity::getDocumentTable, DocumentEnum.D_RETURN.getCode())
                    .and(wq -> wq.eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())
                            .or().eq(WmsInNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())));
            List<String> stringList = wmsInNoticeEntities.stream().map(item -> String.valueOf(item.getId())).toList();
            if (CollUtil.isNotEmpty(wmsInNoticeEntities)) {
                throw new RuntimeException("该发货单存在退货入库记录未处理完成，请处理完成后再发起退货流程！入库通知单号：【" + String.join(",", stringList) + "】");
            }
        }
        //保存主表
        this.save(wmsDeliverGoodsReturn);
        //保存子表
        detailsList = detailsList.stream().filter(item -> BigDecimal.ZERO.compareTo(item.getReturnNum()) < 0).toList();
        detailsList.forEach(item -> {
            item.setReturnId(wmsDeliverGoodsReturn.getId());
        });
        wmsDeliverGoodsReturnDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    /**
     * 编辑
     *
     * @param wmsDeliverGoodsReturn 主体
     */
    @Override
    public Boolean updateReturnById(WmsDeliverGoodsReturnEntity wmsDeliverGoodsReturn) {
        List<WmsDeliverGoodsReturnDetailsEntity> detailsList = wmsDeliverGoodsReturn.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("退货明细不能为空！");
        }
        Long count = detailsList.stream().filter(item -> BigDecimal.ZERO.compareTo(item.getReturnNum()) < 0).count();
        if (count.compareTo(0L) == 0) {
            throw new RuntimeException("退货数量全部为0，请输入合理的退货数量！");
        }
        //更新主表
        this.updateById(wmsDeliverGoodsReturn);
        //更新子表
        wmsDeliverGoodsReturnDetailsService.remove(new LambdaQueryWrapper<WmsDeliverGoodsReturnDetailsEntity>()
                .eq(WmsDeliverGoodsReturnDetailsEntity::getReturnId, wmsDeliverGoodsReturn.getId()));
        detailsList = detailsList.stream().filter(item -> BigDecimal.ZERO.compareTo(item.getReturnNum()) < 0).toList();
        detailsList.forEach(item -> {
            item.setReturnId(wmsDeliverGoodsReturn.getId());
        });
        wmsDeliverGoodsReturnDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    /**
     * 补发
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reissue(WmsDeliverGoodsBaseEntity base) {
        //获取主表
        WmsDeliverGoodsReturnEntity deliverReturnBase = this.getById(base.getReturnId());
        deliverReturnBase.setState(DeliverReturnStateEnum.CLOSE.getCode());

        //创建新发货单
        //创建主表
        base.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        base.setReissueSign(YesOrNoStateEnum.YES.getCode());
        base.setIssued(YesOrNoStateEnum.NO.getCode());
        base.setSource(DeliverBaseSourceEnum.PLAN.getCode());
        wmsDeliverGoodsBaseMapper.insert(base);
        //创建明细
        List<WmsDeliverGoodsDetailsEntity> deliverDetailsList = base.getDeliverDetailsList();
        deliverDetailsList.forEach(item -> {
            item.setDeliverState(DeliverStateEnum.NOT_START.getCode());
            item.setDeliverCode(base.getDeliverCode());
            item.setDeliveredNum(BigDecimal.ZERO);
            item.setReturnNum(BigDecimal.ZERO);
        });
        wmsDeliverGoodsDetailsMapper.insert(deliverDetailsList);
        //更新主表
        this.updateById(deliverReturnBase);
        return Boolean.TRUE;
    }


    /**
     * 通过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean pass(Long id) {
        //获取主表
        WmsDeliverGoodsReturnEntity deliverReturnBase = this.getById(id);
        deliverReturnBase.setState(DeliverReturnStateEnum.PASS.getCode());
        //更新主表
        this.updateById(deliverReturnBase);

        //获取子表数据
        List<WmsDeliverGoodsReturnDetailsEntity> detailsList = wmsDeliverGoodsReturnDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsReturnDetailsEntity>()
                .eq(WmsDeliverGoodsReturnDetailsEntity::getReturnId, id));

        //获取类型,构建数据
        List<MdmMaterialEntity> matList = remoteMaterialService.getList().getData();
        if (CollUtil.isEmpty(matList)) {
            throw new RuntimeException("获取物料数据失败！物料数据未维护或者访问数据失败！");
        }
        Map<String, MdmMaterialEntity> matMap = matList.stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
        try {
            WmsInNoticeHandler handler = WmsInNoticeFactory.getHandler(billType);
            //主表
            WmsInNoticeEntity wmsInNotice = new WmsInNoticeEntity();
            wmsInNotice.setState(InOutNoticeStateEnum.READY.getCode());
            wmsInNotice.setWarehouseCode(deliverReturnBase.getReturnWarehouse());
            wmsInNotice.setWarehouseName(deliverReturnBase.getReturnWarehouseName());
            wmsInNotice.setBillType(billType);
            wmsInNotice.setDocumentId(deliverReturnBase.getId());
            wmsInNotice.setDocumentTable(DocumentEnum.D_RETURN.getCode());
            wmsInNotice.setFactoryCode(String.valueOf(deliverReturnBase.getFactoryCode()));
            //子表
            List<WmsInNoticeDetailEntity> detailList = new ArrayList<>();
            detailsList.forEach(item -> {
                MdmMaterialEntity material = matMap.get(item.getProductCode());
                WmsInNoticeDetailEntity detail = new WmsInNoticeDetailEntity();
                detail.setMatCode(item.getProductCode());
                detail.setMatName(item.getProductName());
                detail.setMatUnit(ObjectUtil.isNotEmpty(material) ? material.getBasicUnit() : null);
                detail.setMatSpec(ObjectUtil.isNotEmpty(material) ? material.getSpecificationModel() : null);
                detail.setMatGroup(ObjectUtil.isNotEmpty(material) ? material.getGroupCode() : null);
                detail.setMatGroupName(ObjectUtil.isNotEmpty(material) ? material.getGroupName() : null);
                detail.setNoticeInNum(Double.valueOf(String.valueOf(item.getReturnNum())));
                detail.setDocumentId(item.getId());
                detail.setDocumentTable(DocumentEnum.D_RETURN.getCode());
                detail.setFactoryCode(String.valueOf(deliverReturnBase.getFactoryCode()));
                detailList.add(detail);
            });
            wmsInNotice.setDetailList(detailList);
            handler.saveDeep(wmsInNotice);
        } catch (Exception e) {
            throw new RuntimeException("创建退货通知单失败！失败原因：" + e.getMessage());
        }
        return Boolean.TRUE;
    }

    /**
     * 生成补发单号
     */
    @Override
    public String createReissueCode(String deliverCode) {
        List<WmsDeliverGoodsBaseEntity> wmsDeliverGoodsBaseList = wmsDeliverGoodsBaseMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>()
                .like(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCode));
        if (wmsDeliverGoodsBaseList.size() > 1) {
            List<Integer> intList = new ArrayList<>();
            wmsDeliverGoodsBaseList.forEach(item -> {
                if (!item.getDeliverCode().equals(deliverCode)) {
                    String substring = item.getDeliverCode().substring(item.getDeliverCode().length() - 3, item.getDeliverCode().length());
                    intList.add(Integer.valueOf(substring));
                }
            });
            int integer = intList.stream().max(Comparator.comparingInt(Integer::intValue)).get();
            StringBuilder valueOf = new StringBuilder(String.valueOf(integer + 1));
            while (valueOf.length() < 3) {
                valueOf.insert(0, "0");
            }
            return deliverCode + valueOf;
        } else {
            return deliverCode + "001";
        }
    }

    @Override
    public void updateReturnByBill(WmsInBillEntity bill) {
        //入库单 bill
        //入库详情
        List<WmsInBillDetailEntity> billDetailList = wmsInBillDetailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                .eq(WmsInBillDetailEntity::getBillId, bill.getId()));

        List<Long> noticeDetailsList = billDetailList.stream().map(WmsInBillDetailEntity::getNoticeDetailId).distinct().toList();


        List<WmsInNoticeDetailEntity> wmsInNoticeDetailList = wmsInNoticeDetailMapper.selectByIds(noticeDetailsList);
        Map<Long, WmsInNoticeDetailEntity> inNoticeDetailMap = wmsInNoticeDetailList.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));


        WmsDeliverGoodsReturnEntity deliverReturnBase = this.getById(bill.getDocumentId());
        //更细发货计划和发货单发货明细
        List<WmsDeliverGoodsReturnDetailsEntity> detailsList = wmsDeliverGoodsReturnDetailsService.list(new LambdaQueryWrapper<WmsDeliverGoodsReturnDetailsEntity>()
                .eq(WmsDeliverGoodsReturnDetailsEntity::getReturnId, deliverReturnBase.getId()));
        Map<Long, WmsDeliverGoodsReturnDetailsEntity> returnDetailMap = detailsList.stream().collect(Collectors.toMap(WmsDeliverGoodsReturnDetailsEntity::getId, Function.identity()));

        List<String> planCodes = detailsList.stream().filter(item -> StrUtil.isNotBlank(item.getDeliverPlanCode())).map(WmsDeliverGoodsReturnDetailsEntity::getDeliverPlanCode).distinct().toList();
        List<Long> detailsIds = detailsList.stream().filter(item -> ObjectUtil.isNotEmpty(item.getBaseDetailsId())).map(WmsDeliverGoodsReturnDetailsEntity::getBaseDetailsId).distinct().toList();

        //查询发货计划
        if (CollUtil.isNotEmpty(planCodes)) {
            List<WmsDeliverGoodsPlanBaseEntity> planBaseList = wmsDeliverGoodsPlanBaseMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                    .in(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, planCodes));
            Map<String, WmsDeliverGoodsPlanBaseEntity> planBaseMap = planBaseList.stream().collect(Collectors.toMap(WmsDeliverGoodsPlanBaseEntity::getDeliverPlanCode, Function.identity()));

            for (WmsInBillDetailEntity wmsInBillDetailEntity : billDetailList) {
                WmsInNoticeDetailEntity notice = inNoticeDetailMap.get(wmsInBillDetailEntity.getNoticeDetailId());
                WmsDeliverGoodsReturnDetailsEntity returnDetails = returnDetailMap.get(notice.getDocumentId());
                WmsDeliverGoodsPlanBaseEntity planBase = planBaseMap.get(returnDetails.getDeliverPlanCode());
                BigDecimal returnNum = planBase.getReturnNum();
                planBase.setReturnNum(returnNum.add(BigDecimal.valueOf(wmsInBillDetailEntity.getOperateNum())));
            }
            wmsDeliverGoodsPlanBaseMapper.updateById(planBaseList);
        }

        //查询明细
        if (CollUtil.isNotEmpty(detailsIds)) {
            List<WmsDeliverGoodsDetailsEntity> deliverGoodsDetailsList = wmsDeliverGoodsDetailsMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                    .in(WmsDeliverGoodsDetailsEntity::getId, detailsIds));
            Map<Long, WmsDeliverGoodsDetailsEntity> deliverGoodsDetailsMap = deliverGoodsDetailsList.stream().collect(Collectors.toMap(WmsDeliverGoodsDetailsEntity::getId, Function.identity()));

            for (WmsInBillDetailEntity wmsInBillDetailEntity : billDetailList) {
                WmsInNoticeDetailEntity notice = inNoticeDetailMap.get(wmsInBillDetailEntity.getNoticeDetailId());
                WmsDeliverGoodsReturnDetailsEntity returnDetails = returnDetailMap.get(notice.getDocumentId());
                WmsDeliverGoodsDetailsEntity goodsDetails = deliverGoodsDetailsMap.get(returnDetails.getBaseDetailsId());
                BigDecimal returnNum = goodsDetails.getReturnNum();
                goodsDetails.setReturnNum(returnNum.add(BigDecimal.valueOf(wmsInBillDetailEntity.getOperateNum())));
            }
            wmsDeliverGoodsDetailsMapper.updateById(deliverGoodsDetailsList);
        }


    }

}