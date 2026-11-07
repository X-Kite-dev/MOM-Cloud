/** WMS特殊收发合同v1；数量、业务标识不经过浮点数转换。 */
export type Kind = 'SPARE' | 'FREE' | 'OUTSOURCE'
export type Direction = 'IN' | 'OUT'
export type Action =
  | 'CREATE'
  | 'APPROVE'
  | 'REJECT'
  | 'CANCEL'
  | 'EXECUTE'
  | 'RECEIVE_OUTPUT'
  | 'RETURN'
  | 'CLOSE'
export type State = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'CLOSED'
export interface Rule {
  kind: Kind
  enabled: boolean
  requireReference: boolean
  requireParty: boolean
  allowReturns: boolean
  quarantineReturns: boolean
}
export interface Policy {
  contractVersion: 1
  tenantId: string
  factoryCode: string
  warehouseCode: string
  version: string
  rules: Rule[]
}
export interface Context {
  contractVersion: 1
  tenantId: string
  factoryCode: string
  warehouseCode: string
  operatorCode: string
  mobileEnabled: boolean
  warehouseEnabled: boolean
  warehouseName: string
  permissions: string[]
  policy: Policy
}
export interface Line {
  lineId: string | null
  materialCode: string
  materialName: string
  unit: string
  quantity: string
}
export interface Plan {
  kind: Kind
  direction: Direction
  partyCode: string
  reference: string
  reason: string
  lines: Line[]
  outputs: Line[]
}
export interface ExecutionLine {
  lineId: string
  stockId: string | null
  sourceFlowId: string | null
  quantity: string
  siteCode: string
  containerCode: string
  batch: string
  barcode: string
  producedAt: string | null
  expiresAt: string | null
  originalConditionConfirmed: boolean | null
}
export interface CloseLine {
  sourceFlowId: string | null
  outputLineId: string | null
  quantity: string
}
export interface Input {
  action: Action
  orderId: string | null
  expectedVersion: string | null
  plan: Plan | null
  lines: ExecutionLine[]
  reason: string
  evidenceReference: string
  closeLines: CloseLine[]
}
export interface Command extends Input {
  requestId: string
  warehouseCode: string
}
export interface Order {
  id: string
  tenantId: string
  factoryCode: string
  warehouseCode: string
  plan: Plan
  rule: Rule
  policyVersion: string
  state: State
  version: string
  createdBy: string
  reviewedBy: string | null
  createdAt: string
}
export interface Flow {
  id: string
  lineId: string
  direction: Direction
  quantity: string
  sourceFlowId: string | null
  stockId: string
  billDetailId: string
  identity: Record<string, string>
}
export interface Receipt {
  contractVersion: 1
  tenantId: string
  factoryCode: string
  warehouseCode: string
  operatorCode: string
  requestId: string
  status: 'REGISTERED' | 'CANCELLED'
  action: Action
  original: Command
  orderId: string | null
  orderVersion: string | null
  billId: string | null
  billNo: string | null
  flows: Flow[]
  endReason: string | null
}
export interface Balance {
  lineId: string
  materialCode: string
  materialName: string
  unit: string
  planned: string
  executed: string
  returned: string
  net: string
  remaining: string
  output: boolean
}
/** 永久操作表的登记时间投影，不属于写命令或已保存的原回执JSON。 */
export interface OperationAudit {
  /** 原办理请求号，与本申请一条办理凭据对应。 */
  requestId: string
  /** 数据库原created_at无时区文本，保留微秒；不表示实物交接或事务提交时刻。 */
  registeredAt: string
}
export interface View {
  order: Order
  balances: Balance[]
  operations: Receipt[]
  /** 旧服务可缺省，页面须明确缺时间；字段存在时必须完整覆盖本申请办理凭据。 */
  audit?: OperationAudit[]
}
export interface Stock {
  stockId: string
  materialCode: string
  materialName: string
  unit: string
  quantity: string
  available: string
  siteCode: string
  containerCode: string
  batch: string
  barcode: string
}
export interface Material {
  code: string
  name: string
  unit: string
}
export interface Location {
  code: string
  name: string
}
export const kinds: Record<Kind, string> = {
  SPARE: '备件收发',
  FREE: '免费收发',
  OUTSOURCE: '外协加工',
}
export const states: Record<State, string> = {
  PENDING: '待审核',
  APPROVED: '待办理',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  CLOSED: '已结案',
}
export const actions: Record<Action, string> = {
  CREATE: '提交申请',
  APPROVE: '审核通过',
  REJECT: '驳回申请',
  CANCEL: '取消申请',
  EXECUTE: '实物收发',
  RECEIVE_OUTPUT: '加工收货',
  RETURN: '原单退回',
  CLOSE: '对账结案',
}
export const permission: Record<Action, string> = {
  CREATE: 'add',
  APPROVE: 'review',
  REJECT: 'review',
  CANCEL: 'add',
  EXECUTE: 'execute',
  RECEIVE_OUTPUT: 'execute',
  RETURN: 'return',
  CLOSE: 'close',
}
export function check(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message)
}
export function id(value: unknown): string {
  check(
    typeof value === 'string' &&
      /^[1-9]\d{0,18}$/.test(value) &&
      BigInt(value) <= BigInt('9223372036854775807'),
    '业务标识未完整读取，请重新选择单据',
  )
  return value
}
export function decimal(value: unknown, zero = true): bigint {
  check(
    typeof value === 'string' && /^(?:0|[1-9]\d{0,14})(?:\.\d{1,6})?$/.test(value),
    '数量须为普通十进制数，最多六位小数',
  )
  const [whole, fraction = ''] = value.split('.')
  const result = BigInt(whole) * BigInt('1000000') + BigInt(fraction.padEnd(6, '0'))
  check(zero || result > BigInt('0'), '数量须大于零')
  return result
}
export function decimalText(value: bigint): string {
  check(value >= BigInt('0'), '数量关系异常')
  const fraction = (value % BigInt('1000000')).toString().padStart(6, '0').replace(/0+$/, '')
  return `${value / BigInt('1000000')}${fraction ? `.${fraction}` : ''}`
}
export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value))
}
export function sorted(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(sorted).join(',')}]`
  if (value && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${sorted((value as Record<string, unknown>)[key])}`)
      .join(',')}}`
  return JSON.stringify(value)
}
/** 保留正式拒绝原因，不序列化响应/命令；错误文字本身不能证明原写请求是否已登记。 */
export function errorText(error: unknown, fallback = '办理未完成，请核对原请求'): string {
  const object = (value: unknown): Record<string, unknown> =>
    value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  const value = object(error)
  const response = object(value.response)
  const body = object(response.data ?? value.data)
  const message = [body.msg, body.message, value.msg, value.message].find(
    (item) => typeof item === 'string' && item.trim(),
  )
  if (typeof message === 'string') return message
  return typeof value.errMsg === 'string' ? '网络连接未完成，请保留原请求核对' : fallback
}
export function body<T>(response: unknown): T {
  const row = response as { code?: number; data?: T; msg?: string }
  check(row?.code === 0, row?.msg || '未获得有效业务响应')
  return row.data as T
}
export function validatePolicy(
  value: Policy,
  context: Pick<Context, 'tenantId' | 'factoryCode' | 'warehouseCode'>,
): Policy {
  check(
    value?.contractVersion === 1 &&
      value.tenantId === context.tenantId &&
      value.factoryCode === context.factoryCode &&
      value.warehouseCode === context.warehouseCode,
    '规则不属于当前仓库',
  )
  check(
    typeof value.version === 'string' &&
      /^(?:0|[1-9]\d*)$/.test(value.version) &&
      Array.isArray(value.rules) &&
      value.rules.length === 3,
    '规则版本或内容不完整',
  )
  const seen = new Set<string>()
  for (const row of value.rules) {
    check(
      row && Object.prototype.hasOwnProperty.call(kinds, row.kind) && !seen.has(row.kind),
      '规则业务类型重复或无效',
    )
    seen.add(row.kind)
    for (const name of [
      'enabled',
      'requireReference',
      'requireParty',
      'allowReturns',
      'quarantineReturns',
    ] as const)
      check(typeof row[name] === 'boolean', '规则开关不完整')
  }
  return clone(value)
}
export function validateContext(
  value: Context,
  identity: { username: string; factoryCode: string; warehouseCode: string },
): Context {
  check(
    value?.contractVersion === 1 &&
      value.operatorCode === identity.username &&
      value.factoryCode === identity.factoryCode &&
      value.warehouseCode === identity.warehouseCode &&
      typeof value.mobileEnabled === 'boolean' &&
      typeof value.warehouseEnabled === 'boolean' &&
      typeof value.warehouseName === 'string' &&
      Array.isArray(value.permissions) &&
      value.permissions.includes('view'),
    '当前身份或仓库授权未核实',
  )
  id(value.tenantId)
  validatePolicy(value.policy, value)
  return clone(value)
}
export function validateCommand(value: Command): Command {
  check(
    value &&
      Object.prototype.hasOwnProperty.call(actions, value.action) &&
      /^[a-zA-Z0-9][a-zA-Z0-9._:-]{7,99}$/.test(value.requestId) &&
      typeof value.warehouseCode === 'string' &&
      !!value.warehouseCode &&
      !/[*?]/.test(value.warehouseCode),
    '原操作内容无效',
  )
  check(
    Array.isArray(value.lines) &&
      value.lines.length <= 200 &&
      Array.isArray(value.closeLines) &&
      value.closeLines.length <= 10200,
    '原操作明细不完整',
  )
  if (value.action === 'CREATE') {
    check(
      value.orderId === null &&
        value.expectedVersion === null &&
        value.plan &&
        !value.lines.length &&
        !value.closeLines.length,
      '申请内容不完整',
    )
    const plan = value.plan
    check(
      Object.prototype.hasOwnProperty.call(kinds, plan.kind) &&
        ['IN', 'OUT'].includes(plan.direction) &&
        Array.isArray(plan.lines) &&
        plan.lines.length > 0 &&
        Array.isArray(plan.outputs),
      '申请计划不完整',
    )
    check(
      plan.kind === 'OUTSOURCE'
        ? plan.direction === 'OUT' && plan.outputs.length > 0
        : plan.outputs.length === 0,
      '请分别填写外协发料与加工交付计划',
    )
    for (const group of [plan.lines, plan.outputs]) {
      const seen = new Set<string>()
      check(group.length <= 200, '单类物料最多200行')
      for (const line of group) {
        check(
          line?.materialCode && line.unit && !seen.has(line.materialCode),
          '物料、单位未选择或物料重复',
        )
        seen.add(line.materialCode)
        decimal(line.quantity, false)
      }
    }
  } else {
    id(value.orderId)
    id(value.expectedVersion)
    check(value.plan === null, '原申请计划不能在办理时改写')
  }
  for (const line of value.lines) {
    id(line.lineId)
    if (line.stockId !== null) id(line.stockId)
    if (line.sourceFlowId !== null) id(line.sourceFlowId)
    decimal(line.quantity, false)
  }
  for (const line of value.closeLines) {
    check(
      (line.sourceFlowId !== null) !== (line.outputLineId !== null),
      '结案核对项须对应原发料或交付行',
    )
    id(line.sourceFlowId ?? line.outputLineId)
    decimal(line.quantity, false)
  }
  return clone(value)
}
function flow(value: Flow) {
  id(value.id)
  id(value.lineId)
  id(value.stockId)
  id(value.billDetailId)
  if (value.sourceFlowId !== null) id(value.sourceFlowId)
  decimal(value.quantity, false)
  check(
    ['IN', 'OUT'].includes(value.direction) &&
      value.identity &&
      Object.keys(value.identity).length > 0,
    '库存流水身份不完整',
  )
}
export function validateReceipt(
  response: unknown,
  context: Context,
  command: Command,
): Receipt | null {
  const value = body<Receipt | null>(response)
  if (value === null) return null
  check(
    value?.contractVersion === 1 &&
      value.tenantId === context.tenantId &&
      value.factoryCode === context.factoryCode &&
      value.warehouseCode === context.warehouseCode &&
      value.operatorCode === context.operatorCode &&
      value.requestId === command.requestId &&
      value.action === command.action &&
      sorted(value.original) === sorted(command) &&
      ['REGISTERED', 'CANCELLED'].includes(value.status) &&
      Array.isArray(value.flows),
    '原操作回执的身份或内容不匹配',
  )
  if (value.status === 'REGISTERED') {
    id(value.orderId)
    id(value.orderVersion)
    if (command.action !== 'CREATE')
      check(
        value.orderId === command.orderId &&
          BigInt(value.orderVersion!) === BigInt(command.expectedVersion!) + BigInt('1'),
        '回执未对应本次申请版本',
      )
    const physical = ['EXECUTE', 'RECEIVE_OUTPUT', 'RETURN'].includes(command.action)
    check(
      physical
        ? value.billId && value.billNo && value.flows.length > 0
        : value.billId === null && value.billNo === null && value.flows.length === 0,
      '回执缺少相应的实际单据凭据',
    )
    if (physical) {
      id(value.billId)
      const actual = new Map<string, bigint>()
      const expected = new Map<string, bigint>()
      const seen = new Set<string>()
      for (const item of command.lines) {
        const key = `${item.lineId}:${item.sourceFlowId || ''}:${item.stockId || ''}`
        expected.set(key, (expected.get(key) || BigInt('0')) + decimal(item.quantity, false))
      }
      const outbound = command.lines.every((row) => row.stockId !== null)
      check(outbound || command.lines.every((row) => row.stockId === null), '入库和出库请分别办理')
      for (const item of value.flows) {
        flow(item)
        check(item.direction === (outbound ? 'OUT' : 'IN'), '实际流水收发方向与原请求不一致')
        check(!seen.has(item.id), '实际流水重复')
        seen.add(item.id)
        const key = `${item.lineId}:${item.sourceFlowId || ''}:${outbound ? item.stockId : ''}`
        actual.set(key, (actual.get(key) || BigInt('0')) + decimal(item.quantity, false))
      }
      check(
        actual.size === expected.size &&
          [...expected].every(([key, num]) => actual.get(key) === num),
        '实际流水数量与原请求不一致',
      )
    }
  } else
    check(
      value.billId === null &&
        value.billNo === null &&
        value.orderVersion === null &&
        value.flows.length === 0 &&
        typeof value.endReason === 'string' &&
        !!value.endReason,
      '安全结束凭据不完整',
    )
  return clone(value)
}
export function validateView(value: View, context: Context): View {
  const order = value?.order
  check(
    order &&
      order.tenantId === context.tenantId &&
      order.factoryCode === context.factoryCode &&
      order.warehouseCode === context.warehouseCode &&
      Object.prototype.hasOwnProperty.call(states, order.state),
    '申请不属于当前仓库或状态无效',
  )
  id(order.id)
  id(order.version)
  check(
    Array.isArray(value.balances) &&
      Array.isArray(value.operations) &&
      order.plan &&
      Array.isArray(order.plan.lines) &&
      Array.isArray(order.plan.outputs),
    '申请计划或办理记录不完整',
  )
  for (const row of value.balances) {
    id(row.lineId)
    for (const name of ['planned', 'executed', 'returned', 'net', 'remaining'] as const)
      decimal(row[name])
    check(
      decimal(row.executed) - decimal(row.returned) === decimal(row.net) &&
        decimal(row.net) + decimal(row.remaining) === decimal(row.planned),
      '申请数量未守恒',
    )
  }
  const requests = new Set<string>()
  const planLines = new Set<string>()
  for (const line of [...order.plan.lines, ...order.plan.outputs]) {
    const lineId = id(line?.lineId)
    check(
      !planLines.has(lineId) && typeof line.unit === 'string' && !!line.unit.trim(),
      '原计划物料或基本单位不完整',
    )
    planLines.add(lineId)
  }
  const historyFlows = new Map<string, Flow>()
  for (const item of value.operations) {
    check(
      item &&
        typeof item.requestId === 'string' &&
        /^[a-zA-Z0-9][a-zA-Z0-9._:-]{7,99}$/.test(item.requestId) &&
        !requests.has(item.requestId) &&
        Object.prototype.hasOwnProperty.call(actions, item.action) &&
        item.orderId === order.id &&
        item.tenantId === context.tenantId &&
        item.factoryCode === context.factoryCode &&
        item.warehouseCode === context.warehouseCode &&
        item.status === 'REGISTERED' &&
        Array.isArray(item.flows) &&
        (!item.flows.length || (typeof item.billNo === 'string' && !!item.billNo.trim())),
      '办理历史归属不一致',
    )
    requests.add(item.requestId)
    for (const itemFlow of item.flows) {
      flow(itemFlow)
      check(
        planLines.has(itemFlow.lineId) && !historyFlows.has(itemFlow.id),
        '收发履历的物料或原流水不一致',
      )
      historyFlows.set(itemFlow.id, itemFlow)
    }
  }
  for (const item of historyFlows.values()) {
    if (item.sourceFlowId === null) continue
    const source = historyFlows.get(item.sourceFlowId)
    check(
      source &&
        source.sourceFlowId === null &&
        source.lineId === item.lineId &&
        source.direction !== item.direction,
      '退回履历缺少同物料的原实际收发记录',
    )
  }
  if (value.audit !== undefined) {
    check(
      Array.isArray(value.audit) && value.audit.length === requests.size,
      '办理登记时间未完整读取，请重新打开原单',
    )
    const seen = new Set<string>()
    for (const item of value.audit) {
      check(
        item &&
          requests.has(item.requestId) &&
          !seen.has(item.requestId) &&
          validRegisteredTime(item.registeredAt),
        '办理登记时间与原凭据不一致，请重新读取',
      )
      seen.add(item.requestId)
    }
  }
  return clone(value)
}

/** 无时区数据库时间保持原文，校日期和时分秒，不借终端时区推算。 */
function validRegisteredTime(value: unknown): boolean {
  if (typeof value !== 'string') return false
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,6})?$/.exec(value)
  if (!parts) return false
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number)
  const days = [
    31,
    year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ]
  return (
    year >= 1 &&
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= days[month - 1] &&
    hour <= 23 &&
    minute <= 59 &&
    second <= 59
  )
}

export function displayTime(value: string | null | undefined): string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value)
    ? value.slice(0, 19).replace('T', ' ')
    : value || '—'
}
