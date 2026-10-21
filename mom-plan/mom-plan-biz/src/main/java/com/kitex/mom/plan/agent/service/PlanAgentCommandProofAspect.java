package com.kitex.mom.plan.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 固定受控业务接口的永久意图边界。排在方法授权之后，不改变原审批和业务校验流程。
 * 不拦截普通CRUD、不重放未知执行；回执事务与原业务服务提交独立。
 */
@Aspect
@Component
@Order(1000)
public class PlanAgentCommandProofAspect {
    private final PlanAgentCommandProofService proofs;
    private final PlanAgentCommandCodec codec;

    public PlanAgentCommandProofAspect(PlanAgentCommandProofService proofs, PlanAgentCommandCodec codec) {
        this.proofs = proofs;
        this.codec = codec;
    }

    @Around("execution(* com.kitex.mom.plan.controller.PlanMatMrpController.agentSubstituteDecision(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlanMatMrpController.agentTransferDecision(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlanMatMrpController.agentPurchaseOrder(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnShortageLocalReplanController.agentPublish(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnSchedulingAgentController.run(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnSchedulingAgentController.select(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnSchedulingAgentController.publish(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnResourceLocalReplanController.select(..)) || "
            + "execution(* com.kitex.mom.plan.controller.PlnResourceLocalReplanController.publish(..))")
    public Object around(ProceedingJoinPoint invocation) throws Throwable {
        String method = invocation.getSignature().getName();
        String owner = invocation.getSignature().getDeclaringType().getSimpleName();
        String code;
        String variant;
        switch (owner) {
            case "PlanMatMrpController" -> {
                variant = "MRP";
                code = switch (method) {
                    case "agentSubstituteDecision" -> "PLAN_MRP_SUBSTITUTE_DECISION";
                    case "agentTransferDecision" -> "PLAN_MRP_TRANSFER_DECISION";
                    default -> "PLAN_MRP_PURCHASE_ORDER_SUBMIT";
                };
            }
            case "PlnShortageLocalReplanController" -> {
                variant = "SHORTAGE_REPLAN";
                code = "PLAN_SHORTAGE_LOCAL_REPLAN_PUBLISH";
            }
            default -> {
                variant = "PlnResourceLocalReplanController".equals(owner) ? "RESOURCE_REPLAN" : "APS";
                code = switch (method) {
                    case "run" -> "PLAN_APS_SCENARIO_RUN";
                    case "select" -> "PLAN_APS_SCENARIO_SELECT";
                    default -> "PLAN_APS_SCENARIO_PUBLISH";
                };
            }
        }
        PlanAgentCommandCodec.Frozen frozen = codec.freeze(code, variant, invocation.getArgs()[0]);
        PlanAgentCommandProofService.Begin intent = proofs.begin(frozen);
        if (!intent.mayExecute()) {
            return R.ok(intent.receipt());
        }
        try {
            Object result = invocation.proceed();
            if (!(result instanceof R<?> response) || !response.isOk() || response.getData() == null
                    || Boolean.FALSE.equals(response.getData())) {
                throw new CheckedException("原业务没有返回可确认成功回执，必须查询原请求核查");
            }
            proofs.confirm(intent.proofId(), response.getData());
            return result;
        } catch (Throwable failure) {
            try {
                proofs.uncertain(intent.proofId(), failure.getClass().getSimpleName() + ": 原业务回执未确认，须查询原永久请求");
            } catch (RuntimeException evidenceUnavailable) {
                failure.addSuppressed(evidenceUnavailable);
            }
            throw failure;
        }
    }
}
