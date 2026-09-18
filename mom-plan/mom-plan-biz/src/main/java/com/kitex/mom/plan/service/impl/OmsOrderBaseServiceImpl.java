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
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteCustomerPartService;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderBasePageDto;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.oms.enums.YesOrNoStateEnum;
import com.kitex.mom.oms.vo.OmsOrderBaseExportVo;
import com.kitex.mom.oms.vo.OmsOrderBaseImportVo;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单输入
 *
 * @author kitex
 * @date 2025-01-07 14:20:47
 */
@Service
@AllArgsConstructor
public class OmsOrderBaseServiceImpl extends ServiceImpl<OmsOrderBaseMapper, OmsOrderBaseEntity> implements OmsOrderBaseService {

    //订单交付子表
    private final OmsOrderDetailsService omsOrderDetailsService;
    //订单评审
    private final OmsOrderBaseReviewService omsOrderBaseReviewService;
    //订单评审交付详情
    private final OmsOrderDetailsReviewService omsOrderDetailsReviewService;
    //序列号生成
    private final RemoteSeqConfigService remoteSeqConfigService;
    //订单跟踪
    private final OmsOrderBaseTrackService omsOrderBaseTrackService;
    //PLAN来源需求：旧订单草稿入口不得改写已有下游业务事实
    private final PlnProductDemandService productDemandService;
    //实时库存
    private final RemoteLiveStockService remoteLiveStockService;
    //字典
    private final RemoteDictService remoteDictService;
    //客户feign
    private final RemoteCustomerService remoteCustomerService;
    //产品feign
    private final RemoteMaterialService remoteMaterialService;
    //客户件号feign
    private final RemoteCustomerPartService remoteCustomerPartService;


    @Override
    public Page<OmsOrderBasePageDto> getOmsOrderBasePage(Page<OmsOrderBasePageDto> page, OmsOrderBasePageDto omsOrderBase) {
//		omsOrderBase.setOrgIds(QueryGenerator.queryOrgIdList());
        return this.baseMapper.getOmsOrderBasePage(page, omsOrderBase);
    }

