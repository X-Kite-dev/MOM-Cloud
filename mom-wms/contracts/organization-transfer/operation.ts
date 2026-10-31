import { ref } from 'vue'
import {
  canonicalOrganization,
  organizationError,
  cloneOrganization,
  organizationId,
  organizationLabels,
  organizationSorted,
  verifyOrganization,
  type OrganizationCommand,
  type OrganizationContext,
  type OrganizationInput,
  type OrganizationReceipt,
} from './contract'
interface Identity {
  serverUrl: string
  username: string
  token: string
  factoryCode: string
  warehouseCode: string
}
interface Scope {
  serverUrl: string
  tenantId: string
  operatorId: string
  operatorCode: string
  factoryCode: string
  warehouseCode: string
}
interface Pending {
  version: 1
  scope: Scope
  savedAt: number
  command: OrganizationCommand
  caption: string
}
interface Ports {
  identity(): Identity
  context(): Promise<any>
  requestId(): string
  exclusive<T>(key: string, action: () => Promise<T>): Promise<T>
  storage: {
    get(key: string): unknown
    set(key: string, value: Pending): void
    remove(key: string): void
  }
  execute(command: OrganizationCommand): Promise<any>
  lookup(command: OrganizationCommand): Promise<any>
  abandon(command: OrganizationCommand, reason: string): Promise<any>
}
const key = (scope: Scope) =>
  `mom_wms_org_transfer_v1:${encodeURIComponent(organizationSorted(scope))}`
