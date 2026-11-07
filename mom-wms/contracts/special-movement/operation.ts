import { ref, watch } from 'vue'
import {
  body,
  check,
  clone,
  errorText,
  permission,
  sorted,
  validateCommand,
  validateContext,
  validateReceipt,
  type Command,
  type Context,
  type Input,
  type Receipt,
} from './contract'
export interface Identity {
  /** 当前实际服务环境。 */
  serverUrl: string
  /** 当前实名登录账号。 */
  username: string
  /** 令牌及端侧租户/权限版本仅参与内存比较，不进入原请求存储。 */
  token: string
  /** 本机选择的实际工厂编码。 */
  factoryCode: string
  /** 当前业务入口的实际仓库编码。 */
  warehouseCode: string
}
interface Scope {
  /** 原请求所属服务环境。 */
  serverUrl: string
  /** 服务核实的真实租户Long原文。 */
  tenantId: string
  /** 原请求实际办理账号。 */
  operatorCode: string
  /** 原请求所属工厂。 */
  factoryCode: string
  /** 原请求所属仓库。 */
  warehouseCode: string
}
interface Pending {
  /** 本机原请求格式版本，保持既有v1合同。 */
  version: 1
  /** 原请求实际组织和本人范围，不携带令牌。 */
  scope: Scope
  /** 本机首次可靠保存时刻，毫秒。 */
  savedAt: number
  /** 原作业简短说明，不代替原命令。 */
  caption: string
  /** 完整原号原文，恢复不按当前表单重新构造。 */
  command: Command
}
export type Guard = () => void
interface Ports {
  /** 相关身份存储变更直接失效，回焦核末态；事件只可撤销旧范围，不能授予新范围。 */
  observeScope?(check: () => void, invalidate: () => void): () => void
  /** 当前实际身份及入口，读取不得修改持久原请求。 */
  identity(): Identity
  context(guard: Guard): Promise<unknown>
  requestId(): string
  exclusive<T>(key: string, action: () => Promise<T>): Promise<T>
  storage: {
    get(key: string): unknown
    set(key: string, value: Pending): void
    remove(key: string): void
  }
  execute(command: Command, guard: Guard): Promise<unknown>
  lookup(command: Command, guard: Guard): Promise<unknown>
  end(command: Command, reason: string, guard: Guard): Promise<unknown>
}
const storageKey = (scope: Scope) =>
  `mom_wms_special_movement_v1:${encodeURIComponent(sorted(scope))}`
