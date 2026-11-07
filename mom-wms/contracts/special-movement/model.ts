import {
  check,
  clone,
  decimal,
  decimalText,
  permission,
  type Action,
  type CloseLine,
  type Context,
  type ExecutionLine,
  type Flow,
  type Input,
  type Plan,
  type Receipt,
  type View,
} from './contract'
export const blankPlan = (): Plan => ({
  kind: 'SPARE',
  direction: 'OUT',
  partyCode: '',
  reference: '',
  reason: '',
  lines: [],
  outputs: [],
})
export const blankExecution = (
  lineId: string,
  sourceFlowId: string | null = null,
): ExecutionLine => ({
  lineId,
  sourceFlowId,
  stockId: null,
  quantity: '',
  siteCode: '',
  containerCode: '',
  batch: '',
  barcode: '',
  producedAt: null,
  expiresAt: null,
  originalConditionConfirmed: null,
})
export function originalFlows(value: View): Flow[] {
  return value.operations.flatMap((row) => row.flows).filter((row) => row.sourceFlowId === null)
}
export function returnable(value: View, source: Flow): string {
  const back = value.operations
    .flatMap((row) => row.flows)
    .filter((row) => row.sourceFlowId === source.id)
    .reduce((sum, row) => sum + decimal(row.quantity), BigInt('0'))
  return decimalText(decimal(source.quantity) - back)
}

/** 两端统一的办理履历投影；时间缺失保持空值，退回关系只来自实际原流水。 */
export interface HandlingEvent {
  /** 原办理凭据，保持后端记录顺序，不按终端时间重新排序。 */
  operation: Receipt
  /** 永久操作表登记时间；旧服务未提供时为空，不能用首次入库或今日时间替代。 */
  registeredAt: string | null
  /** 本次退回关联的原实际收发单号，按真实流水去重。 */
  sourceBills: string[]
}

/** 读模型完整核验后连接办理时间与原件，不产生新业务状态或重算库存。 */
export function handlingHistory(value: View): HandlingEvent[] {
  const audit = new Map((value.audit || []).map((row) => [row.requestId, row.registeredAt]))
  const original = new Map<string, string>()
  for (const operation of value.operations) {
    for (const flow of operation.flows) {
      if (flow.sourceFlowId === null && operation.billNo) original.set(flow.id, operation.billNo)
    }
  }
  return value.operations.map((operation) => {
    const sourceBills = new Set<string>()
    for (const flow of operation.flows) {
      if (flow.sourceFlowId === null) continue
      const source = original.get(flow.sourceFlowId)
      check(source, '退回凭据缺少原实际收发单，请重新读取')
      sourceBills.add(source)
    }
    return {
      operation,
      registeredAt: audit.get(operation.requestId) || null,
      sourceBills: [...sourceBills],
    }
  })
}

/** 实际收发数量沿冻结计划的基本单位展示，不按当前物料配置重算历史。 */
export function movementUnit(value: View, lineId: string): string {
  const line = [...value.order.plan.lines, ...value.order.plan.outputs].find(
    (row) => row.lineId === lineId,
  )
  check(line && typeof line.unit === 'string' && !!line.unit, '原物料基本单位未完整读取')
  return line.unit
}