/** 两个端口共用；冻结原文先落盘，跨页面锁内核对原文，令牌只保留在内存失效签名。 */
export function createOrganizationOperation(ports: Ports) {
  const ready = ref(false)
  const busy = ref(false)
  const pending = ref<Pending | null>(null)
  const last = ref<OrganizationReceipt | null>(null)
  const context = ref<OrganizationContext | null>(null)
  const message = ref('')
  let scope: Scope | null = null
  let generation = 0
  let identity = ''
  function current(epoch: number) {
    const valid = epoch === generation && identity === organizationSorted(ports.identity())
    if (epoch === generation && !valid) {
      ready.value = false
      message.value = '登录、工厂或仓库已变化，请重新准备后核对原请求'
    }
    return valid
  }
  function capture() {
    if (!ready.value || !current(generation)) throw new Error('请先重新准备当前组织身份')
    return generation
  }
  function deactivate() {
    generation++
    ready.value = false
    busy.value = false
  }
  function load(actual: Scope): Pending | null {
    const raw = ports.storage.get(key(actual))
    if (raw === undefined || raw === null || raw === '') return null
    const value = raw as Pending
    if (
      value.version !== 1 ||
      !Number.isFinite(value.savedAt) ||
      value.savedAt <= 0 ||
      organizationSorted(value.scope) !== organizationSorted(actual) ||
      typeof value.caption !== 'string' ||
      value.caption.length > 200
    )
      throw new Error('本机原操作记录不完整，请保留原单核对')
    const body = canonicalOrganization(value.command)
    if (
      body.tenantId !== actual.tenantId ||
      body.operatorId !== actual.operatorId ||
      body.factoryCode !== actual.factoryCode ||
      body.warehouseCode !== actual.warehouseCode
    )
      throw new Error('本机原操作组织归属不一致')
    return cloneOrganization(value)
  }
  async function initialize() {
    deactivate()
    const epoch = generation
    const actual = ports.identity()
    identity = organizationSorted(actual)
    ready.value = false
    busy.value = true
    pending.value = null
    last.value = null
    context.value = null
    scope = null
    message.value = ''
    try {
      if (
        Object.values(actual).some((value) => typeof value !== 'string' || !value.trim()) ||
        /[*?]/.test(actual.warehouseCode)
      )
        throw new Error('请登录并从仓库组织调拨入口办理')
      const response = await ports.context()
      if (!current(epoch)) return
      const value = response?.data
      if (
        response?.code !== 0 ||
        value?.contractVersion !== 1 ||
        value.operatorCode !== actual.username ||
        value.factoryCode !== actual.factoryCode ||
        value.warehouseCode !== actual.warehouseCode ||
        !Array.isArray(value.permissions)
      )
        throw new Error(response?.msg || '实际库存组织身份未核实')
      scope = {
        serverUrl: actual.serverUrl,
        tenantId: organizationId(value.tenantId),
        operatorId: organizationId(value.operatorId),
        operatorCode: actual.username,
        factoryCode: actual.factoryCode,
        warehouseCode: actual.warehouseCode,
      }
      context.value = {
        ...cloneOrganization(value),
        tenantId: scope.tenantId,
        operatorId: scope.operatorId,
      }
      pending.value = load(scope)
      ready.value = true
    } catch (error) {
      if (current(epoch)) message.value = organizationError(error, '组织调拨准备失败')
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function finish(response: any, original: Pending, actual: Scope) {
    const verifiedContext = context.value
    if (!verifiedContext) throw new Error('请重新核对当前组织身份')
    const receipt = verifyOrganization(response, verifiedContext, original.command)
    if (receipt.status === 'NOT_FOUND') {
      message.value = '尚未查到原操作回执，请保留原内容继续核对'
      return
    }
    const stored = load(actual)
    if (!stored || organizationSorted(stored) !== organizationSorted(original))
      throw new Error('本机原操作已变化，不能清除核对记录')
    ports.storage.remove(key(actual))
    if (load(actual)) throw new Error('本机原记录未清除，请勿开始新操作')
    pending.value = null
    last.value = cloneOrganization(receipt)
    message.value =
      receipt.status === 'ABANDONED'
        ? '本次未执行请求已取消，可重新读取单据办理'
        : `${organizationLabels[receipt.action]}已登记`
  }
  async function perform(input: OrganizationInput, caption: string) {
    if (
      !ready.value ||
      busy.value ||
      pending.value ||
      !scope ||
      !context.value ||
      !current(generation)
    )
      return
    const epoch = generation
    const actual = cloneOrganization(scope)
    busy.value = true
    message.value = ''
    last.value = null
    try {
      const command: OrganizationCommand = {
        ...cloneOrganization(input),
        tenantId: actual.tenantId,
        operatorId: actual.operatorId,
        factoryCode: actual.factoryCode,
        warehouseCode: actual.warehouseCode,
        requestId: ports.requestId(),
      }
      canonicalOrganization(command)
      if (!context.value.permissions.includes(command.action))
        throw new Error('当前账号没有本次组织调拨操作权限')
      await ports.exclusive(key(actual), async () => {
        if (!current(epoch)) return
        const previous = load(actual)
        if (previous) {
          pending.value = previous
          throw new Error('当前仓库已有结果待确认的操作，请先核对')
        }
        const original: Pending = {
          version: 1,
          scope: actual,
          savedAt: Date.now(),
          command,
          caption: String(caption).slice(0, 200),
        }
        try {
          ports.storage.set(key(actual), cloneOrganization(original))
          const saved = load(actual)
          if (!saved || organizationSorted(saved) !== organizationSorted(original))
            throw new Error('原操作内容未可靠保存')
          pending.value = saved
        } catch (error) {
          try {
            pending.value = load(actual)
          } catch {
            ready.value = false
          }
          throw error
        }
        const response = await ports.execute(cloneOrganization(command))
        if (current(epoch)) finish(response, original, actual)
      })
    } catch (error) {
      if (current(epoch)) message.value = organizationError(error, '操作结果未确认，请核对原请求')
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function resolve(action: 'query' | 'retry' | 'abandon', reason = '') {
    if (
      !ready.value ||
      busy.value ||
      !pending.value ||
      !scope ||
      !context.value ||
      !current(generation)
    )
      return
    const epoch = generation
    const actual = cloneOrganization(scope)
    const original = cloneOrganization(pending.value)
    busy.value = true
    message.value = ''
    try {
      if (action === 'abandon' && (!reason.trim() || reason.trim().length > 500))
        throw new Error('请填写500字以内的取消原因')
      await ports.exclusive(key(actual), async () => {
        if (!current(epoch)) return
        const stored = load(actual)
        if (!stored || organizationSorted(stored) !== organizationSorted(original))
          throw new Error('本机原操作已变化，请重新准备并核对')
        const command = cloneOrganization(original.command)
        let response
        if (action === 'query') response = await ports.lookup(command)
        else if (action === 'retry') response = await ports.execute(command)
        else response = await ports.abandon(command, reason.trim())
        if (current(epoch)) finish(response, original, actual)
      })
    } catch (error) {
      if (current(epoch)) message.value = organizationError(error, '原操作结果未确认，请继续核对')
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  return {
    ready,
    busy,
    pending,
    last,
    context,
    message,
    initialize,
    perform,
    resolve,
    capture,
    current,
    deactivate,
    dispose: deactivate,
  }
}