/** 写前持久化完整原文；未确认结果保持锁定，任何身份切换都使旧请求失效。 */
export function createSpecialOperation(ports: Ports) {
  const ready = ref(false)
  const busy = ref(false)
  const pending = ref<Pending | null>(null)
  const last = ref<Receipt | null>(null)
  const context = ref<Context | null>(null)
  const message = ref('')
  let generation = 0
  let identity = ''
  let active = false
  let stopObserving: (() => void) | undefined
  let scope: Scope | null = null
  /** 实际身份不符立即推进代次，切回原身份也不能接受旧guard或回包。 */
  function current(epoch: number) {
    if (!active || epoch !== generation) return false
    if (identity !== sorted(ports.identity())) {
      invalidateScope()
      return false
    }
    return true
  }
  function capture() {
    check(ready.value && current(generation), '请先核实当前仓库身份')
    return generation
  }
  function guard(epoch: number) {
    return () => check(current(epoch), '当前身份已变化，已阻止发送')
  }
  /** 离开或失效只清内存，原范围的持久原号原文保持，重新核实身份后恢复。 */
  function deactivate() {
    active = false
    stopObserving?.()
    stopObserving = undefined
    generation++
    ready.value = false
    busy.value = false
    identity = ''
    scope = null
    context.value = null
    pending.value = null
    last.value = null
    message.value = ''
  }
  /** 身份事件即使最终已切回原值，也须撤销原代次；不读取事件中的身份作为业务依据。 */
  function invalidateScope() {
    deactivate()
    message.value = '登录、工厂或仓库已变化，请重新连接后核对原请求'
  }
  watch(
    () => sorted(ports.identity()),
    () => {
      if (active) current(generation)
    },
    { flush: 'sync' },
  )
  function load(actual: Scope): Pending | null {
    const raw = ports.storage.get(storageKey(actual))
    if (raw === undefined || raw === null || raw === '') return null
    const value = raw as Pending
    check(
      value?.version === 1 &&
        Number.isFinite(value.savedAt) &&
        value.savedAt > 0 &&
        typeof value.caption === 'string' &&
        value.caption.length <= 200 &&
        sorted(value.scope) === sorted(actual),
      '本机原操作记录不完整，请保留记录核对',
    )
    validateCommand(value.command)
    check(value.command.warehouseCode === actual.warehouseCode, '本机原操作仓库不一致')
    return clone(value)
  }
  /** 每次重新核实服务端真实身份，再恢复相同原范围的未决请求，不自动采用其他账号原件。 */
  async function initialize() {
    deactivate()
    const epoch = generation
    const actual = ports.identity()
    identity = sorted(actual)
    active = true
    scope = null
    context.value = null
    pending.value = null
    last.value = null
    message.value = ''
    busy.value = true
    try {
      check(typeof BigInt === 'function', '当前设备版本不支持精确数量运算，请升级设备浏览器后办理')
      check(
        Object.values(actual).every((value) => typeof value === 'string' && !!value.trim()) &&
          !/[*?]/.test(actual.warehouseCode),
        '请登录并从实际仓库入口办理',
      )
      const stop = ports.observeScope?.(
        () => current(epoch),
        () => {
          if (active && epoch === generation) invalidateScope()
        },
      )
      if (!current(epoch)) {
        stop?.()
        return
      }
      stopObserving = stop
      const response = await ports.context(guard(epoch))
      if (!current(epoch)) return
      const value = validateContext(body<Context>(response), actual)
      scope = {
        serverUrl: actual.serverUrl,
        tenantId: value.tenantId,
        operatorCode: value.operatorCode,
        factoryCode: value.factoryCode,
        warehouseCode: value.warehouseCode,
      }
      context.value = value
      pending.value = load(scope)
      ready.value = true
    } catch (error) {
      if (current(epoch)) message.value = errorText(error, '特殊收发准备失败')
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function finish(response: unknown, original: Pending, actual: Scope) {
    check(context.value, '请重新核实当前身份')
    const value = validateReceipt(response, context.value, original.command)
    if (!value) {
      message.value = '尚未查到原请求结果，请继续查询或按原内容重试'
      return
    }
    check(sorted(load(actual)) === sorted(original), '本机原操作已变化，不能清除记录')
    ports.storage.remove(storageKey(actual))
    check(load(actual) === null, '本机原记录未清除，请先核对原结果')
    pending.value = null
    last.value = value
    message.value = value.status === 'CANCELLED' ? '原请求已安全结束' : '本次办理已确认'
  }
  async function perform(input: Input, caption: string) {
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
    const actual = clone(scope)
    busy.value = true
    message.value = ''
    last.value = null
    try {
      const command = validateCommand({
        ...clone(input),
        warehouseCode: actual.warehouseCode,
        requestId: ports.requestId(),
      })
      check(context.value.permissions.includes(permission[command.action]), '当前账号无此操作权限')
      await ports.exclusive(storageKey(actual), async () => {
        guard(epoch)()
        const existing = load(actual)
        if (existing) {
          pending.value = existing
          throw Error('当前仓库有结果待确认的操作，请先核对')
        }
        const original: Pending = {
          version: 1,
          scope: actual,
          savedAt: Date.now(),
          caption: caption.slice(0, 200),
          command,
        }
        try {
          ports.storage.set(storageKey(actual), clone(original))
          const saved = load(actual)
          check(saved && sorted(saved) === sorted(original), '原操作内容未可靠保存')
          pending.value = saved
        } catch (error) {
          try {
            pending.value = load(actual)
          } catch {
            ready.value = false
          }
          throw error
        }
        guard(epoch)()
        const response = await ports.execute(clone(command), guard(epoch))
        if (current(epoch)) finish(response, original, actual)
      })
    } catch (error) {
      if (current(epoch)) message.value = errorText(error)
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function resolve(action: 'query' | 'retry' | 'end', reason = '') {
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
    const actual = clone(scope)
    const original = clone(pending.value)
    busy.value = true
    message.value = ''
    try {
      if (action === 'end')
        check(reason.trim() && reason.trim().length <= 500, '请填写500字以内的结束原因')
      await ports.exclusive(storageKey(actual), async () => {
        guard(epoch)()
        check(sorted(load(actual)) === sorted(original), '本机原操作已变化，请重新核对')
        const command = clone(original.command)
        const response =
          action === 'query'
            ? await ports.lookup(command, guard(epoch))
            : action === 'retry'
              ? await ports.execute(command, guard(epoch))
              : await ports.end(command, reason.trim(), guard(epoch))
        if (current(epoch)) finish(response, original, actual)
      })
    } catch (error) {
      if (current(epoch)) message.value = errorText(error, '原操作结果未确认，请继续核对')
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
    current,
    /** 当前内存代次，仅供初始化调用者核原读取，不作为业务版本或持久依据。 */
    version: () => generation,
    capture,
    guard,
    deactivate,
    dispose: deactivate,
  }
}
