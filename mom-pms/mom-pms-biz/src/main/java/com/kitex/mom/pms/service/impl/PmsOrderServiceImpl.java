package com.kitex.mom.pms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.metadata.data.ImageData;
import com.alibaba.excel.metadata.data.WriteCellData;
import com.alibaba.excel.util.MapUtils;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.common.flow.api.feign.RemoteProcessAdapterService;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.enums.FlowResultEnum;
import com.kitex.mom.common.flow.enums.OrderTypeEnum;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsInNoticeOrderDTO;
import com.kitex.mom.pms.dto.PmsOrderAddDTO;
import com.kitex.mom.pms.dto.PmsOrderDetailAddDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.enums.PmsPlanFlowFieldDetailEnum;
import com.kitex.mom.pms.enums.PmsPlanFlowFieldEnum;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.kitex.mom.pms.service.*;
import com.kitex.mom.pms.vo.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 采购订单表
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@Service
@RequiredArgsConstructor
public class PmsOrderServiceImpl extends ServiceImpl<PmsOrderMapper, PmsOrderEntity> implements PmsOrderService {

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    private final PmsPlanService pmsPlanService;

    private final RemotePrintService remotePrintService;

    private final RemoteProcessAdapterService remoteProcessAdapterService;

    private final PmsHistoryPriceService pmsHistoryPriceService;

    private final PmsSupplierDetailService pmsSupplierDetailService;

    private final RemoteSupplierService remoteSupplierService;

    private final PmsBusinessReconciliationService pmsBusinessReconciliationService;

    private final PmsAgentPurchaseStatusOutboxService agentPurchaseStatusOutboxService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    /**
     * 新增采购订单表
     *
     * @param pmsOrder 采购订单新增DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsOrderAddDTO pmsOrder) {
        // 校验状态
        checkOrderSupplier(pmsOrder.getSupplierCode(), PmsOrderStatusEnum.NO_CHECK);

        PmsOrderEntity pmsOrderEntity = new PmsOrderEntity();
        BeanUtils.copyProperties(pmsOrder, pmsOrderEntity);
        pmsOrderEntity.setStatus(PmsOrderStatusEnum.NO_CHECK.getCode());
        pmsOrderEntity.setContractStatus(Boolean.FALSE);
        baseMapper.insert(pmsOrderEntity);

        orderDetailSaveOrUpdate(pmsOrder, pmsOrderEntity);
        return Boolean.TRUE;
    }

    /**
     * 编辑采购订单表
     *
     * @param pmsOrder 采购订单编辑DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsOrderAddDTO pmsOrder) {
        if (pmsOrder == null || pmsOrder.getId() == null) throw new CheckedException("采购订单编辑必须指定订单");
        PmsOrderEntity pmsOrderEntity = this.getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getId, pmsOrder.getId()).last("FOR UPDATE"), false);
        if (pmsOrderEntity == null || !Set.of(PmsOrderStatusEnum.NO_CHECK.getCode(),
                PmsOrderStatusEnum.REJECT.getCode()).contains(pmsOrderEntity.getStatus())) {
            throw new CheckedException("只有未审核或已驳回采购订单允许编辑；已审批订单必须走受控变更");
        }
        checkOrderSupplier(pmsOrder.getSupplierCode(), PmsOrderStatusEnum.NO_CHECK, pmsOrder.getId());
        if (PmsOrderStatusEnum.NO_CHECK.getCode().equals(pmsOrderEntity.getStatus())
                && !Boolean.TRUE.equals(pmsOrderEntity.getType())) {
            rollbackOrderPlanAllocations(pmsOrderEntity.getId());
        }
        String sourceType = pmsOrderEntity.getSourceType();
        String agentRequestId = pmsOrderEntity.getAgentRequestId();
        Long agentMrpId = pmsOrderEntity.getAgentMrpId();
        BeanUtils.copyProperties(pmsOrder, pmsOrderEntity);
        pmsOrderEntity.setStatus(PmsOrderStatusEnum.NO_CHECK.getCode());
        pmsOrderEntity.setSourceType(sourceType);
        pmsOrderEntity.setAgentRequestId(agentRequestId);
        pmsOrderEntity.setAgentMrpId(agentMrpId);
        baseMapper.updateById(pmsOrderEntity);

        orderDetailSaveOrUpdate(pmsOrder, pmsOrderEntity);

        return Boolean.TRUE;
    }

    /**
     * 校验供应商是否有某状态的采购订单
     *
     * @param supplierCode 供应商编码
     * @param statusEnum   状态枚举
     */
    private void checkOrderSupplier(String supplierCode, PmsOrderStatusEnum statusEnum) {
        checkOrderSupplier(supplierCode, statusEnum, null);
    }