export function closureRows(value: View): CloseLine[] {
  if (value.order.plan.kind !== 'OUTSOURCE') return []
  return [
    ...originalFlows(value)
      .filter((row) => value.order.plan.lines.some((line) => line.lineId === row.lineId))
      .map((row) => ({
        sourceFlowId: row.id,
        outputLineId: null,
        quantity: returnable(value, row),
      })),
    ...value.balances
      .filter((row) => row.output)
      .map((row) => ({ sourceFlowId: null, outputLineId: row.lineId, quantity: row.remaining })),
  ].filter((row) => decimal(row.quantity) > BigInt('0'))
}
export function allowedActions(value: View, context: Context, mobile = false): Action[] {
  if (!context.warehouseEnabled || (mobile && !context.mobileEnabled)) return []
  const { order } = value
  let result: Action[] = []
  const physical = value.operations.some((row) => row.flows.length > 0)
  if (order.state === 'PENDING')
    result = [
      ...(order.createdBy !== context.operatorCode ? (['APPROVE', 'REJECT'] as Action[]) : []),
      'CANCEL',
    ]
  if (order.state === 'APPROVED')
    result = [
      ...(!physical ? (['CANCEL'] as Action[]) : []),
      'EXECUTE',
      ...(order.plan.kind === 'OUTSOURCE' ? (['RECEIVE_OUTPUT'] as Action[]) : []),
      'RETURN',
      ...(order.createdBy !== context.operatorCode &&
      (order.plan.kind !== 'OUTSOURCE' ||
        !value.operations.some(
          (row) => row.flows.length > 0 && row.operatorCode === context.operatorCode,
        ))
        ? (['CLOSE'] as Action[])
        : []),
    ]
  if (order.state === 'CLOSED' && order.plan.kind !== 'OUTSOURCE') result = ['RETURN']
  const rule = context.policy.rules.find((row) => row.kind === order.plan.kind)
  return result.filter(
    (action) =>
      context.permissions.includes(permission[action]) &&
      (action !== 'CANCEL' ||
        order.createdBy === context.operatorCode ||
        context.permissions.includes('review')) &&
      (!['EXECUTE', 'RECEIVE_OUTPUT', 'APPROVE'].includes(action) || rule?.enabled) &&
      (action !== 'RETURN' ||
        (rule?.allowReturns &&
          originalFlows(value).some((row) => decimal(returnable(value, row)) > BigInt('0')))),
  )
}
export function createInput(plan: Plan, context: Context): Input {
  const result = clone(plan)
  result.partyCode = result.partyCode.trim()
  result.reference = result.reference.trim()
  result.reason = result.reason.trim()
  if (result.kind === 'OUTSOURCE') result.direction = 'OUT'
  const rule = context.policy.rules.find((row) => row.kind === result.kind)
  check(rule?.enabled, '请先启用本仓该类特殊收发规则')
  check(result.reason, '请填写用途说明')
  check(!rule.requireParty || result.partyCode, '请填写领用方或往来方')
  check(!rule.requireReference || result.reference, '请填写来源依据')
  return {
    action: 'CREATE',
    orderId: null,
    expectedVersion: null,
    plan: result,
    lines: [],
    reason: '',
    evidenceReference: '',
    closeLines: [],
  }
}

/**
 * 按原申请基本单位逐计划行或原流水汇总，提前拦截超量及收发方向混用。
 * 此处只核当前已读事实；并发库存、质量和最新规则仍由正式记账服务在锁内复核。
 */
function checkPhysicalInput(value: View, action: Action, lines: ExecutionLine[]) {
  const requested = new Map<string, bigint>()
  const sources = originalFlows(value)
  const directions = new Set<string>()
  for (const item of lines) {
    const row = value.balances.find((balance) => balance.lineId === item.lineId)
    check(row, '本次物料不属于原计划，请重新选择')
    const quantity = decimal(item.quantity, false)
    let incoming: boolean
    if (action === 'RETURN') {
      const source = sources.find(
        (flow) => flow.id === item.sourceFlowId && flow.lineId === item.lineId,
      )
      check(source, '退回须选择该物料的原实际收发记录')
      directions.add(source.direction)
      check(directions.size === 1, '收回入库与退回对方请分别办理')
      const total = (requested.get(source.id) ?? BigInt('0')) + quantity
      requested.set(source.id, total)
      check(total <= decimal(returnable(value, source)), '本次退回合计超过原记录剩余可退数量')
      incoming = source.direction === 'OUT'
      if (incoming && !value.order.rule.quarantineReturns) {
        check(item.originalConditionConfirmed === true, '按原状态收回前，请确认原实物状态')
      }
    } else {
      check(
        item.sourceFlowId === null &&
          (action === 'EXECUTE'
            ? !row.output
            : value.order.plan.kind === 'OUTSOURCE' && row.output),
        '本次操作与收发物料或加工交付计划不匹配',
      )
      const total = (requested.get(row.lineId) ?? BigInt('0')) + quantity
      requested.set(row.lineId, total)
      check(
        total <= decimal(row.remaining),
        `${row.materialCode} 本次合计不能超过剩余 ${row.remaining} ${row.unit}`,
      )
      incoming = row.output || value.order.plan.direction === 'IN'
    }
    if (incoming) {
      check(item.stockId === null, '收货请填写实物信息，不指定现存库存')
      check(typeof item.siteCode === 'string' && item.siteCode.trim(), '请选择实际入库库位')
    } else {
      check(item.stockId, '请从当前可用库存选择发出实物')
    }
  }
}

