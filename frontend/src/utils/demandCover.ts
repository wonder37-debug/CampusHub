import type { CampusZone, DemandCategory } from '@/types/campushub'

const COVER_CATEGORIES: readonly DemandCategory[] = [
  'EXPRESS',
  'ERRAND',
  'STUDY_TUTORING',
  'SECOND_HAND',
  'TEAM_UP',
  'HELP',
  'OTHER'
]

/**
 * 把任意分类字符串收敛为后端认识的分类枚举名，无法识别时兜底为 OTHER。
 */
export function normalizeCoverCategory(category: string | null | undefined): DemandCategory {
  const normalized = String(category ?? '').trim().toUpperCase()
  return (COVER_CATEGORIES as readonly string[]).includes(normalized)
    ? (normalized as DemandCategory)
    : 'OTHER'
}

/**
 * 拼接后端兜底封面地址。
 *
 * <p>地点文本含中文、空格、`·`、括号，必须走 URLSearchParams 编码，
 * 手工拼字符串在部分浏览器/代理上会出问题；参数为空时不拼出 `?zone=&loc=` 这种空串。
 */
export function demandCoverUrl(
  category: string | null | undefined,
  seed: string | number | null | undefined,
  campusZone?: CampusZone | string | null,
  location?: string | null
): string {
  const resolvedCategory = normalizeCoverCategory(category)
  const resolvedSeed = seed === null || seed === undefined || String(seed).trim() === ''
    ? '0'
    : String(seed).trim()

  const params = new URLSearchParams()
  const zone = campusZone?.trim()
  const place = location?.trim()
  if (zone) {
    params.set('zone', zone)
  }
  if (place) {
    params.set('loc', place)
  }

  const base = `/api/v1/covers/${resolvedCategory}/${encodeURIComponent(resolvedSeed)}.jpg`
  const query = params.toString()
  return query ? `${base}?${query}` : base
}