    private void checkOrderSupplier(String supplierCode, PmsOrderStatusEnum statusEnum, Long excludeOrderId) {
        LambdaQueryWrapper<PmsOrderEntity> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(PmsOrderEntity::getSupplierCode, supplierCode);
        queryWrapper.eq(PmsOrderEntity::getStatus, statusEnum.getCode());
        queryWrapper.ne(excludeOrderId != null, PmsOrderEntity::getId, excludeOrderId);
        long count = this.count(queryWrapper);
        if (count > 0) {
            throw new CheckedException(String.format("供应商编码【%s】存在状态【%s】的采购订单，请先处理", supplierCode, statusEnum.getDesc()));
        }
    }

    /**
     * 采购订单明细处理
     *
     * @param pmsOrder       采购订单新增DTO
     * @param pmsOrderEntity 采购订单实体
     */
    private void orderDetailSaveOrUpdate(PmsOrderAddDTO pmsOrder, PmsOrderEntity pmsOrderEntity) {
        List<PmsPlanEntity> pmsPlanEntityList = new ArrayList<>();

        // 历史采购价列表
        List<PmsHistoryPriceEntity> pmsHistoryPriceEntityList = new ArrayList<>();
        for (PmsOrderDetailAddDTO pmsOrderDetailAddDTO : pmsOrder.getPmsOrderDetailList()) {
            PmsOrderDetailEntity pmsOrderDetail = new PmsOrderDetailEntity();
            BeanUtils.copyProperties(pmsOrderDetailAddDTO, pmsOrderDetail);
            pmsOrderDetail.setPmsOrderId(pmsOrderEntity.getId());
            pmsOrderDetail.setFactoryCode(pmsOrderEntity.getFactoryCode());
            if (Objects.isNull(pmsOrderDetail.getId())) {
                pmsOrderDetail.setId(null);
                pmsOrderDetailMapper.insert(pmsOrderDetail);
            } else {
                pmsOrderDetailMapper.updateById(pmsOrderDetail);
            }

            // 历史采购价保存
            PmsHistoryPriceEntity pmsHistoryPriceEntity = new PmsHistoryPriceEntity();
            BeanUtils.copyProperties(pmsOrderDetail, pmsHistoryPriceEntity, "id", "createTime");
            pmsHistoryPriceEntity.setPmsOrderNo(pmsOrderEntity.getPmsOrderNo());
            pmsHistoryPriceEntity.setPmsOrderDetailId(pmsOrderDetail.getId());
            pmsHistoryPriceEntity.setArrivalNum(pmsOrderDetail.getOrderNum());
            pmsHistoryPriceEntity.setSupplierCode(pmsOrderEntity.getSupplierCode());
            pmsHistoryPriceEntity.setSupplierName(pmsOrderEntity.getSupplierName());
            pmsHistoryPriceEntity.setFactoryCode(pmsOrderEntity.getFactoryCode());
            pmsHistoryPriceEntityList.add(pmsHistoryPriceEntity);

            if (!pmsOrder.getType()) {
                PmsPlanEntity pmsPlanEntity = new PmsPlanEntity();
                pmsPlanEntity.setPmsPlanNo(pmsOrderDetailAddDTO.getPmsPlanNo());
                pmsPlanEntity.setPlanOrderNum(pmsOrderDetailAddDTO.getOrderNum());
                pmsPlanEntity.setOrderNo(pmsOrderDetailAddDTO.getOrderNo());
                pmsPlanEntity.setMatCode(pmsOrderDetailAddDTO.getMatCode());
                pmsPlanEntityList.add(pmsPlanEntity);
            }
        }

        // 历史采购价保存
        pmsHistoryPriceService.saveBatch(pmsHistoryPriceEntityList);

        // 采购计划修改
        pmsPlanService.updatePlanOrderNum(pmsPlanEntityList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        if (ids == null || ids.length == 0) return Boolean.TRUE;
        List<PmsOrderEntity> orders = this.list(new LambdaQueryWrapper<PmsOrderEntity>()
                .in(PmsOrderEntity::getId, Arrays.asList(ids)).last("FOR UPDATE"));
        if (orders.size() != Arrays.stream(ids).distinct().count()
                || orders.stream().anyMatch(order -> !PmsOrderStatusEnum.NO_CHECK.getCode().equals(order.getStatus()))) {
            throw new CheckedException("只能删除全部存在且未审核的采购订单；已审批订单必须保留历史并走受控变更");
        }
        for (PmsOrderEntity order : orders) {
            if (!Boolean.TRUE.equals(order.getType())) rollbackOrderPlanAllocations(order.getId());
        }
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        pmsOrderDetailMapper.delete(Wrappers.<PmsOrderDetailEntity>lambdaQuery().in(PmsOrderDetailEntity::getPmsOrderId, ids));
        return Boolean.TRUE;
    }

    /**
     * 通过id构造采购通知单VO
     *
     * @param id id
     * @return R
     */
    @Override
    public PmsOrderVO getPurchaseInNotice(Long id) {
        PmsOrderVO resultVo = new PmsOrderVO();
        List<PmsOrderNoticeVO> resultList = new ArrayList<>();
        PmsOrderEntity pmsOrderEntity = this.getById(id);
        if (pmsOrderEntity == null || !Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
                PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode())
                .contains(pmsOrderEntity.getStatus())) {
            throw new CheckedException("采购订单不存在、未审核通过、已完成或已取消，不能生成收料单");
        }
        BeanUtils.copyProperties(pmsOrderEntity, resultVo);
        List<PmsOrderDetailEntity> pmsOrderDetailEntities = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, pmsOrderEntity.getId()));
        for (PmsOrderDetailEntity pmsOrderDetailEntity : pmsOrderDetailEntities) {
            Double arrivalNum = Optional.ofNullable(pmsOrderDetailEntity.getArrivalNum()).orElse(0.0);
            pmsOrderDetailEntity.setArrivalNum(arrivalNum);
            double orderNum = pmsOrderDetailEntity.getOrderNum() - arrivalNum;
            if (orderNum > 0) {
                PmsOrderNoticeVO pmsOrderNoticeVO = new PmsOrderNoticeVO();
                BeanUtils.copyProperties(pmsOrderDetailEntity, pmsOrderNoticeVO);
                pmsOrderNoticeVO.setOrderNum(orderNum);
                pmsOrderNoticeVO.setOrderDetailId(pmsOrderDetailEntity.getId());
                resultList.add(pmsOrderNoticeVO);
            }
        }
        resultVo.setDetails(resultList);
        return resultVo;
    }

    /**
     * 修改状态
     *
     * @param pmsOrderEntity 采购订单
     * @return R
     */
    @Override
    public Boolean statusChange(PmsOrderEntity pmsOrderEntity) {
        return this.updateById(pmsOrderEntity);
    }

    /**
     * 处理到货数量
     *
     * @param pmsInNoticeOrderDTO 采购通知单接收DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean inNoticeSave(PmsInNoticeOrderDTO pmsInNoticeOrderDTO) {
        if (pmsInNoticeOrderDTO == null || pmsInNoticeOrderDTO.getOrderDetailId() == null
                || StrUtil.isBlank(pmsInNoticeOrderDTO.getPmsOrderNo()) || pmsInNoticeOrderDTO.getNum() == null
                || pmsInNoticeOrderDTO.getNum() <= 0) {
            throw new CheckedException("采购到货回写必须指定订单、订单行和正数到货量");
        }
        PmsOrderEntity pmsOrderEntity = this.getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, pmsInNoticeOrderDTO.getPmsOrderNo()).last("FOR UPDATE"));
        if (pmsOrderEntity == null || !Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(), PmsOrderStatusEnum.RECEIVING.getCode(),
                PmsOrderStatusEnum.PART_RECEIVE.getCode()).contains(pmsOrderEntity.getStatus())) {
            throw new CheckedException("采购订单不存在、未审核通过或已完成收货");
        }
        PmsOrderDetailEntity pmsOrderDetail = pmsOrderDetailMapper.selectOne(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getId, pmsInNoticeOrderDTO.getOrderDetailId()).last("FOR UPDATE"));
        if (pmsOrderDetail == null || !Objects.equals(pmsOrderEntity.getId(), pmsOrderDetail.getPmsOrderId())
                || !Objects.equals(pmsOrderEntity.getFactoryCode(), pmsOrderDetail.getFactoryCode())
                || (StrUtil.isNotBlank(pmsInNoticeOrderDTO.getMatCode())
                && !Objects.equals(pmsInNoticeOrderDTO.getMatCode(), pmsOrderDetail.getMatCode()))) {
            throw new CheckedException("采购到货订单行与订单、工厂或物料不一致");
        }
        Double inNum = pmsInNoticeOrderDTO.getNum();
        Double arrivalNum = Optional.ofNullable(pmsOrderDetail.getArrivalNum()).orElse(0.0);
        double addNum = inNum + arrivalNum;
        if (addNum - Optional.ofNullable(pmsOrderDetail.getOrderNum()).orElse(0.0) > 0.00000001) {
            throw new CheckedException("累计到货量不能超过采购订单行订购数量");
        }

        // 更新采购订单明细数量
        pmsOrderDetail.setArrivalNum(addNum);
        pmsOrderDetailMapper.updateById(pmsOrderDetail);

        boolean fullyReceived = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .eq(PmsOrderDetailEntity::getPmsOrderId, pmsOrderEntity.getId())).stream()
                .allMatch(detail -> Optional.ofNullable(detail.getArrivalNum()).orElse(0.0) + 0.00000001
                        >= Optional.ofNullable(detail.getOrderNum()).orElse(0.0));
        if (fullyReceived) {
            pmsOrderEntity.setStatus(PmsOrderStatusEnum.COMPLETE.getCode());

            // 如果订单完成，自动生成业务对账单
            pmsBusinessReconciliationService.saveByPmsOrder(pmsOrderEntity);
        } else {
            pmsOrderEntity.setStatus(PmsOrderStatusEnum.PART_RECEIVE.getCode());
        }
        this.updateById(pmsOrderEntity);

        // 更新采购计划数量
        pmsPlanService.updateInNoticeSave(pmsOrderDetail, inNum);

        return Boolean.TRUE;
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购订单列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    @Override
    public R importPmsOrder(List<PmsOrderExcelVO> excelVOList, BindingResult bindingResult, String factoryCode) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 查询供应商列表并转化为map
        Map<String, MdmSupplierEntity> supplierMap = remoteSupplierService.getList(null).getData()
                .stream()
                .collect(Collectors.toMap(MdmSupplierEntity::getSupplierCode, Function.identity(), (a, b) -> a));
        // 查询物料列表并转化为map
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));
        // 构造订单主表list
        List<PmsOrderEntity> insertList = new ArrayList<>();
        // 构造订单明细表list
        List<PmsOrderDetailEntity> insertChildrenList = new ArrayList<>();
        Snowflake snowflake = IdUtil.getSnowflake();

        // 按supplierCode分组
        Map<String, List<PmsOrderExcelVO>> groupedBySupplier = excelVOList.stream()
                .collect(Collectors.groupingBy(PmsOrderExcelVO::getSupplierCode));

        for (Map.Entry<String, List<PmsOrderExcelVO>> entry : groupedBySupplier.entrySet()) {
            String supplierCode = entry.getKey();
            List<PmsOrderExcelVO> groupList = entry.getValue();

            List<PmsOrderDetailEntity> orderChildrenList = new ArrayList<>();
            // 用于记录当前供应商下已处理的物料编码
            Set<String> processedMatCodes = new HashSet<>();

            // 创建订单主表
            PmsOrderEntity orderEntity = new PmsOrderEntity();
            orderEntity.setId(snowflake.nextId());
            orderEntity.setPmsOrderNo(remoteSeqConfigService.create("pms_order").getData());
            orderEntity.setSupplierCode(supplierCode);
            orderEntity.setStatus(PmsOrderStatusEnum.NO_CHECK.getCode());
            orderEntity.setType(Boolean.TRUE);
            orderEntity.setFactoryCode(factoryCode);

            // 处理订单明细
            for (PmsOrderExcelVO excelVO : groupList) {
                Set<String> errorMsg = new HashSet<>();
                // 校验供应商
                MdmSupplierEntity mdmSupplier = supplierMap.get(supplierCode);
                if (mdmSupplier == null) {
                    errorMsg.add(supplierCode + "供应商编码不存在");
                } else {
                    orderEntity.setSupplierName(mdmSupplier.getSupplierName());
                }
                // 校验物料
                String matCode = excelVO.getMatCode();
                // 检查物料是否重复
                if (processedMatCodes.contains(matCode)) {
                    errorMsg.add(matCode + "物料编码在同一供应商下重复");
                } else {
                    processedMatCodes.add(matCode);
                    MdmMaterialEntity mdmMaterialEntity = materialMap.get(matCode);
                    if (mdmMaterialEntity == null) {
                        errorMsg.add(matCode + "物料编码不存在");
                    } else {
                        excelVO.setMatName(mdmMaterialEntity.getMaterialName());
                        excelVO.setMatUnit(mdmMaterialEntity.getBasicUnit());
                        excelVO.setMatSpec(mdmMaterialEntity.getSpecificationModel());
                    }
                }

                if (!errorMsg.isEmpty()) {
                    errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
                    continue;
                }

                // 创建订单明细
                PmsOrderDetailEntity detailEntity = new PmsOrderDetailEntity();
                BeanUtils.copyProperties(excelVO, detailEntity);
                detailEntity.setPmsOrderId(orderEntity.getId());
                detailEntity.setFactoryCode(factoryCode);
                orderChildrenList.add(detailEntity);
            }

            // 只有当有明细数据时才添加订单
            if (!orderChildrenList.isEmpty()) {
                insertList.add(orderEntity);
                insertChildrenList.addAll(orderChildrenList);
            }
        }

        // 批量保存数据
        this.saveBatch(insertList);
        for (PmsOrderDetailEntity detail : insertChildrenList) {
            pmsOrderDetailMapper.insert(detail);
        }

        if (CollUtil.isNotEmpty(errorMessageList)) {
            // 按lineNum排序错误信息
            errorMessageList.sort(Comparator.comparing(ErrorMessage::getLineNum));
            return R.failed(errorMessageList);
        }
        return R.ok(null, "采购订单导入成功");
    }

    /**
     * 导出excel 表格
     *
     * @param pmsOrder 查询条件
     * @param ids      导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<PmsOrderExcelVO> export(PmsOrderEntity pmsOrder, Long[] ids) {
        List<PmsOrderExcelVO> resultList = new ArrayList<>();
        List<PmsOrderEntity> list = this.listDeep(Wrappers.lambdaQuery(pmsOrder).in(ArrayUtil.isNotEmpty(ids), PmsOrderEntity::getId, ids));
        for (PmsOrderEntity order : list) {
            for (PmsOrderDetailEntity pmsOrderDetail : order.getPmsOrderDetailList()) {
                PmsOrderExcelVO pmsOrderExcelVO = new PmsOrderExcelVO();
                BeanUtils.copyProperties(pmsOrderDetail, pmsOrderExcelVO);
                pmsOrderExcelVO.setPmsOrderNo(order.getPmsOrderNo());
                pmsOrderExcelVO.setSupplierCode(order.getSupplierCode());
                pmsOrderExcelVO.setSupplierName(order.getSupplierName());
                pmsOrderExcelVO.setStatus(PmsOrderStatusEnum.getDescByCode(order.getStatus()));
                resultList.add(pmsOrderExcelVO);
            }
        }
        return resultList;
    }

    /**
     * 分页查询订单
     *
     * @param page     分页对象
     * @param pmsOrder 采购订单表
     * @return
     */
    @Override
    public Page<PmsOrderDetailVO> getPmsOrderPageDetail(Page page, PmsOrderEntity pmsOrder) {
        MPJLambdaWrapper<PmsOrderEntity> wrapper = new MPJLambdaWrapper<PmsOrderEntity>()
                .selectAll(PmsOrderEntity.class)
                .leftJoin(PmsOrderDetailEntity.class, PmsOrderDetailEntity::getPmsOrderId, PmsOrderEntity::getId)
                .like(StrUtil.isNotBlank(pmsOrder.getPmsOrderNo()), PmsOrderEntity::getPmsOrderNo, pmsOrder.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierCode()), PmsOrderEntity::getSupplierCode, pmsOrder.getSupplierCode())
                .like(StrUtil.isNotBlank(pmsOrder.getSupplierName()), PmsOrderEntity::getSupplierName, pmsOrder.getSupplierName())
                .gt(PmsOrderDetailEntity::getArrivalNum, 0)
                .groupBy(PmsOrderEntity::getId);
        return baseMapper.selectJoinPage(page, PmsOrderDetailVO.class, wrapper);
    }

    /**
     * 采购订单打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String print(PrintReq printReq) {
        List<PmsOrderEntity> pmsOrderEntities = this.listByIdsDeep(Arrays.asList(printReq.getIds()));
        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(printReq.getTemplateCode());
        JSONConfig jsonConfig = new JSONConfig();
        jsonConfig.setDateFormat("yyyy-MM-dd");
        String jsonData = JSONUtil.toJsonStr(pmsOrderEntities, jsonConfig);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }

    /**
     * 通过采购订单id查询明细
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    @Override
    public List<PmsOrderDetailEntity> getDetailsById(Long id) {
        return pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, id).gt(PmsOrderDetailEntity::getArrivalNum, 0));
    }

    /**
     * 采购订单导出
     *
     * @param id id
     */
    @Override
    public void download(Long id, HttpServletResponse response) {
        response.setContentType("application/octet-stream; charset=UTF-8");

        // 获取填充模版
        ClassPathResource classPathResource = new ClassPathResource("template/pmsOrder.xlsx");
        try (ExcelWriter excelWriter = EasyExcel.write(response.getOutputStream()).withTemplate(classPathResource.getInputStream()).build()) {
            WriteSheet writeSheet = EasyExcel.writerSheet(0).build();

            PmsOrderEntity pmsOrderEntity = this.getByIdDeep(id);
            Map<String, Object> map = MapUtils.newHashMap();
            map.put("pmsOrderNo", pmsOrderEntity.getPmsOrderNo());
            map.put("supplierCode", pmsOrderEntity.getSupplierCode());
            map.put("supplierName", pmsOrderEntity.getSupplierName());
            WriteCellData<Void> writeCellData = new WriteCellData<>();
            List<ImageData> imageDataList = new ArrayList<>();
            ImageData imageData = new ImageData();
            // 设置图片
            imageData.setImage(remotePrintService.createQrInputStream(pmsOrderEntity.getPmsOrderNo()).getData());
            // 上右下左需要留空，通过这种方式调整图片大小，单位为像素
            imageData.setTop(10);
            imageData.setRight(10);
            imageData.setBottom(10);
            imageData.setLeft(10);
            // 设置图片插入位置
            imageData.setRelativeFirstRowIndex(0);
            imageData.setRelativeFirstColumnIndex(0);
            imageData.setRelativeLastRowIndex(2);
            imageData.setRelativeLastColumnIndex(1);
            imageDataList.add(imageData);
            writeCellData.setImageDataList(imageDataList);
            map.put("img", writeCellData);
            excelWriter.fill(map, writeSheet);
            List<Map<String, Object>> maps = new ArrayList<>();
            int no = 0;
            for (PmsOrderDetailEntity pmsOrderDetail : pmsOrderEntity.getPmsOrderDetailList()) {
                no++;
                Map<String, Object> detailMap = MapUtils.newHashMap();
                detailMap.put("no", no);
                detailMap.put("matCode", pmsOrderDetail.getMatCode());
                detailMap.put("matName", pmsOrderDetail.getMatName());
                detailMap.put("matSpec", pmsOrderDetail.getMatSpec());
                detailMap.put("matUnit", pmsOrderDetail.getMatUnit());
                detailMap.put("orderNum", pmsOrderDetail.getOrderNum());
                LocalDate deliveryDate = pmsOrderDetail.getDeliveryTime();
                String formattedDeliveryDate = deliveryDate != null ? deliveryDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "";
                detailMap.put("deliveryTime", formattedDeliveryDate);
                detailMap.put("arrivalNum", pmsOrderDetail.getArrivalNum() != null ? pmsOrderDetail.getArrivalNum() : 0);
                maps.add(detailMap);
            }
            excelWriter.fill(maps, writeSheet);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 审核
     *
     * @param ids 采购订单表ids
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean check(Long[] ids) {
        List<PmsOrderEntity> pmsOrderEntities = this.listByIds(Arrays.asList(ids));

        if (pmsOrderEntities.size() != Arrays.stream(ids).distinct().count()
                || pmsOrderEntities.stream().anyMatch(order -> !PmsOrderStatusEnum.NO_CHECK.getCode().equals(order.getStatus()))) {
            throw new CheckedException("只能提交全部存在且未审核的采购订单；驳回订单须先编辑形成新版本");
        }

        // 校验状态
        pmsOrderEntities.forEach(pmsOrder -> {
            checkOrderSupplier(pmsOrder.getSupplierCode(), PmsOrderStatusEnum.CHECKING);
        });

        Map<Long, List<PmsOrderDetailEntity>> orderDetailMap = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .in(PmsOrderDetailEntity::getPmsOrderId, Arrays.asList(ids)))
                .stream()
                .collect(Collectors.groupingBy(PmsOrderDetailEntity::getPmsOrderId));
        for (PmsOrderEntity pmsOrderEntity : pmsOrderEntities) {
            // 转换表单数据
            Map<String, Object> map = BeanUtil.beanToMap(pmsOrderEntity);

            Map<String, Object> formData = new HashMap<>();
            for (PmsPlanFlowFieldEnum fieldEnum : PmsPlanFlowFieldEnum.values()) {
                formData.put(fieldEnum.getName(), map.get(fieldEnum.getCode()));
            }
            // 添加订单明细表格数据
            List<List<Map<String, Object>>> tableData = new ArrayList<>();
            List<PmsOrderDetailEntity> pmsOrderDetailEntities = orderDetailMap.get(pmsOrderEntity.getId());
            for (PmsOrderDetailEntity detail : pmsOrderDetailEntities) {
                Map<String, Object> detailBeanMap = BeanUtil.beanToMap(detail);
                List<Map<String, Object>> rowDataList = new ArrayList<>();
                for (PmsPlanFlowFieldDetailEnum fieldEnum : PmsPlanFlowFieldDetailEnum.values()) {
                    Map<String, Object> rowData = new HashMap<>();
                    rowData.put("name", fieldEnum.getName());
                    rowData.put("value", detailBeanMap.get(fieldEnum.getCode()));
                    rowDataList.add(rowData);
                }
                tableData.add(rowDataList);
            }
            formData.put("订单明细", JSON.toJSONString(tableData));

            formData.put("billId", pmsOrderEntity.getPmsOrderNo());
            StartFlowDataDTO dto = new StartFlowDataDTO()
                    .setFlowName(OrderTypeEnum.PMS_ORDER.getFlowName())
                    .setBizService(Constants.Name)
                    .setBackUrl("/pmsOrder/processBackChangeState")
                    .setFormData(formData);

            // 发起任务流程
            R<Boolean> booleanR = remoteProcessAdapterService.startFlow(dto);
            if (!booleanR.isOk()) {
                // 使用正则表达式匹配msg中的内容
                Pattern pattern = Pattern.compile("\"msg\":\"([^\"]*)\"");
                Matcher matcher = pattern.matcher(booleanR.getMsg());
                if (matcher.find()) {
                    // 提取出msg的内容
                    String msg = matcher.group(1);
                    throw new CheckedException("审批流程启动失败:" + msg);
                }
                throw new CheckedException("审批流程启动失败");
            }
            this.update(Wrappers.lambdaUpdate(PmsOrderEntity.class)
                    .eq(PmsOrderEntity::getId, pmsOrderEntity.getId())
                    .set(PmsOrderEntity::getStatus, PmsOrderStatusEnum.CHECKING.getCode()));
        }
        return Boolean.TRUE;
    }

    private void rollbackOrderPlanAllocations(Long orderId) {
        List<PmsPlanEntity> allocations = pmsOrderDetailMapper.selectList(
                        new LambdaQueryWrapper<PmsOrderDetailEntity>()
                                .eq(PmsOrderDetailEntity::getPmsOrderId, orderId)).stream()
                .filter(detail -> StrUtil.isNotBlank(detail.getPmsPlanNo()))
                .map(detail -> {
                    PmsPlanEntity allocation = new PmsPlanEntity();
                    allocation.setPmsPlanNo(detail.getPmsPlanNo());
                    allocation.setPlanOrderNum(Optional.ofNullable(detail.getOrderNum()).orElse(0D));
                    return allocation;
                }).toList();
        pmsPlanService.rollbackPlanOrderNum(allocations);
    }

    /**
     * 审核-流程结束修改状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean processBackChangeState(FlowInstanceBizRecordEntity backDTO) {
        // 审批结果
        String result = backDTO.getResult();
        FlowResultEnum resultEnum = FlowResultEnum.getByCode(result);
        if (resultEnum == null) {
            throw new CheckedException("采购订单审批回调结果无效");
        }
        boolean approveRes = FlowResultEnum.AGREE.getCode().equals(resultEnum.getCode());
        // 表单数据
        Map<String, Object> formData = backDTO.getFormData();
        Map<String, Object> formDataEntity = new HashMap<>();
        for (PmsPlanFlowFieldEnum fieldEnum : PmsPlanFlowFieldEnum.values()) {
            formDataEntity.put(fieldEnum.getCode(), formData.get(fieldEnum.getName()));
        }
        PmsOrderEntity entity = BeanUtil.toBean(formDataEntity, PmsOrderEntity.class);
        PmsOrderEntity current = getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, backDTO.getBillId()).last("FOR UPDATE"), false);
        if (current == null) throw new CheckedException("采购订单审批回调对应订单不存在");
        String targetStatus = approveRes ? PmsOrderStatusEnum.CHECK_SUCCESS.getCode() : PmsOrderStatusEnum.REJECT.getCode();
        if (Objects.equals(current.getStatus(), targetStatus)) return Boolean.TRUE;
        if (!PmsOrderStatusEnum.CHECKING.getCode().equals(current.getStatus())) {
            throw new CheckedException("采购订单已不处于审批中，拒绝过期或冲突回调");
        }
        if (!approveRes) {
            List<PmsPlanEntity> rollbacks = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                            .eq(PmsOrderDetailEntity::getPmsOrderId, current.getId())).stream()
                    .filter(detail -> StrUtil.isNotBlank(detail.getPmsPlanNo()))
                    .map(detail -> {
                        PmsPlanEntity plan = new PmsPlanEntity();
                        plan.setPmsPlanNo(detail.getPmsPlanNo());
                        plan.setPlanOrderNum(Optional.ofNullable(detail.getOrderNum()).orElse(0D));
                        return plan;
                    }).toList();
            pmsPlanService.rollbackPlanOrderNum(rollbacks);
        }
        current.setStatus(targetStatus);
        current.setCheckReason(entity.getCheckReason());
        if (!this.updateById(current)) throw new CheckedException("采购订单审批结果保存失败");
        if (!approveRes && "AGENT_SHORTAGE".equals(current.getSourceType())) {
            agentPurchaseStatusOutboxService.enqueueRejected(current,
                    StrUtil.blankToDefault(entity.getCheckReason(), "PMS正式审批驳回"));
        }
        return Boolean.TRUE;
    }

    /**
     * 通过采购订单id查询明细和供应商信息
     *
     * @param id 采购订单id
     * @return R  对象列表
     */
    @Override
    public PmsOrderSupplierVO getOrderDataByContract(Long id) {
        PmsOrderSupplierVO pmsOrderSupplierVO = new PmsOrderSupplierVO();
        // 查询采购订单和采购订单明细
        PmsOrderEntity pmsOrderEntity = this.getByIdDeep(id);
        BeanUtil.copyProperties(pmsOrderEntity, pmsOrderSupplierVO);
        BigDecimal totalPrice = pmsOrderEntity.getPmsOrderDetailList().stream()
                .filter(detail -> detail.getPrice() != null && detail.getOrderNum() != null) // 过滤掉price或orderNum为null的情况
                .map(detail -> detail.getPrice().multiply(BigDecimal.valueOf(detail.getOrderNum()))) // 将price和orderNum相乘
                .reduce(BigDecimal.ZERO, BigDecimal::add); // 累加结果，默认值为BigDecimal.ZERO
        pmsOrderSupplierVO.setContractAmount(totalPrice);
        pmsOrderSupplierVO.setPmsContractDetailList(pmsOrderEntity.getPmsOrderDetailList());
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setSupplierCode(pmsOrderSupplierVO.getSupplierCode());
        R<MdmSupplierEntity> detail = remoteSupplierService.getDetails(mdmSupplierEntity);
        if (detail.getCode() == 0 && detail.getData() != null) {
            // 查询供应商详情
            MdmSupplierEntity supplierEntity = detail.getData();
            PmsSupplierDetailEntity detailEntity = pmsSupplierDetailService.getOne(new LambdaQueryWrapper<PmsSupplierDetailEntity>()
                    .eq(PmsSupplierDetailEntity::getSupplierId, supplierEntity.getId()));
            BeanUtil.copyProperties(detailEntity, pmsOrderSupplierVO);
            pmsOrderSupplierVO.setBank(supplierEntity.getBank());
            pmsOrderSupplierVO.setAccount(supplierEntity.getAccount());
            pmsOrderSupplierVO.setAddress(supplierEntity.getDetailedAddress());
        }
        return pmsOrderSupplierVO;
    }
}