/** 外协耗用/处置与交付短缺逐项核对，不按交付数量推算原料消耗。 */
function checkCloseInput(value: View, declarations: CloseLine[]) {
  const expected = closureRows(value)
  const declared = new Map<string, bigint>()
  for (const row of declarations) {
    check(
      (row.sourceFlowId !== null) !== (row.outputLineId !== null),
      '结案核对项须对应原发料记录或加工交付计划',
    )
    const key = row.sourceFlowId !== null ? `F:${row.sourceFlowId}` : `O:${row.outputLineId}`
    check(!declared.has(key), '结案核对项重复，请刷新原申请')
    declared.set(key, decimal(row.quantity, false))
  }
  check(declared.size === expected.length, '请完整核对原料处置和交付短缺，不能遗漏或增加核对项')
  for (const row of expected) {
    const key = row.sourceFlowId !== null ? `F:${row.sourceFlowId}` : `O:${row.outputLineId}`
    check(
      declared.get(key) === decimal(row.quantity),
      row.sourceFlowId !== null
        ? '原发料未退数量须与实际耗用或处置数量一致'
        : '加工实收与明确短交数量须等于交付计划',
    )
  }
}

export function actionInput(
  value: View,
  action: Action,
  context: Context,
  lines: ExecutionLine[],
  reason: string,
  evidence: string,
  closeLines: CloseLine[],
  mobile = false,
): Input {
  check(allowedActions(value, context, mobile).includes(action), '当前申请或账号不能办理此动作')
  check(reason.trim(), '请填写办理说明')
  if (['RETURN', 'CLOSE'].includes(action)) check(evidence.trim(), '请填写交接或对账凭证')
  const physical = ['EXECUTE', 'RECEIVE_OUTPUT', 'RETURN'].includes(action)
  check(!physical || lines.length > 0, '请添加本次实物作业行')
  if (physical) {
    checkPhysicalInput(value, action, lines)
  }
  if (action === 'CLOSE') {
    checkCloseInput(value, closeLines)
  }
  return {
    action,
    orderId: value.order.id,
    expectedVersion: value.order.version,
    plan: null,
    lines: physical ? clone(lines) : [],
    reason: reason.trim(),
    evidenceReference: evidence.trim(),
    closeLines: action === 'CLOSE' ? clone(closeLines) : [],
  }
}

/** 使用实际单据及其明细序号辨认原件，不向操作人暴露数据库标识。 */
export function sourceDescription(value: View, sourceFlowId: string): string {
  const operation = value.operations.find((row) =>
    row.flows.some((flow) => flow.id === sourceFlowId),
  )
  const source = operation?.flows.find((flow) => flow.id === sourceFlowId)
  check(operation && source, '未找到原实际收发明细')
  const line = [...value.order.plan.lines, ...value.order.plan.outputs].find(
    (row) => row.lineId === source.lineId,
  )
  const index = operation.flows.findIndex((flow) => flow.id === sourceFlowId) + 1
  return `${line?.materialCode || '原物料'} · ${source.identity.matBatch || '无批次'} / ${source.identity.matBarcode || '无实物码'} · ${operation.billNo || '原收发单'} 第${index}项`
}
