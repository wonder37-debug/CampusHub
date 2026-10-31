import { describe, expect, it } from 'vitest'
import { demandCoverUrl, normalizeCoverCategory } from '@/utils/demandCover'

describe('兜底封面地址拼接', () => {
  it('未传校区与地点时只保留路径，不拼空 query', () => {
    expect(demandCoverUrl('EXPRESS', '12')).toBe('/api/v1/covers/EXPRESS/12.jpg')
    expect(demandCoverUrl('EXPRESS', '12', '', '   ')).toBe('/api/v1/covers/EXPRESS/12.jpg')
    expect(demandCoverUrl('EXPRESS', '12', null, null)).toBe('/api/v1/covers/EXPRESS/12.jpg')
  })

  it('校区与地点按 URLSearchParams 编码，中文与特殊字符不裸奔', () => {
    const url = demandCoverUrl('STUDY_TUTORING', 3, 'XIANLIN', '仙林校区 · 杜厦图书馆研讨间')

    expect(url.startsWith('/api/v1/covers/STUDY_TUTORING/3.jpg?')).toBe(true)
    const query = new URLSearchParams(url.split('?')[1])
    expect(query.get('zone')).toBe('XIANLIN')
    expect(query.get('loc')).toBe('仙林校区 · 杜厦图书馆研讨间')
    // 中文、空格、间隔号都必须被百分号编码
    expect(url).not.toMatch(/[\u4e00-\u9fa5]/)
    expect(url).toContain('%E4%BB%99%E6%9E%97')
  })

  it('分类无法识别时兜底为 OTHER，seed 缺失时用 0', () => {
    expect(demandCoverUrl('NOT_A_CATEGORY', 1)).toBe('/api/v1/covers/OTHER/1.jpg')
    expect(demandCoverUrl(null, null)).toBe('/api/v1/covers/OTHER/0.jpg')
    expect(demandCoverUrl(undefined, undefined, 'GULOU')).toBe('/api/v1/covers/OTHER/0.jpg?zone=GULOU')
  })

  it('分类名大小写与空白都会被规范化', () => {
    expect(normalizeCoverCategory(' study_tutoring ')).toBe('STUDY_TUTORING')
    expect(normalizeCoverCategory('second_hand')).toBe('SECOND_HAND')
    expect(demandCoverUrl(' help ', 9)).toBe('/api/v1/covers/HELP/9.jpg')
  })

  it('只有地点时也能拼出 loc 参数', () => {
    const url = demandCoverUrl('SECOND_HAND', '7', undefined, '五栋宿舍路口')
    const query = new URLSearchParams(url.split('?')[1])
    expect(query.get('loc')).toBe('五栋宿舍路口')
    expect(query.get('zone')).toBeNull()
  })
})
