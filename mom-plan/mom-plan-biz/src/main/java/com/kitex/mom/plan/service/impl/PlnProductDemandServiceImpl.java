package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mdm.feign.RemoteCustomerService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.dto.MatMrpDTO;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import com.kitex.mom.plan.vo.PlnProductDemandExcelVO;
import com.kitex.mom.plan.vo.PlnTemporaryDemandDateVO;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsLiveStockByProductCodeDto;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产计划表
 *
 * @author kitex
 * @date 2024-11-06 17:36:16
 */
@Service
@AllArgsConstructor
@EnableAsync
@Slf4j
public class PlnProductDemandServiceImpl extends ServiceImpl<PlnProductDemandMapper, PlnProductDemandEntity>
        implements PlnProductDemandService {

    private final RemoteCustomerService remoteCustomerService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteBomService remoteBomService;

    private final RemoteLiveStockService remoteLiveStockService;

    private final PlanMatMrpService planMatMrpService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    /**
     * 导出Excel
     *
     * @param plnProductDemand
     * @param ids
     * @return
     */
    @Override
    public List<PlnProductDemandExcelVO> exportExcel(PlnProductDemandEntity plnProductDemand, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(plnProductDemand).in(ArrayUtil.isNotEmpty(ids), PlnProductDemandEntity::getId, ids))
                .stream()
                .map(entity -> {
                    PlnProductDemandExcelVO excelVO = new PlnProductDemandExcelVO();
                    BeanUtil.copyProperties(entity, excelVO);
                    excelVO.setPlanState(PlanStatusEnum.getDescByCode(entity.getPlanState()));
                    return excelVO;
                }).toList();
    }

    /**
     * 新增生产计划
     *
     * @param plnProductDemand
     * @return
     */
    @Override
    public Boolean savePlan(PlnProductDemandEntity plnProductDemand) {
        if (StrUtil.isBlank(plnProductDemand.getDemandNo())) {
            plnProductDemand.setDemandNo(remoteSeqConfigService.create("plan_demand").getData());
        }
        // 校验需求数量
        if (plnProductDemand.getNeedNum() == null || plnProductDemand.getNeedNum().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("需求数量无效：需求数量必须大于零");
        }
        plnProductDemand.setPlanState(PlanStatusEnum.CANCEL.getCode());
        return this.save(plnProductDemand);
    }

    /**
     * 通过条件查询需求日期
     *
     * @param templateQueryDTO 查询条件
     */
    @Override
    public List<Map<String, Object>> getDemandDate(TemporaryPlanQueryDTO templateQueryDTO) {
        LambdaQueryWrapper<PlnProductDemandEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PlnProductDemandEntity::getPlanState, PlanStatusEnum.RECEIVE.getCode());
        wrapper.in(ObjUtil.isNotEmpty(templateQueryDTO.getMatCodes()), PlnProductDemandEntity::getProductCode, templateQueryDTO.getMatCodes());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getMatName()), PlnProductDemandEntity::getProductName, templateQueryDTO.getMatName());
        wrapper.like(ObjUtil.isNotEmpty(templateQueryDTO.getCustomerCode()), PlnProductDemandEntity::getCustomerCode, templateQueryDTO.getCustomerCode());
        if (templateQueryDTO.getPlanNum() == null) {
            templateQueryDTO.setPlanNum(1);
        }
        LocalDate enTime = templateQueryDTO.getTime().plusDays(templateQueryDTO.getPlanNum() - 1);
        wrapper.between(PlnProductDemandEntity::getPlanEndDate, templateQueryDTO.getTime(), enTime);
        wrapper.groupBy(PlnProductDemandEntity::getProductCode, PlnProductDemandEntity::getPlanEndDate);
        List<PlnTemporaryDemandDateVO> demandDate = baseMapper.getDemandDate(wrapper);
        // 转换数据结构
        List<Map<String, Object>> resultList = demandDate.stream().collect(Collectors.groupingBy(
                PlnTemporaryDemandDateVO::getProductCode,
                Collectors.toMap(
                        PlnTemporaryDemandDateVO::getMonthDay,
                        vo -> vo.getNum().toString()
                )
        )).entrySet().stream().map(entry -> {
            Map<String, Object> map = new HashMap<>();
            map.put("productCode", entry.getKey());
            map.put("date", entry.getValue());
            return map;
        }).collect(Collectors.toList());
        return resultList;
    }

    /**
     * 导入客户订单excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Override
    public R importExcel(List<PlnProductDemandExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 主数据-客户map
        Map<String, MdmCustomerEntity> customerMap = remoteCustomerService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(),
                        (a, b) -> a));
        // 主数据-物料map
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getList().getData()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(),
                        (a, b) -> a));
        List<PlnProductDemandEntity> insertList = new ArrayList<>();
        for (PlnProductDemandExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            MdmCustomerEntity mdmCustomerEntity = customerMap.get(excelVO.getCustomerCode());
            if (mdmCustomerEntity == null) {
                errorMsg.add(excelVO.getCustomerCode() + "客户编码不存在");
            }
            MdmMaterialEntity mdmMaterialEntity = materialMap.get(excelVO.getProductCode());
            if (mdmMaterialEntity == null) {
                errorMsg.add(excelVO.getProductCode() + "物料编码不存在");
            }
            if (CollUtil.isEmpty(errorMsg)) {
                insertList.add(insertExcel(excelVO, mdmCustomerEntity, mdmMaterialEntity));
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "物料需求导入成功");
    }

    /**
     * mrp运算
     *
     * @param ids 计划ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R mrp(Long[] ids) {
        // 查询计划订单
        List<PlnProductDemandEntity> plnProductDemandEntities = this.listByIds(Arrays.asList(ids));
        // 去重获取产品编码列表
        List<String> productCodes = plnProductDemandEntities.stream().map(PlnProductDemandEntity::getProductCode)
                .distinct().collect(Collectors.toList());
        // 查询bom
        Map<String, MdmBomChildDTO> productBomMap = remoteBomService.getAllChildMaterials(productCodes).getData();
        // 以Map形式存储扁平化的数据
        Map<String, List<MdmBomChildDTO>> bomDataMap = new HashMap<>();
        for (MdmBomChildDTO bomChild : productBomMap.values()) {
            List<MdmBomChildDTO> bomDataNew = new ArrayList<>();
            // 获取当前节点的子节点并递归扁平化
            List<MdmBomChildDTO> children = bomChild.getChildren();
            if (children != null && !children.isEmpty()) {
                flattenBomData(children, bomDataNew);
            }
            // 将当前节点本身也加入到扁平化结果中
            bomDataNew.add(bomChild);
            bomDataMap.put(bomChild.getMaterialCode(), bomDataNew);
        }
        List<String> materialCodeList = bomDataMap.values().stream() // 获取所有值（List<MdmBomChildDTO>）
                .flatMap(List::stream) // 将每个 List<MdmBomChildDTO> 扁平化为单个流
                .map(MdmBomChildDTO::getMaterialCode) // 提取 materialCode
                .filter(Objects::nonNull) // 过滤掉可能为 null 的值
                .collect(Collectors.toList()); // 收集为 List<String>
        // 查询物料信息
        Map<String, MdmMaterialEntity> materialMap = remoteMaterialService.getListByCodes(materialCodeList).getData().stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));
        // 查询物料库存
        WmsLiveStockByProductCodeDto liveStock = new WmsLiveStockByProductCodeDto();
        liveStock.setProductCodeSet(new HashSet<>(materialCodeList));
        Map<String, Double> liveStockMap = remoteLiveStockService.getLiveStockByProductCodeList(liveStock).getData().stream()
                .collect(Collectors.groupingBy(
                        WmsLiveStockEntity::getMatCode,
                        Collectors.summingDouble(entity -> entity.getMatNum() != null ? entity.getMatNum() : 0.0)
                ));
        // 创建列表
        List<PlanMatMrpEntity> completableFutures = new ArrayList<>();
        // 创建错误列表
        List<String> errorList = new ArrayList<>();
        // 创建提示列表
        List<String> warningList = new ArrayList<>();
        // 提交任务
        for (PlnProductDemandEntity plnProductDemandEntity : plnProductDemandEntities) {
            // 计算MRP数据
            AtomicReference<String> errorMsg = new AtomicReference<>("");
            List<PlanMatMrpEntity> planMatMrpEntities = calculateMrpData(plnProductDemandEntity,
                    bomDataMap.get(plnProductDemandEntity.getProductCode()), materialMap, liveStockMap, errorMsg);
            if (StrUtil.isNotEmpty(errorMsg.get())) {
                errorList.add(errorMsg.get());
                continue;
            }
            // 收集结果
            if (!planMatMrpEntities.isEmpty()) {
                planMatMrpEntities.forEach(planMatMrpEntity -> {
                    planMatMrpEntity.setFactoryCode(plnProductDemandEntity.getFactoryCode());
                });
                completableFutures.addAll(planMatMrpEntities);
            } else {
                warningList.add(String.format("客户订单号【%s】产品名称【%s】产品编码【%s】库存量充足",
                        plnProductDemandEntity.getDemandNo(), plnProductDemandEntity.getProductName(), plnProductDemandEntity.getProductCode()));
            }
        }
        // 检查是否有错误
        if (!errorList.isEmpty()) {
            // 如果有错误，抛出异常
            throw new CheckedException(String.join("; ", errorList));
        }
        // 如果没有MRP实体，直接返回
        if (completableFutures.isEmpty()) {
            return R.ok(String.join("; ", warningList));
        }
        List<String> demanNos = completableFutures.stream().map(PlanMatMrpEntity::getPlanDemandNo).collect(Collectors.toList());
        // 更新计划状态
        this.update(new LambdaUpdateWrapper<PlnProductDemandEntity>()
                .set(PlnProductDemandEntity::getPlanState, PlanStatusEnum.MRP.getCode())
                .in(PlnProductDemandEntity::getDemandNo, demanNos));
        // 保存MRP数据
        planMatMrpService.saveBatch(completableFutures);
        return R.ok();
    }

    /**
     * 计算MRP数据，不进行数据库操作
     *
     * @param plnProductDemandEntity 计划订单实体
     * @param bomData                扁平化后的bom
     * @param materialMap            物料map
     * @param liveStockMap           库存map
     * @param errorMsg               错误信息
     * @return 包含MRP计算结果的Map
     */
    private List<PlanMatMrpEntity> calculateMrpData(PlnProductDemandEntity plnProductDemandEntity, List<MdmBomChildDTO> bomData,
                                                    Map<String, MdmMaterialEntity> materialMap, Map<String, Double> liveStockMap,
                                                    AtomicReference<String> errorMsg) {
        if (bomData.size() == 1) {
            errorMsg.set(String.format("客户订单号【%s】产品名称【%s】产品编码【%s】没有BOM信息",
                    plnProductDemandEntity.getDemandNo(), plnProductDemandEntity.getProductName(), plnProductDemandEntity.getProductCode()));
            return new ArrayList<>();
        }
        // 提取所有物料编码
        List<String> allMaterialCodes = bomData.stream().map(MdmBomChildDTO::getMaterialCode).collect(Collectors.toList());
        allMaterialCodes.add(plnProductDemandEntity.getProductCode());

        // 从materialMap和liveStockMap中获取与allMaterialCodes对应的数据组建物料基本信息
        List<MatMrpDTO> matMrpDTOList = new ArrayList<>();
        // 根据allMaterialCodes组装物料和库存数据到MatMrpDTO对象中
        for (String materialCode : allMaterialCodes) {
            if (materialMap.containsKey(materialCode)) {
                MatMrpDTO matMrpDTO = new MatMrpDTO();
                // 设置物料信息
                MdmMaterialEntity material = materialMap.get(materialCode);
                matMrpDTO.setMaterialCode(material.getMaterialCode());
                matMrpDTO.setMaterialName(material.getMaterialName());
                matMrpDTO.setMaterialAttribute(material.getMaterialAttribute());
                matMrpDTO.setBasicUnit(material.getBasicUnit());
                matMrpDTO.setSpecificationModel(material.getSpecificationModel());
                matMrpDTO.setFixedLeadTime(material.getFixedLeadTime() != null ? material.getFixedLeadTime() : new BigDecimal(0));
                matMrpDTO.setIssuanceBatch(material.getIssuanceBatch() != null ? material.getIssuanceBatch() : new BigDecimal(1));
                matMrpDTO.setMinOrderQuantity(material.getMinOrderQuantity() != null ? material.getMinOrderQuantity() : 1);
                matMrpDTO.setMinStock(material.getMinStock() != null ? material.getMinStock() : new BigDecimal(0));
                // 设置库存信息
//                matMrpDTO.setStock(liveStockMap.getOrDefault(materialCode, 0.0));
                // 添加到列表
                matMrpDTOList.add(matMrpDTO);
            } else {
                errorMsg.set(String.format("未查询到客户订单号【%s】产品【%s】bom中物料编码【%s】对应的物料信息",
                        plnProductDemandEntity.getDemandNo(), plnProductDemandEntity.getProductName(), materialCode));
                return new ArrayList<>();
            }
        }
        // 计算MRP数据
        Map<String, PlanMatMrpEntity> grossDemand = new HashMap<>();
        // 获取产品编码和需求数量
        BigDecimal needNum = plnProductDemandEntity.getNeedNum();
        LocalDate planEndDate = plnProductDemandEntity.getPlanEndDate();
        // 调用BOM展开方法计算毛需求
        explodeBOM(plnProductDemandEntity, bomData.get(0).getBomId(), needNum, planEndDate, grossDemand, bomData, matMrpDTOList);
        // 计算净需求、时间分段、批量调整和生成计划订单
        List<PlanMatMrpEntity> matMrpList = new ArrayList<>();
        for (Map.Entry<String, PlanMatMrpEntity> entry : grossDemand.entrySet()) {
            PlanMatMrpEntity mrpEntity = entry.getValue();
            if (mrpEntity != null) {
                // 计算净需求
                BigDecimal gross = mrpEntity.getNum();
                BigDecimal net = calculateNetDemand(mrpEntity, gross, liveStockMap);
                if (net.compareTo(BigDecimal.ZERO) > 0) {
                    // 计算订单日期（考虑提前期）
                    LocalDate orderDate = mrpEntity.getOrderDate().minusDays(mrpEntity.getLeadTime());
                    // 批量调整
                    BigDecimal orderQuantity = adjustLotSize(mrpEntity, net);
                    // 日期和净需求数添加到mrp运算实体
                    mrpEntity.setOrderDate(orderDate);
                    mrpEntity.setNum(orderQuantity);
                    mrpEntity.setStatus(false);
                    mrpEntity.setPlanDemandNo(plnProductDemandEntity.getDemandNo());
                    mrpEntity.setSaleOrderCode(plnProductDemandEntity.getSaleOrderCode());
                    matMrpList.add(mrpEntity);
                }
            }
        }
        // 返回计算结果
        return matMrpList;
    }

    /**
     * 将树形结构的BOM数据扁平化
     *
     * @param bomData 树形结构的BOM数据
     * @param result  存储扁平化结果的列表
     */
    private void flattenBomData(List<MdmBomChildDTO> bomData, List<MdmBomChildDTO> result) {
        if (bomData == null || bomData.isEmpty()) {
            return;
        }
        for (MdmBomChildDTO bom : bomData) {
            // 创建一个新的DTO对象，避免引用原对象
            MdmBomChildDTO newBom = new MdmBomChildDTO();
            BeanUtil.copyProperties(bom, newBom);
            // 清空子节点，避免重复
            newBom.setChildren(null);
            // 添加到结果列表
            result.add(newBom);
            // 递归处理子节点
            if (bom.getChildren() != null && !bom.getChildren().isEmpty()) {
                flattenBomData(bom.getChildren(), result);
            }
        }
    }

    /**
     * BOM展开，计算毛需求
     *
     * @param plnProductDemandEntity 产品需求
     * @param bomId                  bom id
     * @param quantity               数量
     * @param date                   日期
     * @param grossDemand            毛需求结果
     * @param bomData                BOM数据
     */
    private void explodeBOM(PlnProductDemandEntity plnProductDemandEntity, Long bomId, BigDecimal quantity, LocalDate date, Map<String, PlanMatMrpEntity> grossDemand,
                            List<MdmBomChildDTO> bomData, List<MatMrpDTO> matMrpDTOList) {
        // 找到当前bomId下的所有直接子物料
        List<MdmBomChildDTO> children = bomData.stream().filter(item -> bomId.equals(item.getBomId())).collect(Collectors.toList());

        // 处理每个子物料
        for (MdmBomChildDTO child : children) {
            // 计算子物料的需求数量 = 父物料需求数量 * 子物料单位用量
            BigDecimal childQuantity = quantity.multiply(child.getQuantity());

            // 更新或创建该物料的毛需求记录
            String materialCode = child.getMaterialCode();

            // 查找对应的物料信息
            MatMrpDTO material = matMrpDTOList.stream()
                    .filter(m -> m.getMaterialCode().equals(materialCode))
                    .findFirst()
                    .orElse(null);

            PlanMatMrpEntity planMatMrpEntity = new PlanMatMrpEntity();
            planMatMrpEntity.setBomId(bomId);
            planMatMrpEntity.setSubitemBomId(child.getSubitemBomId());
            planMatMrpEntity.setCustomerCode(plnProductDemandEntity.getCustomerCode());
            planMatMrpEntity.setCustomerName(plnProductDemandEntity.getCustomerName());
            planMatMrpEntity.setMatCode(child.getMaterialCode());
            planMatMrpEntity.setMatName(child.getMaterialName());
            planMatMrpEntity.setMatUnit(material.getBasicUnit());
            planMatMrpEntity.setMatSpec(material.getSpecificationModel());
            planMatMrpEntity.setType(material.getMaterialAttribute());
            planMatMrpEntity.setLeadTime(material.getFixedLeadTime().intValue());
            planMatMrpEntity.setSafetyStock(material.getMinStock().intValue());
            planMatMrpEntity.setMinOrderQuantity(material.getMinOrderQuantity());
            planMatMrpEntity.setLotSize(material.getIssuanceBatch().intValue());
            planMatMrpEntity.setOrderDate(date);
            planMatMrpEntity.setNum(childQuantity);

            // 将PlanMatMrpEntity存入grossDemand
            grossDemand.put(materialCode, planMatMrpEntity);

            // 如果该子物料还有下级BOM，则递归处理
            if (child.getSubitemBomId() != null && child.getSubitemBomId() != 0) {
                explodeBOM(plnProductDemandEntity, child.getSubitemBomId(), childQuantity, date, grossDemand, bomData, matMrpDTOList);
            }
        }
    }

    /**
     * 计算净需求
     *
     * @param material     物料信息
     * @param gross        毛需求
     * @param liveStockMap 库存map
     * @return 净需求
     */
    private BigDecimal calculateNetDemand(PlanMatMrpEntity material, BigDecimal gross, Map<String, Double> liveStockMap) {
        Double matNum = liveStockMap.getOrDefault(material.getMatCode(), 0.0);
        BigDecimal stock = new BigDecimal(matNum);
        BigDecimal safetyStock = new BigDecimal(material.getSafetyStock());
        BigDecimal net = gross.subtract(stock.subtract(safetyStock));
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            double num = matNum - gross.doubleValue() - material.getSafetyStock();
            if (num >= 0) { // 仅在 num >= 0 时更新 map
                liveStockMap.put(material.getMatCode(), num);
            }
            return BigDecimal.ZERO;
        }
        return net;
    }

    /**
     * 批量调整
     *
     * @param material 物料信息
     * @param net      净需求
     * @return 调整后的批量
     */
    private BigDecimal adjustLotSize(PlanMatMrpEntity material, BigDecimal net) {
        int minOrderQuantity = material.getMinOrderQuantity();
        if (net.compareTo(new BigDecimal(minOrderQuantity)) < 0) {
            return new BigDecimal(minOrderQuantity);
        }
        BigDecimal issuanceBatch = new BigDecimal(material.getLotSize());
        return net.divide(issuanceBatch, 0, RoundingMode.UP).multiply(issuanceBatch);
    }

    private PlnProductDemandEntity insertExcel(PlnProductDemandExcelVO excelVO, MdmCustomerEntity mdmCustomerEntity,
                                               MdmMaterialEntity mdmMaterialEntity) {
        PlnProductDemandEntity plnProductDemand = new PlnProductDemandEntity();
        BeanUtil.copyProperties(excelVO, plnProductDemand);
        plnProductDemand.setCustomerName(mdmCustomerEntity.getCustomerName());
        plnProductDemand.setProductName(mdmMaterialEntity.getMaterialName());
        plnProductDemand.setSpecification(mdmMaterialEntity.getSpecificationModel());
        plnProductDemand.setPlanState(PlanStatusEnum.CANCEL.getCode());
        return plnProductDemand;
    }

    /**
     * 接收计划
     *
     * @param planIds
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean receive(Long[] planIds) {
        List<PlnProductDemandEntity> plnProductDemandEntities = this.listByIds(Arrays.asList(planIds));
        plnProductDemandEntities.forEach(plnProductDemand -> {
            plnProductDemand.setPlanState(PlanStatusEnum.RECEIVE.getCode());
        });
        return this.updateBatchById(plnProductDemandEntities);
    }

}