    /**
     * 通过id查询订单输入
     */
    @Override
    public OmsOrderBaseEntity getOrderById(Long id) {
        //查询主表信息
        OmsOrderBaseEntity byId = this.getById(id);
        //查询子表信息
        List<OmsOrderDetailsEntity> detailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, byId.getOrderCode()));
        //查询历史未交订单
        List<String> codes = detailsList.stream().map(OmsOrderDetailsEntity::getProductCode).distinct().toList();
        List<HistoryDeliverOrderDto> historyDeliverOrderDtoList = this.baseMapper.getHistoryDeliverOrderByProductCodes(byId.getOrderCode(), codes);
        byId.setOrderDetailsList(detailsList);
        byId.setHistoryDeliverOrderDtoList(historyDeliverOrderDtoList);
        return byId;
    }

    @Override
    public List<HistoryDeliverOrderDto> getHistoryDeliverOrderDtoList(String orderCode, String productCode) {
        return this.baseMapper.getHistoryDeliverOrderByProductCode(orderCode, productCode);
    }

    /**
     * 订单输入新增
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveOrder(OmsOrderBaseEntity omsOrderBase) {
        if (omsOrderBase == null || StrUtil.isBlank(omsOrderBase.getOrderCode())
                || CollUtil.isEmpty(omsOrderBase.getOrderDetailsList())) {
            throw new CheckedException("新建订单必须提供订单号和交付行");
        }
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory)) {
            if (StrUtil.isNotBlank(omsOrderBase.getFactoryCode())
                    && !Objects.equals(selectedFactory, omsOrderBase.getFactoryCode())) {
                throw new CheckedException("新建订单的工厂与当前授权工厂不一致");
            }
            omsOrderBase.setFactoryCode(selectedFactory);
        }
        if (StrUtil.isBlank(omsOrderBase.getFactoryCode())) {
            throw new CheckedException("新建订单必须明确工厂");
        }
        if (this.count(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getOrderCode, omsOrderBase.getOrderCode())) != 0) {
            throw new CheckedException("订单号已存在，禁止重复建立订单");
        }
        omsOrderBase.setId(null);
        omsOrderBase.setOrderState(OrderStateEnum.CREATE.getCode());
        prepareDraftLines(omsOrderBase, omsOrderBase.getOrderDetailsList());
        boolean father = this.save(omsOrderBase);
        if (!father) throw new CheckedException("新建订单主记录保存失败");
        boolean child = omsOrderDetailsService.saveBatch(omsOrderBase.getOrderDetailsList());
        if (!child) throw new CheckedException("新建订单交付行保存失败");
        return Boolean.TRUE;
    }

    /**
     * 订单输入编辑
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateOrderById(OmsOrderBaseEntity omsOrderBase) {
        if (omsOrderBase == null || omsOrderBase.getId() == null
                || CollUtil.isEmpty(omsOrderBase.getOrderDetailsList())) {
            throw new CheckedException("订单草稿编辑必须指定订单和非空交付行");
        }
        OmsOrderBaseEntity persisted = lockEditableDraft(omsOrderBase.getId());
        if (!Objects.equals(persisted.getOrderCode(), omsOrderBase.getOrderCode())
                || (StrUtil.isNotBlank(omsOrderBase.getFactoryCode())
                && !Objects.equals(persisted.getFactoryCode(), omsOrderBase.getFactoryCode()))
                || (StrUtil.isNotBlank(omsOrderBase.getOrderState())
                && !OrderStateEnum.CREATE.getCode().equals(omsOrderBase.getOrderState()))) {
            throw new CheckedException("草稿编辑不得变更订单号、工厂或订单状态");
        }
        List<OmsOrderDetailsEntity> oldLines = requireEditableDraftLines(persisted);
        prepareDraftLines(persisted, omsOrderBase.getOrderDetailsList());
        boolean updated = this.update(new UpdateWrapper<OmsOrderBaseEntity>().lambda()
                .set(OmsOrderBaseEntity::getProductCode, omsOrderBase.getProductCode())
                .set(OmsOrderBaseEntity::getProductName, omsOrderBase.getProductName())
                .set(OmsOrderBaseEntity::getProductSpec, omsOrderBase.getProductSpec())
                .set(OmsOrderBaseEntity::getCustomerCode, omsOrderBase.getCustomerCode())
                .set(OmsOrderBaseEntity::getCustomerName, omsOrderBase.getCustomerName())
                .set(OmsOrderBaseEntity::getCustomerPartCode, omsOrderBase.getCustomerPartCode())
                .set(OmsOrderBaseEntity::getOrderType, omsOrderBase.getOrderType())
                .eq(OmsOrderBaseEntity::getId, persisted.getId())
                .eq(OmsOrderBaseEntity::getOrderState, OrderStateEnum.CREATE.getCode()));
        if (!updated) throw new CheckedException("订单草稿头更新失败");
        List<Long> oldIds = oldLines.stream().map(OmsOrderDetailsEntity::getId).toList();
        if (!omsOrderDetailsService.removeByIds(oldIds)) throw new CheckedException("订单草稿旧交付行删除失败");
        if (!omsOrderDetailsService.saveBatch(omsOrderBase.getOrderDetailsList())) {
            throw new CheckedException("订单草稿新交付行保存失败");
        }
        return Boolean.TRUE;
    }

    /**
     * 通过id删除订单输入
     *
     * @param idList id列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeOrderByIds(ArrayList<Long> idList) {
        if (CollUtil.isEmpty(idList) || idList.stream().anyMatch(Objects::isNull)
                || new HashSet<>(idList).size() != idList.size()) {
            throw new CheckedException("删除订单草稿必须指定不重复的订单ID");
        }
        for (Long id : idList.stream().sorted().toList()) {
            OmsOrderBaseEntity draft = lockEditableDraft(id);
            List<Long> detailIds = requireEditableDraftLines(draft).stream()
                    .map(OmsOrderDetailsEntity::getId).toList();
            if (!omsOrderDetailsService.removeByIds(detailIds) || !this.removeById(id)) {
                throw new CheckedException("订单草稿删除失败：" + draft.getOrderCode());
            }
        }
        return Boolean.TRUE;
    }

    private OmsOrderBaseEntity lockEditableDraft(Long id) {
        OmsOrderBaseEntity draft = this.getOne(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getId, id).last("FOR UPDATE"), false);
        if (draft == null || !OrderStateEnum.CREATE.getCode().equals(draft.getOrderState())
                || StrUtil.isBlank(draft.getOrderCode()) || StrUtil.isBlank(draft.getFactoryCode())) {
            throw new CheckedException("只允许编辑或删除未提交的完整订单草稿：" + id);
        }
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(selectedFactory) && !Objects.equals(selectedFactory, draft.getFactoryCode())) {
            throw new CheckedException("当前工厂无权修改订单草稿：" + draft.getOrderCode());
        }
        if (this.count(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getOrderCode, draft.getOrderCode())) != 1
                || omsOrderBaseReviewService.count(new LambdaQueryWrapper<OmsOrderBaseReviewEntity>()
                .eq(OmsOrderBaseReviewEntity::getOrderCode, draft.getOrderCode())) != 0
                || omsOrderBaseTrackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, draft.getOrderCode())) != 0
                || productDemandService.count(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getSaleOrderCode, draft.getOrderCode())) != 0) {
            throw new CheckedException("订单号重复或已有评审、跟踪、PLAN来源事实，须走受控变更/撤回：" + draft.getOrderCode());
        }
        return draft;
    }

    private List<OmsOrderDetailsEntity> requireEditableDraftLines(OmsOrderBaseEntity draft) {
        List<OmsOrderDetailsEntity> lines = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, draft.getOrderCode())
                .orderByAsc(OmsOrderDetailsEntity::getId).last("FOR UPDATE"));
        if (lines.isEmpty() || lines.stream().anyMatch(line ->
                !Objects.equals(line.getFactoryCode(), draft.getFactoryCode())
                        || (line.getState() != null && !OrderStateEnum.CREATE.getCode().equals(line.getState()))
                        || Optional.ofNullable(line.getDeliveredNum()).orElse(BigDecimal.ZERO).signum() != 0)) {
            throw new CheckedException("订单草稿交付行存在跨工厂、非草稿或已交付事实，禁止直接删改");
        }
        return lines;
    }

    private void prepareDraftLines(OmsOrderBaseEntity draft, List<OmsOrderDetailsEntity> lines) {
        Set<String> productDates = new HashSet<>();
        for (OmsOrderDetailsEntity line : lines) {
            if (line == null || StrUtil.isBlank(line.getProductCode()) || line.getDeliverTime() == null
                    || line.getDeliverNum() == null || line.getDeliverNum().signum() <= 0
                    || !productDates.add(line.getProductCode() + "\u0001" + line.getDeliverTime())) {
                throw new CheckedException("订单草稿交付行必须有产品、正数量、日期且产品交期不能重复");
            }
            line.setId(null);
            line.setOrderCode(draft.getOrderCode());
            line.setFactoryCode(draft.getFactoryCode());
            line.setState(OrderStateEnum.CREATE.getCode());
            line.setDeliveredNum(BigDecimal.ZERO);
            line.setChangeSign(YesOrNoStateEnum.NO.getCode());
        }
    }


    /**
     * 导入excel 表格
     *
     * @param omsOrderBaseImportVoList 订单导入列表
     * @param bindingResult            错误信息列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importOrder(List<OmsOrderBaseImportVo> omsOrderBaseImportVoList, BindingResult bindingResult, String factoryCode) {
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(factoryCode) || (StrUtil.isNotBlank(selectedFactory)
                && !Objects.equals(selectedFactory, factoryCode))) {
            throw new CheckedException("订单导入必须指定当前授权工厂");
        }
        if (CollUtil.isEmpty(omsOrderBaseImportVoList)) throw new CheckedException("订单导入内容不能为空");
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        //获取所有客户信息
        List<MdmCustomerEntity> customerList = remoteCustomerService.getList().getData();
        if (CollUtil.isEmpty(customerList)) {
            throw new RuntimeException("客户信息访问失败！客户信息为空或服务请求失败！");
        }
        Map<String, MdmCustomerEntity> customerMap = customerList.stream().collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(), (k1, k2) -> k1));
        //获取所有产品信息
        List<MdmMaterialEntity> materialList = remoteMaterialService.getList().getData();
        if (CollUtil.isEmpty(materialList)) {
            throw new RuntimeException("物料信息访问失败！物料信息为空或服务请求失败！");
        }
        Map<String, MdmMaterialEntity> materialMap = materialList.stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
        //获取所有客户件号信息
        List<MdmCustomerPartEntity> customerPartList = remoteCustomerPartService.getList().getData();
        Map<String, MdmCustomerPartEntity> customerPartMap = new HashMap<>();
        if (CollUtil.isNotEmpty(customerPartList)) {
            customerPartMap = customerPartList.stream()
                    .collect(Collectors.toMap(item -> item.getCustomerCode() + "_" + item.getMaterialCode(), Function.identity(), (k1, k2) -> k1));
        }
        //获取订单类型字典
        Map<String, String> orderTypeMap = remoteDictService.getDictByType("order_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue));
        //构建数据
        List<OmsOrderBaseEntity> orderBaseList = new ArrayList<>();
        List<OmsOrderDetailsEntity> orderDetailsList = new ArrayList<>();
        Map<String, OmsOrderBaseImportVo> orderMap = new HashMap<>();
        for (OmsOrderBaseImportVo orderBaseImportVo : omsOrderBaseImportVoList) {
            if (orderBaseImportVo == null) throw new CheckedException("订单导入存在空白明细行");
            Set<String> errorMsg = new HashSet<>();
            if (StrUtil.isBlank(orderBaseImportVo.getGroupCode())
                    || StrUtil.isBlank(orderBaseImportVo.getCustomerCode())
                    || StrUtil.isBlank(orderBaseImportVo.getOrderType())) {
                errorMsg.add("组号、客户和订单类型均为必填");
            }
            if (StrUtil.isBlank(orderBaseImportVo.getProductCode())
                    || orderBaseImportVo.getDeliverTime() == null || orderBaseImportVo.getDeliverNum() == null
                    || orderBaseImportVo.getDeliverNum().signum() <= 0) {
                errorMsg.add("产品、交期和正数量均为必填");
            }
            //判断产品编码的有效性
            MdmMaterialEntity material = materialMap.get(orderBaseImportVo.getProductCode());
            if (ObjectUtil.isEmpty(material)) {
                errorMsg.add(orderBaseImportVo.getProductCode() + "产品已停用或未维护！");
            }
            //判断客户编码的有效性
            MdmCustomerEntity customer = customerMap.get(orderBaseImportVo.getCustomerCode());
            if (ObjectUtil.isEmpty(customer)) {
                errorMsg.add(orderBaseImportVo.getCustomerCode() + "客户已停用或未维护！");
            }
            //判断订单类型的有效性
            String orderType = orderTypeMap.get(orderBaseImportVo.getOrderType());
            if (StrUtil.isBlank(orderType)) {
                errorMsg.add(orderBaseImportVo.getOrderType() + "订单类型未维护！");
            }
            //判断同一组组号的数据的客户编码、订单类型是否一致
            OmsOrderBaseImportVo importVo = orderMap.get(orderBaseImportVo.getGroupCode());
            if (ObjectUtil.isEmpty(importVo)) {
                orderMap.put(orderBaseImportVo.getGroupCode(), orderBaseImportVo);
            } else {
                if (!(Objects.equals(orderBaseImportVo.getCustomerCode(), importVo.getCustomerCode()) &&
                        Objects.equals(orderBaseImportVo.getOrderType(), importVo.getOrderType()))) {
                    errorMsg.add(orderBaseImportVo.getGroupCode() + "该组号数据未满足导入说明第4条要求！");
                }
            }
            //数据不合法
            if (CollUtil.isNotEmpty(errorMsg)) {
                errorMessageList.add(new ErrorMessage(
                        Optional.ofNullable(orderBaseImportVo.getLineNum()).orElse(0L) + 1L, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        } else {
            Map<String, List<OmsOrderBaseImportVo>> importMap = omsOrderBaseImportVoList.stream().collect(Collectors.groupingBy(OmsOrderBaseImportVo::getGroupCode, Collectors.toList()));
            //构建主子表数据
            for (Map.Entry<String, OmsOrderBaseImportVo> entry : orderMap.entrySet()) {
                OmsOrderBaseImportVo importVo = entry.getValue();
                String orderCode = remoteSeqConfigService.create("order").getData();
                OmsOrderBaseEntity omsOrderBase = new OmsOrderBaseEntity();
                omsOrderBase.setOrderCode(orderCode);
                omsOrderBase.setCustomerCode(importVo.getCustomerCode());
                omsOrderBase.setCustomerName(customerMap.get(importVo.getCustomerCode()).getCustomerName());
                omsOrderBase.setCustomerPartCode(ObjectUtil.isNotEmpty(customerPartMap.get(importVo.getCustomerCode() + "_" + importVo.getProductCode())) ?
                        customerPartMap.get(importVo.getCustomerCode() + "_" + importVo.getProductCode()).getPartNumber() : null);
                omsOrderBase.setOrderType(orderTypeMap.get(importVo.getOrderType()));
                omsOrderBase.setOrderState(OrderStateEnum.CREATE.getCode());
                omsOrderBase.setFactoryCode(factoryCode);
                orderBaseList.add(omsOrderBase);

                //构建子表数据
                List<OmsOrderBaseImportVo> importVos = importMap.get(entry.getKey());
                for (OmsOrderBaseImportVo vo : importVos) {
                    orderDetailsList.stream().filter(item -> item.getOrderCode().equals(orderCode)
                                    && item.getProductCode().equals(vo.getProductCode())
                                    && item.getDeliverTime().equals(vo.getDeliverTime())).findFirst()
                            .ifPresentOrElse(find -> {
                                BigDecimal deliverNum = find.getDeliverNum();
                                find.setDeliverNum(deliverNum.add(vo.getDeliverNum()));
                            }, () -> {
                                OmsOrderDetailsEntity omsOrderDetails = new OmsOrderDetailsEntity();
                                omsOrderDetails.setOrderCode(orderCode);
                                omsOrderDetails.setProductCode(vo.getProductCode());
                                omsOrderDetails.setProductName(materialMap.get(vo.getProductCode()).getMaterialName());
                                omsOrderDetails.setProductSpec(materialMap.get(vo.getProductCode()).getSpecificationModel());
                                omsOrderDetails.setProductUnit(materialMap.get(vo.getProductCode()).getBasicUnit());
                                omsOrderDetails.setDeliverTime(vo.getDeliverTime());
                                omsOrderDetails.setDeliverNum(vo.getDeliverNum());
                                omsOrderDetails.setDeliveredNum(BigDecimal.ZERO);
                                omsOrderDetails.setChangeSign(YesOrNoStateEnum.NO.getCode());
                                omsOrderDetails.setState(OrderStateEnum.CREATE.getCode());
                                omsOrderDetails.setFactoryCode(factoryCode);
                                omsOrderDetails.setRemark(vo.getRemark());
                                orderDetailsList.add(omsOrderDetails);
                            });
                }
            }
            //保存信息
            if (!this.saveBatch(orderBaseList) || !omsOrderDetailsService.saveBatch(orderDetailsList)) {
                throw new CheckedException("订单导入保存失败，整批回滚");
            }
            return R.ok(null, "订单导入成功！");
        }


    }

    /**
     * 导出excel 表格
     *
     * @param omsOrderBase 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<OmsOrderBaseExportVo> export(OmsOrderBaseEntity omsOrderBase, Long[] ids) {
        //获取订单类型字典
        Map<String, String> orderTypeMap = remoteDictService.getDictByType("order_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel));
        //订单状态
        List<OmsOrderBaseEntity> orderBaseEntities = this.list(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .like(StrUtil.isNotBlank(omsOrderBase.getOrderCode()), OmsOrderBaseEntity::getOrderCode, omsOrderBase.getOrderCode())
                .like(StrUtil.isNotBlank(omsOrderBase.getProductCode()), OmsOrderBaseEntity::getProductCode, omsOrderBase.getProductCode())
                .like(StrUtil.isNotBlank(omsOrderBase.getProductName()), OmsOrderBaseEntity::getProductName, omsOrderBase.getProductName())
                .like(StrUtil.isNotBlank(omsOrderBase.getCustomerName()), OmsOrderBaseEntity::getCustomerName, omsOrderBase.getCustomerName())
                .eq(StrUtil.isNotBlank(omsOrderBase.getOrderState()), OmsOrderBaseEntity::getOrderState, omsOrderBase.getOrderState())
                .in(ArrayUtil.isNotEmpty(ids), OmsOrderBaseEntity::getId, ids));
        orderBaseEntities.forEach(item -> {
            item.setOrderType(orderTypeMap.get(item.getOrderType()));
            item.setOrderState(OrderStateEnum.getNameByCode(item.getOrderState()));
        });
        return orderBaseEntities.stream().map(item -> {
            OmsOrderBaseExportVo exportVo = new OmsOrderBaseExportVo();
            BeanUtils.copyProperties(item, exportVo);
            return exportVo;
        }).collect(Collectors.toList());
    }


    /**
     * 订单输入批量提交评审
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean submitReview(ArrayList<Long> idList) {
        List<OmsOrderBaseEntity> omsOrderBaseList = checkOrderCommon(idList);
        //订单状态信息修改
        omsOrderBaseList.forEach(item -> {
            item.setOrderState(OrderStateEnum.WAIT_REVIEW.getCode());
        });
        //1.根据订单信息生成订单评审记录和交付详情评审数据
        //1.1查询交付记录详情
        List<String> orderCodeList = omsOrderBaseList.stream().map(OmsOrderBaseEntity::getOrderCode).collect(Collectors.toList());
        List<OmsOrderDetailsEntity> orderDetailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>().in(OmsOrderDetailsEntity::getOrderCode, orderCodeList));
        Map<String, List<OmsOrderDetailsEntity>> orderDetailsMap = orderDetailsList.stream().collect(Collectors.groupingBy(OmsOrderDetailsEntity::getOrderCode, Collectors.toList()));
        //1.2构建评审主子表数据
        List<OmsOrderBaseReviewEntity> orderBaseReviewList = new ArrayList<>();
        List<OmsOrderDetailsReviewEntity> omsOrderDetailsReviewList = new ArrayList<>();
        omsOrderBaseList.forEach(item -> {
            //评审主表
            OmsOrderBaseReviewEntity omsOrderBaseReview = new OmsOrderBaseReviewEntity();
            BeanUtils.copyProperties(item, omsOrderBaseReview, "id");
            //独有字段 是否变更订单 、 订单评审单号
            omsOrderBaseReview.setChangeType(YesOrNoStateEnum.NO.getCode());
            String orderReviewCode = remoteSeqConfigService.create("order_review").getData();
            omsOrderBaseReview.setOrderReviewCode(orderReviewCode);
            orderBaseReviewList.add(omsOrderBaseReview);
            //评审子表
            List<OmsOrderDetailsEntity> orderDetailsEntities = orderDetailsMap.get(item.getOrderCode());
            for (OmsOrderDetailsEntity orderDetails : orderDetailsEntities) {
                OmsOrderDetailsReviewEntity omsOrderDetailsReview = new OmsOrderDetailsReviewEntity();
                BeanUtils.copyProperties(orderDetails, omsOrderDetailsReview, "id");
                //独有字段
                omsOrderDetailsReview.setOrderReviewCode(omsOrderBaseReview.getOrderReviewCode());
                omsOrderDetailsReviewList.add(omsOrderDetailsReview);
            }
        });
        //更新订单状态
        boolean main = this.updateBatchById(omsOrderBaseList);
        //保存评审主子表信息
        omsOrderBaseReviewService.saveBatch(orderBaseReviewList);
        omsOrderDetailsReviewService.saveBatch(omsOrderDetailsReviewList);
        return main;
    }

    /**
     * 订单输入批量直接通过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean pass(ArrayList<Long> idList) {
        List<OmsOrderBaseEntity> omsOrderBaseList = checkOrderCommon(idList);
        //订单状态信息修改
        omsOrderBaseList.forEach(item -> {
            item.setOrderState(OrderStateEnum.REVIEW.getCode());
        });
        //根据订单信息生成订单跟踪记录
        List<OmsOrderBaseTrackEntity> orderBaseTrackList = new ArrayList<>();
        omsOrderBaseList.forEach(item -> {
            //订单跟踪主表
            OmsOrderBaseTrackEntity omsOrderBaseTrack = new OmsOrderBaseTrackEntity();
            BeanUtils.copyProperties(item, omsOrderBaseTrack, "id");
            //独有字段 订单评审单号（无）、订单跟踪单号
            String orderTrackCode = remoteSeqConfigService.create("order_track").getData();
            omsOrderBaseTrack.setOrderTrackCode(orderTrackCode);
            orderBaseTrackList.add(omsOrderBaseTrack);
        });
        //更新订单状态
        boolean main = this.updateBatchById(omsOrderBaseList);
        //保存评审主表信息
        omsOrderBaseTrackService.saveBatch(orderBaseTrackList);

        List<String> orderCodes = omsOrderBaseList.stream().map(OmsOrderBaseEntity::getOrderCode).distinct().toList();
        omsOrderDetailsService.update(new UpdateWrapper<OmsOrderDetailsEntity>()
                .lambda().set(OmsOrderDetailsEntity::getState, OrderStateEnum.REVIEW.getCode())
                .in(OmsOrderDetailsEntity::getOrderCode, orderCodes));
        return main;
    }

    private List<OmsOrderBaseEntity> checkOrderCommon(ArrayList<Long> idList) {
        if (CollUtil.isEmpty(idList)) {
            throw new CheckedException("选择数据为空！");
        }
        //后端二次校验状态
        List<OmsOrderBaseEntity> omsOrderBaseList = this.list(new LambdaQueryWrapper<OmsOrderBaseEntity>().in(OmsOrderBaseEntity::getId, idList));
        List<OmsOrderBaseEntity> errorStateList = omsOrderBaseList.stream().filter(item -> !OrderStateEnum.CREATE.getCode().equals(item.getOrderState())).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(errorStateList)) {
            throw new CheckedException("只有新建状态的订单才可以进行该操作！错误数据订单编号为：" + errorStateList.stream().map(OmsOrderBaseEntity::getOrderCode).collect(Collectors.joining(",")));
        }
        return omsOrderBaseList;
    }

    @Override
    public Boolean checkOrderBaseMsg(String orderCode, String productCode, String customerCode) {
        OmsOrderBaseEntity orderBase = this.getOne(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getOrderCode, orderCode));
        if (ObjectUtil.isEmpty(orderBase)) {
            return Boolean.TRUE;
        }
        List<String> errorMsg = new ArrayList<>();
        if (!productCode.equals(orderBase.getProductCode())) {
            errorMsg.add("该订单号维护的产品信息与本次输入产品信息不相符！");
        }
        if (!customerCode.equals(orderBase.getCustomerCode())) {
            errorMsg.add("该订单号维护的客户信息与本次输入客户信息不相符！");
        }
        if (CollUtil.isNotEmpty(errorMsg)) {
            throw new RuntimeException("操作失败！失败原因：" + String.join(",", errorMsg));
        }
        return Boolean.TRUE;
    }

    /**
     * 查询订单明细
     */
    @Override
    public OmsOrderDetailsEntity getOrderDetails(String orderCode, String productCode, LocalDate planDeliverTime) {
        return omsOrderDetailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode)
                .eq(OmsOrderDetailsEntity::getProductCode, productCode)
                .eq(OmsOrderDetailsEntity::getDeliverTime, planDeliverTime));
    }

    /**
     * 查询各订单输入状态的数量
     *
     * @return
     */
    @Override
    public Map<String, Long> getCount(OmsOrderBasePageDto omsOrderBase) {
//		omsOrderBase.setOrgIds(QueryGenerator.queryOrgIdList());
        List<OmsOrderBasePageDto> omsOrderBaseCount = this.baseMapper.getOmsOrderBaseCount(omsOrderBase);
        return omsOrderBaseCount.stream().collect(Collectors.groupingBy(OmsOrderBasePageDto::getOrderState, Collectors.counting()));
    }


}
