/** numeric(20,8)的正数量文本；禁止接收已经过JSON浮点解析的来源数值。 */
export function quantityText(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{1,12}(?:\.\d{1,8})?$/.test(value.trim()))
    throw new Error('数量须使用十进制文本，最多12位整数和8位小数；请重新读取来源')
  const [integer, fraction = ''] = value.trim().split('.')
  const whole = integer.replace(/^0+(?=\d)/, '')
  const decimal = fraction.replace(/0+$/, '')
  const result = decimal ? `${whole}.${decimal}` : whole
  if (result === '0') throw new Error('所选库存的申请数量必须大于0')
  return result
}

export function compareQuantity(left: string, right: string): number {
  const scaled = (value: string) => {
    const [whole, fraction = ''] = quantityText(value).split('.')
    return (whole + fraction.padEnd(8, '0')).replace(/^0+(?=\d)/, '')
  }
  const a = scaled(left)
  const b = scaled(right)
  if (a.length !== b.length) return a.length < b.length ? -1 : 1
  if (a === b) return 0
  return a < b ? -1 : 1
}
