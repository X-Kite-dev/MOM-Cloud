package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 原工单冻结预留的来源资格；不创建预留，也不把当前库存重新分配给另一个需求。
 */
@Component
@RequiredArgsConstructor
public class MesMaterialSourceEligibility {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final MesTaskMapper tasks;

    private final MesTaskArrangeMapper arrangements;

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new CheckedException(message);
        }
    }

    /**
     * 调用者须传真实父工单及库存；错误冻结正文是UNKNOWN，不能当作没有预留。
     */
    public boolean eligible(MesTaskEntity task, MesWarehouseLiveStockEntity stock,
                            String requirementMatCode, BigDecimal ratio) {
        var links = links(task);
        require(text(requirementMatCode) && ratio != null && ratio.signum() > 0, "物料来源须明确原需求及替代比例");

        if (stock == null || !Objects.equals(stock.getTenantId(), task.getTenantId())
                || !Objects.equals(stock.getFactoryCode(), task.getFactoryCode()) || !text(stock.getMatCode())) {
            return false;
        }
        if (text(stock.getRequirementMatCode()) && !requirementMatCode.equals(stock.getRequirementMatCode())) {
            return false;
        }
        if (stock.getReservationNo() == null || stock.getReservationNo().isEmpty()) {
            return true;
        }
        return requirementMatCode.equals(stock.getRequirementMatCode()) && links.stream().anyMatch(link ->
                link.matches(stock.getReservationNo(), requirementMatCode, stock.getMatCode(), ratio, stock.getWarehouseCode()));
    }

    public void assertEligible(MesTaskEntity task, MesWarehouseLiveStockEntity stock,
                               String requirementMatCode, BigDecimal ratio) {
        require(eligible(task, stock, requirementMatCode, ratio), "实际库存不属于本工单冻结预留、需求、物料、比例或仓库，须重新核对来源");
    }

    /**
     * 预览和实际OUT共用SQL谓词；先过滤所有不合格预留，再核数量完整性、FIFO及锁。
     */
    public void constrain(QueryWrapper<MesWarehouseLiveStockEntity> query, MesTaskEntity task,
                          String requirement, String actual, BigDecimal ratio) {
        var all = links(task);
        require(text(requirement) && text(actual) && ratio != null && ratio.signum() > 0, "来源筛选缺少原需求、实际物料或比例");
        var allowed = all.stream().filter(link -> link.requirement.equals(requirement)
                && link.actual.equals(actual) && link.ratio.compareTo(ratio) == 0).toList();

        query.lambda().and(group -> {
            group.nested(publicStock -> publicStock
                    .and(noReservation -> noReservation.isNull(MesWarehouseLiveStockEntity::getReservationNo)
                            .or().eq(MesWarehouseLiveStockEntity::getReservationNo, ""))
                    .and(demand -> demand.isNull(MesWarehouseLiveStockEntity::getRequirementMatCode)
                            .or().eq(MesWarehouseLiveStockEntity::getRequirementMatCode, "")
                            .or().eq(MesWarehouseLiveStockEntity::getRequirementMatCode, requirement)));
            for (var link : allowed) {
                group.or(reserved -> reserved
                        .eq(MesWarehouseLiveStockEntity::getReservationNo, link.reservation)
                        .eq(MesWarehouseLiveStockEntity::getRequirementMatCode, requirement)
                        .eq(text(link.warehouse), MesWarehouseLiveStockEntity::getWarehouseCode, link.warehouse));
            }
        });
    }

    /**
     * 不接收客户端工单/冻结正文，始终按实际派工重新定位父工单和冻结工序需求。
     */
    public ProductionContext productionContext(MesWarehouseLiveStockInoutDTO request, String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                && text(request.getBillNo()) && text(request.getRequirementMatCode()), "生产扣料缺少真实派工或原需求");

        var rows = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, request.getBillNo()).last("LIMIT 2"));
        require(rows.size() == 1, "生产扣料派工未唯一归属当前租户工厂");
        var arrange = rows.get(0);
        require(text(arrange.getProduceTaskCode()) && (!text(request.getProduceTaskCode())
                || Objects.equals(request.getProduceTaskCode(), arrange.getProduceTaskCode())), "来源请求工序与实际派工不一致");

        var parents = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2"));
        require(parents.size() == 1, "生产扣料父工单缺失或不唯一");
        var task = parents.get(0);
        var route = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(),
                arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
        require(route.getMdmProcessProcedureMatList() != null && route.getMdmProcessProcedureMatList().stream()
                        .filter(r -> r != null && Objects.equals(r.getMaterialCode(), request.getRequirementMatCode())).count() == 1,
                "实际投料/倒冲需求不属于原冻结工序");

        var policy = MesMaterialSubstituteResolver.resolvePolicy(task.getBomSnapshot(), request.getRequirementMatCode());
        require(request.getSubstituteRatio() != null && policy.candidates().stream().anyMatch(candidate ->
                        Objects.equals(candidate.actualMatCode(), request.getMatCode())
                                && candidate.ratio().compareTo(request.getSubstituteRatio()) == 0
                                && (request.getSubstituteFlag() == null || candidate.substitute() == request.getSubstituteFlag())),
                "实际来源物料或比例不属于工单冻结BOM");
        return new ProductionContext(task, arrange.getProduceTaskCode());
    }

    private List<Link> links(MesTaskEntity task) {
        require(task != null && task.getTenantId() != null && task.getTenantId() > 0
                && text(task.getFactoryCode()) && text(task.getCode()), "物料来源缺少实际父工单组织");
        if (task.getMaterialReservationSnapshot() == null || task.getMaterialReservationSnapshot().isEmpty()) {
            return List.of();
        }
        try {
            require(task.getMaterialReservationSnapshot().length() <= 2 * 1024 * 1024, "冻结预留正文超限");
            JsonNode root = JSON.readTree(task.getMaterialReservationSnapshot());
            require(root != null && root.isArray() && root.size() <= 10000, "冻结预留正文不是可核验的完整数组");
            var result = new ArrayList<Link>();
            for (var row : root) {
                require(row.isObject(), "冻结预留含空行或非对象");
                String reservation = code(row, "reservationNo", false);
                String requirement = code(row, "requirementMatCode", false);
                String actual = code(row, "actualMatCode", false);
                String warehouse = code(row, "warehouseCode", true);
                var ratio = number(row, "substituteRatio");
                number(row, "reservedActualNum");
                result.add(new Link(reservation, requirement, actual, ratio, warehouse));
            }
            return List.copyOf(result);
        } catch (CheckedException error) {
            throw error;
        } catch (Exception error) {
            throw new CheckedException("工单冻结预留无法核验，禁止猜测来源归属");
        }
    }

    private String code(JsonNode row, String key, boolean optional) {
        JsonNode value = row.get(key);
        if (optional && (value == null || value.isNull())) {
            return null;
        }
        require(value != null && value.isTextual() && (optional && value.textValue().isEmpty()
                || text(value.textValue()) && value.textValue().equals(value.textValue().strip())
                && value.textValue().length() <= 100), "冻结预留缺少有效" + key);
        return value.textValue();
    }

    private BigDecimal number(JsonNode row, String key) {
        var value = row.get(key);
        require(value != null && (value.isNumber() || value.isTextual()), "冻结预留缺少原数量/比例" + key);
        var result = new BigDecimal(value.asText());
        require(result.signum() > 0, "冻结预留数量/比例非正数");
        return result;
    }

    public record ProductionContext(MesTaskEntity task, String produceTaskCode) {
    }

    private record Link(String reservation, String requirement, String actual, BigDecimal ratio, String warehouse) {

        boolean matches(String r, String requirementCode, String actualCode,
                        BigDecimal actualRatio, String stockWarehouse) {
            return reservation.equals(r) && requirement.equals(requirementCode) && actual.equals(actualCode)
                    && ratio.compareTo(actualRatio) == 0 && (!text(warehouse) || warehouse.equals(stockWarehouse));
        }
    }
}
