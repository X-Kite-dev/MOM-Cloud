package com.kitex.mom.mdm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardLogEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.mapper.MdmMatUnitConversionMapper;
import com.kitex.mom.mdm.mapper.MdmMaterialMapper;
import com.kitex.mom.mdm.mapper.MdmPhysicalTransformStandardLogMapper;
import com.kitex.mom.mdm.mapper.MdmPhysicalTransformStandardMapper;
import com.kitex.mom.mdm.mapper.MdmProcessRouteMapper;
import com.kitex.mom.mdm.mapper.MdmProcessRouteWorkingMapper;
import com.kitex.mom.mdm.service.MdmPhysicalTransformStandardService;
import com.kitex.mom.mdm.support.MdmPhysicalTransformStandardCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.kitex.mom.mdm.support.MdmPhysicalTransformStandardCodec.*;

/**
 * 转换工程版本完整治理。所有原依赖在同域锁内读取并保存；提交/发布重核依赖，
 * 已发布版本只读原正文，不因基础换算或路线后续修改而重新解释。
 */
@Service
@RequiredArgsConstructor
public class MdmPhysicalTransformStandardServiceImpl
        extends ServiceImpl<MdmPhysicalTransformStandardMapper, MdmPhysicalTransformStandardEntity>
        implements MdmPhysicalTransformStandardService {

    private final MdmPhysicalTransformStandardLogMapper logs;
    private final MdmMaterialMapper materials;
    private final MdmProcessRouteMapper routes;
    private final MdmProcessRouteWorkingMapper workings;
    private final MdmMatUnitConversionMapper conversions;

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    public Page<MdmPhysicalTransformStandardEntity> pageStandards(SearchPage page, String no, String material, String status) {
        Identity owner = identity();
        return page(page.toMpPage(), scope(owner)
                .like(no != null && !no.isBlank(), MdmPhysicalTransformStandardEntity::getStandardNo, no)
                .eq(material != null && !material.isBlank(), MdmPhysicalTransformStandardEntity::getMaterialCode, material)
                .eq(status != null && !status.isBlank(), MdmPhysicalTransformStandardEntity::getStatus, status)
                .orderByDesc(MdmPhysicalTransformStandardEntity::getId));
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    @Transactional(readOnly = true)
    public Page<MdmPhysicalTransformStandardDTO.BasisOption> basisPage(SearchPage page, String kind, String material, String routeId) {
        Identity owner = identity();
        code(material, 100, "依据物料");
        if ("ROUTE".equals(kind)) {
            Page<MdmProcessRouteEntity> data = routes.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmProcessRouteEntity>()
                    .eq(MdmProcessRouteEntity::getTenantId, owner.tenantId()).eq(MdmProcessRouteEntity::getFactoryCode, owner.factory())
                    .eq(MdmProcessRouteEntity::getProductCode, material).eq(MdmProcessRouteEntity::getStatus, true)
                    .orderByAsc(MdmProcessRouteEntity::getId));
            return basisResult(data, data.getRecords().stream().map(row -> basisOption(row.getId(), material,
                    row.getRouteCode(), row.getRouteName(), "", "", "", row)).toList());
        }
        if ("CONVERSION".equals(kind)) {
            Page<MdmMatUnitConversionEntity> data = conversions.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmMatUnitConversionEntity>()
                    .eq(MdmMatUnitConversionEntity::getTenantId, owner.tenantId()).eq(MdmMatUnitConversionEntity::getFactoryCode, owner.factory())
                    .eq(MdmMatUnitConversionEntity::getMatCode, material).orderByAsc(MdmMatUnitConversionEntity::getId));
            return basisResult(data, data.getRecords().stream().map(row -> basisOption(row.getId(), material,
                    row.getNonStandardUnit() + " → " + row.getStandardUnit(), "单位换算", row.getNonStandardUnit(),
                    row.getStandardUnit(), row.getRatio(), row)).toList());
        }
        require("WORKING".equals(kind), "未知工程依据类型");
        var route = routes.selectOne(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, owner.tenantId()).eq(MdmProcessRouteEntity::getFactoryCode, owner.factory())
                .eq(MdmProcessRouteEntity::getId, id(routeId)).eq(MdmProcessRouteEntity::getProductCode, material)
                .eq(MdmProcessRouteEntity::getStatus, true));
        require(route != null, "指定实际路线未归属当前物料/组织或未启用");
        Page<MdmProcessRouteWorkingEntity> data = workings.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                .eq(MdmProcessRouteWorkingEntity::getTenantId, owner.tenantId()).eq(MdmProcessRouteWorkingEntity::getFactoryCode, owner.factory())
                .eq(MdmProcessRouteWorkingEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcessRouteWorkingEntity::getOrderNum).orderByAsc(MdmProcessRouteWorkingEntity::getId));
        return basisResult(data, data.getRecords().stream().map(row -> basisOption(row.getId(), material,
                row.getProcessCode(), row.getProcessName(), "", "", "", row)).toList());
    }

    /** 分页元数据仅控制页面；工程依据ID/比例及完整原记录全为文本。 */
    private Page<MdmPhysicalTransformStandardDTO.BasisOption> basisResult(Page<?> source,
            List<MdmPhysicalTransformStandardDTO.BasisOption> records) {
        var result = new Page<MdmPhysicalTransformStandardDTO.BasisOption>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(records);
        return result;
    }

    private MdmPhysicalTransformStandardDTO.BasisOption basisOption(Long id, String material, String code, String name,
            String input, String output, String ratio, Object row) {
        var result = new MdmPhysicalTransformStandardDTO.BasisOption();
        result.setId(id.toString());
        result.setMaterialCode(material);
        result.setCode(code);
        result.setName(name);
        result.setInputUnit(input);
        result.setOutputUnit(output);
        result.setRatio(ratio);
        result.setDetailJson(snapshot(row));
        return result;
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    @Transactional(readOnly = true)
    public MdmPhysicalTransformStandardDTO.Detail detail(Long id) {
        Identity owner = identity();
        var result = new MdmPhysicalTransformStandardDTO.Detail();
        result.setStandard(standard(owner, id, false));
        result.setLogs(logs.selectList(logScope(owner, id)
                .orderByAsc(MdmPhysicalTransformStandardLogEntity::getOperatedTime)
                .orderByAsc(MdmPhysicalTransformStandardLogEntity::getId)));
        return result;
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_edit')")
    @Transactional(rollbackFor = Exception.class)
    public MdmPhysicalTransformStandardEntity saveDraft(String rawJson) {
        var draft = draft(rawJson);
        reason(draft.getReason());
        Identity owner = identity();
        expectScope(owner, draft.getExpectedTenantId(), draft.getExpectedFactoryCode());
        lock(owner);
        boolean fresh = draft.getId().isEmpty();
        boolean copying = !draft.getCopySourceId().isEmpty();
        require(fresh || !copying, "修改既有草稿不能同时声明复制来源");
        MdmPhysicalTransformStandardEntity row;
        String before = null;
        if (fresh) {
            code(draft.getStandardNo(), 100, "标准编码");
            row = new MdmPhysicalTransformStandardEntity();
            int version = 1;
            if (copying) {
                // 用户先在复制编制页明确调整新单位/布局配置，再建立新版本；不自动改旧政策。
                var original = standard(owner, id(draft.getCopySourceId()), true);
                expect(original, draft.getExpectedFingerprint());
                expectOperation(original, draft.getExpectedOperationId());
                require(Objects.equals(original.getStandardNo(), draft.getStandardNo())
                        && Objects.equals(original.getMaterialCode(), draft.getMaterialCode())
                        && Objects.equals(original.getProcessRouteId(), id(draft.getProcessRouteId()))
                        && Objects.equals(original.getProcessCode(), draft.getProcessCode()), "复制编制不能变更原标准家族归属");
                int maximum = baseMapper.maxVersion(owner.tenantId(), owner.factory(), original.getStandardNo());
                require(maximum < Integer.MAX_VALUE, "工程版本号容量已满");
                version = maximum + 1;
                row.setOperationType(original.getOperationType());
                before = snapshot(original);
            } else {
                require(draft.getExpectedFingerprint().isEmpty() && draft.getExpectedOperationId().isEmpty(), "新增首版不能带原版本指纹/动作号");
                require(baseMapper.maxVersion(owner.tenantId(), owner.factory(), draft.getStandardNo()) == 0,
                        "标准家族已存在，须复制真实原版本，不能自行复用版本号");
            }
            row.setId(IdWorker.getId());
            row.setTenantId(owner.tenantId());
            row.setFactoryCode(owner.factory()).setVersionNo(version).setStatus("DRAFT");
            row.setCreateBy(owner.user().getUsername());
            row.setCreateTime(LocalDateTime.now());
            row.setDelFlag("0");
        } else {
            row = standard(owner, id(draft.getId()), true);
            expect(row, draft.getExpectedFingerprint());
            expectOperation(row, draft.getExpectedOperationId());
            require("DRAFT".equals(row.getStatus()), "只有草稿可修改，待复核/已发布/退役必须走受控动作");
            before = snapshot(row);
            require(Objects.equals(row.getStandardNo(), draft.getStandardNo())
                    && Objects.equals(row.getMaterialCode(), draft.getMaterialCode())
                    && Objects.equals(row.getProcessRouteId(), id(draft.getProcessRouteId()))
                    && Objects.equals(row.getProcessCode(), draft.getProcessCode()), "标准家族物料/路线/工序不能原地换归属");
        }
        String normalizedPolicy = policy(draft.getPolicyJson());
        String action = object(normalizedPolicy, 256 * 1024).path("operationType").textValue();
        require(fresh && !copying || Objects.equals(row.getOperationType(), action), "标准家族动作不能原地改变");
        row.setStandardNo(code(draft.getStandardNo(), 100, "标准编码"))
                .setStandardName(code(draft.getStandardName(), 200, "标准名称"))
                .setMaterialCode(code(draft.getMaterialCode(), 100, "实际物料"))
                .setProcessRouteId(id(draft.getProcessRouteId()))
                .setProcessCode(code(draft.getProcessCode(), 100, "实际工序"))
                .setOperationType(action).setPolicyJson(normalizedPolicy)
                .setUnitConversionId(draft.getUnitConversionId().isEmpty() ? null : id(draft.getUnitConversionId()))
                .setPositionLayoutCode(draft.getPositionLayoutCode());
        row.setContentSnapshot(content(row, owner));
        row.setContentFingerprint(fingerprint(VERSION, row.getContentSnapshot()));
        row.setLastOperationId(IdWorker.getId());
        require((fresh ? baseMapper.insert(row) : baseMapper.updateById(row)) == 1, "工程草稿必要写未恰好一行");
        row = readBack(owner, row);
        audit(owner, row, copying ? "COPY" : fresh ? "CREATE" : "UPDATE", fresh ? null : "DRAFT", draft.getReason(), before);
        return row;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional(rollbackFor = Exception.class)
    public MdmPhysicalTransformStandardEntity act(String rawJson) {
        var command = action(rawJson);
        String action = command.getAction();
        boolean review = Set.of("RELEASE", "REJECT", "RETIRE").contains(action);
        require(SecurityUtils.getAuthentication() != null, "工程动作必须实名认证");
        // 具体权限在控制器和独立服务代理入口共同校验，不从内部标记取得发布权。
        require(hasPermission(review ? "mdm_physicalTransformStandard_approve" : "mdm_physicalTransformStandard_edit"),
                "没有该工程动作的实际岗位权限");
        Identity owner = identity();
        expectScope(owner, command.getExpectedTenantId(), command.getExpectedFactoryCode());
        lock(owner);
        var row = standard(owner, id(command.getId()), true);
        expect(row, command.getExpectedFingerprint());
        expectOperation(row, command.getExpectedOperationId());
        String before = snapshot(row);
        String from = row.getStatus();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if ("COPY".equals(action)) return copy(owner, row, command.getReason());
        switch (action) {
            case "SUBMIT" -> {
                require("DRAFT".equals(from), "只能提交草稿工程版本");
                assertDependencies(row, owner);
                row.setStatus("IN_REVIEW").setSubmittedTime(now);
            }
            case "WITHDRAW" -> {
                require("IN_REVIEW".equals(from), "只能撤回待复核版本");
                require(contributor(owner, row.getId()), "只有本版本实际编制参与人可撤回");
                row.setStatus("DRAFT").setSubmittedTime(null);
            }
            case "REJECT" -> {
                require("IN_REVIEW".equals(from), "只能驳回待复核版本");
                require(!contributor(owner, row.getId()), "本版本编制参与人不能自行复核");
                row.setStatus("DRAFT").setSubmittedTime(null);
            }
            case "RELEASE" -> {
                require("IN_REVIEW".equals(from), "只有待复核工程版本可发布");
                require(!contributor(owner, row.getId()), "本版本任一编制/修改参与人不能自行发布");
                assertDependencies(row, owner);
                require(baseMapper.selectCount(scope(owner).eq(MdmPhysicalTransformStandardEntity::getMaterialCode, row.getMaterialCode())
                        .eq(MdmPhysicalTransformStandardEntity::getProcessRouteId, row.getProcessRouteId())
                        .eq(MdmPhysicalTransformStandardEntity::getProcessCode, row.getProcessCode())
                        .eq(MdmPhysicalTransformStandardEntity::getOperationType, row.getOperationType())
                        .eq(MdmPhysicalTransformStandardEntity::getStatus, "RELEASED")) == 0,
                        "相同物料/路线/工序/动作已有发布版，须明确退役原版本，不自动猜替换");
                row.setStatus("RELEASED").setReleasedById(owner.user().getId())
                        .setReleasedBy(owner.user().getUsername()).setReleasedTime(now);
            }
            case "RETIRE" -> {
                require("RELEASED".equals(from), "只能退役已发布工程版本");
                row.setStatus("RETIRED").setRetiredById(owner.user().getId())
                        .setRetiredBy(owner.user().getUsername()).setRetiredTime(now).setRetireReason(command.getReason());
            }
            default -> throw new CheckedException("不支持该工程动作");
        }
        row.setLastOperationId(IdWorker.getId());
        require(baseMapper.updateById(row) == 1, "工程状态必要写未恰好一行");
        row = readBack(owner, row);
        audit(owner, row, action, from, command.getReason(), before);
        return row;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public MdmPhysicalTransformStandardDTO.Frozen resolveReleased(MdmPhysicalTransformStandardDTO.ResolveQuery query) {
        Identity owner = identity();
        require(query != null && owner.factory().equals(query.getFactoryCode()), "工程解析不属于当前实际工厂");
        var row = standard(owner, id(query.getStandardId()), false);
        require("RELEASED".equals(row.getStatus()) && row.getReleasedTime() != null && row.getReleasedById() != null,
                "精确工程版本未正式发布或已经退役，不改查其他版本");
        expect(row, row.getContentFingerprint());
        require(Objects.equals(row.getMaterialCode(), query.getMaterialCode())
                && Objects.equals(row.getProcessRouteId(), id(query.getProcessRouteId()))
                && Objects.equals(row.getProcessRouteCode(), query.getProcessRouteCode())
                && Objects.equals(row.getProcessCode(), query.getProcessCode())
                && object(row.getPolicyJson(), 256 * 1024).equals(object(policy(query.getPolicyJson()), 256 * 1024)),
                "精确发布工程版本与实际物料/路线/工序/政策不一致");
        var result = new MdmPhysicalTransformStandardDTO.Frozen();
        result.setVersion(VERSION);
        result.setStandardId(row.getId().toString());
        result.setTenantId(owner.tenantId().toString());
        result.setFactoryCode(owner.factory());
        result.setContentSnapshot(row.getContentSnapshot());
        result.setContentFingerprint(row.getContentFingerprint());
        result.setReleasedById(row.getReleasedById().toString());
        result.setReleasedBy(row.getReleasedBy());
        result.setReleasedTime(row.getReleasedTime().toString());
        return result;
    }

    /** Copy以同依赖域锁分配下一永久版本；新版本重读当前真实依赖，原版不被重写。 */
    private MdmPhysicalTransformStandardEntity copy(Identity owner, MdmPhysicalTransformStandardEntity original, String reason) {
        int max = baseMapper.maxVersion(owner.tenantId(), owner.factory(), original.getStandardNo());
        require(max < Integer.MAX_VALUE, "工程版本号容量已满");
        var target = new MdmPhysicalTransformStandardEntity().setStandardNo(original.getStandardNo())
                .setStandardName(original.getStandardName()).setVersionNo(max + 1)
                .setMaterialCode(original.getMaterialCode()).setProcessRouteId(original.getProcessRouteId())
                .setProcessRouteCode(original.getProcessRouteCode()).setProcessCode(original.getProcessCode())
                .setOperationType(original.getOperationType()).setUnitConversionId(original.getUnitConversionId())
                .setPositionLayoutCode(original.getPositionLayoutCode()).setPolicyJson(original.getPolicyJson())
                .setStatus("DRAFT").setFactoryCode(owner.factory());
        target.setId(IdWorker.getId());
        target.setTenantId(owner.tenantId());
        target.setCreateBy(owner.user().getUsername());
        target.setCreateTime(LocalDateTime.now());
        target.setDelFlag("0");
        target.setContentSnapshot(content(target, owner));
        target.setContentFingerprint(fingerprint(VERSION, target.getContentSnapshot()));
        target.setLastOperationId(IdWorker.getId());
        require(baseMapper.insert(target) == 1, "工程版本复制必要写失败");
        target = readBack(owner, target);
        audit(owner, target, "COPY", null, reason, snapshot(original));
        return target;
    }

    /** 全部真实依赖正文在保存、提交及发布时一致；发布后不再查当前基础行。 */
    private String content(MdmPhysicalTransformStandardEntity row, Identity owner) {
        var materialRows = materials.selectList(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, owner.tenantId()).eq(MdmMaterialEntity::getFactoryCode, owner.factory())
                .eq(MdmMaterialEntity::getMaterialCode, row.getMaterialCode()).last("FOR UPDATE NOWAIT"));
        require(materialRows.size() == 1 && Boolean.TRUE.equals(materialRows.get(0).getStatus()), "工程物料缺失、不唯一或未启用");
        var route = routes.selectOne(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, owner.tenantId()).eq(MdmProcessRouteEntity::getFactoryCode, owner.factory())
                .eq(MdmProcessRouteEntity::getId, row.getProcessRouteId()).last("FOR UPDATE NOWAIT"));
        require(route != null && Boolean.TRUE.equals(route.getStatus()) && Objects.equals(route.getProductCode(), row.getMaterialCode()),
                "工程路线未启用或不属于当前物料");
        code(route.getRouteCode(), 100, "实际路线编码");
        require(route.getProcedureSnapshot() != null && !route.getProcedureSnapshot().isBlank(), "路线缺完整原工序快照");
        var work = workings.selectList(new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                .eq(MdmProcessRouteWorkingEntity::getTenantId, owner.tenantId()).eq(MdmProcessRouteWorkingEntity::getFactoryCode, owner.factory())
                .eq(MdmProcessRouteWorkingEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcessRouteWorkingEntity::getId).last("FOR UPDATE NOWAIT"));
        require(!work.isEmpty() && work.size() <= 2000 && work.stream().filter(w -> row.getProcessCode().equals(w.getProcessCode())).count() == 1,
                "工程路线工序全集为空、超界或指定工序不唯一，不能截断");
        var policy = object(row.getPolicyJson(), 256 * 1024);
        String input = policy.path("inputUnit").textValue();
        String output = policy.path("outputUnit").textValue();
        BigDecimal factor = quantity(policy.path("equivalenceFactor").textValue());
        MdmMatUnitConversionEntity conversion = null;
        String direction;
        if (input.equals(output)) {
            require(row.getUnitConversionId() == null && factor.compareTo(BigDecimal.ONE) == 0, "同单位不应引用跨单位行或改变数量");
            require(Stream.of(Objects.toString(materialRows.get(0).getBasicUnit(), ""),
                    Objects.toString(materialRows.get(0).getProductionMeasureUnit(), ""),
                    Objects.toString(materialRows.get(0).getInventoryMeasureUnit(), "")).anyMatch(input::equals), "同单位未属于真实物料计量单位");
            direction = "IDENTITY";
        } else {
            require(row.getUnitConversionId() != null, "跨单位须明确实际原换算行，工程发布前不能凭配置系数授权");
            conversion = conversions.selectOne(new LambdaQueryWrapper<MdmMatUnitConversionEntity>()
                    .eq(MdmMatUnitConversionEntity::getTenantId, owner.tenantId()).eq(MdmMatUnitConversionEntity::getFactoryCode, owner.factory())
                    .eq(MdmMatUnitConversionEntity::getId, row.getUnitConversionId()).last("FOR UPDATE NOWAIT"));
            require(conversion != null && Objects.equals(conversion.getMatCode(), row.getMaterialCode()), "换算行不属于实际物料");
            BigDecimal ratio = quantity(conversion.getRatio());
            if (input.equals(conversion.getNonStandardUnit()) && output.equals(conversion.getStandardUnit())) {
                require(factor.compareTo(ratio) == 0, "正向单位系数与实际原比例不一致");
                direction = "NON_STANDARD_TO_STANDARD";
            } else if (input.equals(conversion.getStandardUnit()) && output.equals(conversion.getNonStandardUnit())) {
                require(factor.multiply(ratio).compareTo(BigDecimal.ONE) == 0, "反向单位系数乘原比例必须精确等于1，不猜倒数或舍入");
                direction = "STANDARD_TO_NON_STANDARD";
            } else throw new CheckedException("原换算单位对与工程投入/产出单位不一致");
            require(Objects.equals(conversion.getStandardUnit(), materialRows.get(0).getBasicUnit()), "换算标准单位不等于真实物料基本单位");
        }
        boolean panel = "DEPANEL".equals(row.getOperationType());
        require(panel ? code(row.getPositionLayoutCode(), 100, "工程布局编码") != null : "".equals(row.getPositionLayoutCode()),
                "非拆板不能附带板位布局");
        row.setProcessRouteCode(route.getRouteCode());
        Map<String, Object> dependency = new TreeMap<>();
        dependency.put("material", materialRows.get(0));
        dependency.put("route", route);
        dependency.put("workings", work);
        dependency.put("unitConversion", conversion);
        dependency.put("unitDirection", direction);
        dependency.put("unitEquation", "1 " + input + " = " + amount(factor) + " " + output);
        String dependencies = snapshot(dependency);
        Map<String, String> body = new TreeMap<>();
        body.put("version", VERSION);
        body.put("tenantId", owner.tenantId().toString());
        body.put("factoryCode", owner.factory());
        body.put("standardId", row.getId().toString());
        body.put("standardNo", row.getStandardNo());
        body.put("standardName", row.getStandardName());
        body.put("versionNo", row.getVersionNo().toString());
        body.put("materialCode", row.getMaterialCode());
        body.put("processRouteId", row.getProcessRouteId().toString());
        body.put("processRouteCode", row.getProcessRouteCode());
        body.put("processCode", row.getProcessCode());
        body.put("positionLayoutCode", row.getPositionLayoutCode());
        body.put("policyJson", row.getPolicyJson());
        body.put("dependencySnapshot", dependencies);
        body.put("dependencyFingerprint", fingerprint("MDM_PHYSICAL_DEPENDENCIES_V1", dependencies));
        String result = snapshot(body);
        object(result, 4 * 1024 * 1024);
        return result;
    }

    private void assertDependencies(MdmPhysicalTransformStandardEntity row, Identity owner) {
        require(row.getContentSnapshot().equals(content(row, owner)), "真实路线/物料/单位依据已变化，须驳回或撤回后重新编制，不审批漂移正文");
    }

    private boolean contributor(Identity owner, Long id) {
        return logs.selectCount(logScope(owner, id).in(MdmPhysicalTransformStandardLogEntity::getAction, "CREATE", "UPDATE", "COPY")
                .and(q -> q.eq(MdmPhysicalTransformStandardLogEntity::getActorId, owner.user().getId())
                        .or().eq(MdmPhysicalTransformStandardLogEntity::getActorCode, owner.user().getUsername()))) > 0;
    }

    private void audit(Identity owner, MdmPhysicalTransformStandardEntity row, String action, String from, String reason, String before) {
        // 完整实体快照会再次转义工程正文；容量不能误沿用内层正文的4Mi限制。
        String after = snapshot(row);
        object(after, 16 * 1024 * 1024);
        if (before != null) object(before, 16 * 1024 * 1024);
        var log = new MdmPhysicalTransformStandardLogEntity().setStandardId(row.getId()).setFactoryCode(owner.factory())
                .setAction(action).setFromStatus(from).setToStatus(row.getStatus()).setReason(reason)
                .setActorId(owner.user().getId()).setActorCode(owner.user().getUsername())
                .setOperatedTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).setBeforeSnapshot(before).setAfterSnapshot(after);
        log.setId(row.getLastOperationId());
        log.setTenantId(owner.tenantId());
        log.setCreateBy(owner.user().getUsername());
        log.setDelFlag("0");
        require(logs.insert(log) == 1, "工程完整履历必要写失败");
        var stored = logs.selectById(log.getId());
        require(stored != null && storedFacts(stored).equals(storedFacts(log)), "工程履历必要事实回读不一致");
    }

    private MdmPhysicalTransformStandardEntity readBack(Identity owner, MdmPhysicalTransformStandardEntity expected) {
        var row = standard(owner, expected.getId(), false);
        require(storedFacts(row).equals(storedFacts(expected)), "工程必要写领域事实回读不一致，禁止声称保存/发布成功");
        return row;
    }

    /** 框架自动填充审计时间存在JDBC纳秒到微秒转换；全部身份/领域事实仍逐项精确回读。 */
    private com.fasterxml.jackson.databind.JsonNode storedFacts(Object row) {
        var root = entityFacts(row);
        root.remove(List.of("createTime", "updateTime"));
        return root;
    }

    private void expect(MdmPhysicalTransformStandardEntity row, String expected) {
        require(expected != null && expected.matches("[0-9a-f]{64}") && expected.equals(row.getContentFingerprint())
                && expected.equals(fingerprint(VERSION, row.getContentSnapshot())), "工程正文已变化或指纹损坏，请重读原版本，不继续旧页面动作");
    }

    /** 正文指纹不包含状态，所以还需精确最近动作号，不能跨审批轮次重放旧命令。 */
    private void expectOperation(MdmPhysicalTransformStandardEntity row, String expected) {
        require(Objects.equals(row.getLastOperationId(), id(expected)), "工程生命周期已变化，请重读原版本及履历，不执行迟到动作");
    }

    /** HTTP请求拦截器可能晚于点击切厂；正文期望值仅拒绝错厂写，不授予任何权限。 */
    private void expectScope(Identity owner, String tenant, String factory) {
        require(owner.tenantId().equals(id(tenant)) && owner.factory().equals(factory),
                "页面期望组织与当前认证上下文不同，未执行工程写入；请重开当前工厂版本");
    }

    private MdmPhysicalTransformStandardEntity standard(Identity owner, Long id, boolean locked) {
        require(id != null && id > 0, "工程标准ID不能为空");
        var row = locked ? baseMapper.locked(owner.tenantId(), owner.factory(), id)
                : baseMapper.selectOne(scope(owner).eq(MdmPhysicalTransformStandardEntity::getId, id));
        require(row != null && "0".equals(row.getDelFlag()), "当前实际租户工厂没有该工程标准");
        return row;
    }

    private void lock(Identity owner) {
        require(Boolean.TRUE.equals(baseMapper.dependencyLock(snapshot(List.of("MDM_PHYSICAL_DEPENDENCY_LOCK_V1",
                owner.tenantId().toString(), owner.factory())))), "实际工程依赖正在修改，请保留原操作和指纹后重核");
    }

    private Identity identity() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var user = SecurityUtils.getUser();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && user != null && user.getId() != null && user.getId() > 0,
                "工程标准必须实名选择实际租户工厂，不允许跳过隔离");
        code(factory, 100, "实际工厂");
        code(user.getUsername(), 100, "实名账号");
        return new Identity(tenant, factory, user);
    }

    private boolean hasPermission(String permission) {
        var auth = SecurityUtils.getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(granted -> permission.equals(granted.getAuthority()));
    }

    private LambdaQueryWrapper<MdmPhysicalTransformStandardEntity> scope(Identity owner) {
        return new LambdaQueryWrapper<MdmPhysicalTransformStandardEntity>()
                .eq(MdmPhysicalTransformStandardEntity::getTenantId, owner.tenantId())
                .eq(MdmPhysicalTransformStandardEntity::getFactoryCode, owner.factory());
    }

    private LambdaQueryWrapper<MdmPhysicalTransformStandardLogEntity> logScope(Identity owner, Long id) {
        return new LambdaQueryWrapper<MdmPhysicalTransformStandardLogEntity>()
                .eq(MdmPhysicalTransformStandardLogEntity::getTenantId, owner.tenantId())
                .eq(MdmPhysicalTransformStandardLogEntity::getFactoryCode, owner.factory())
                .eq(MdmPhysicalTransformStandardLogEntity::getStandardId, id);
    }

    private record Identity(Long tenantId, String factory, MomUser user) {
    }
}
