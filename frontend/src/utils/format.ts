export function formatDateTime(value: string): string {
  if (!value) {
    return '未填写'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

export function formatDate(value: string): string {
  if (!value) {
    return '未填写'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

export function formatMoney(value: number): string {
  return `¥${Number(value || 0).toFixed(0)}`
}

export function formatScore(value: number): string {
  return `${Number(value || 0).toFixed(1)} 分`
}

export function statusToneClass(value: string): string {
  if (value.includes('开放') || value.includes('通过') || value.includes('已完成')) {
    return 'is-success'
  }

  if (value.includes('待') || value.includes('进行中') || value.includes('已接单')) {
    return 'is-warning'
  }

  if (value.includes('拒绝') || value.includes('取消') || value.includes('关闭')) {
    return 'is-danger'
  }

  return 'is-neutral'
}

export function truncateText(value: string, length: number): string {
  if (value.length <= length) {
    return value
  }

  return `${value.slice(0, length)}…`
}
