import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { useAlert } from '@/composables/useDialog'

/**
 * 统一的「查看用户公开主页」入口。
 *
 * <p>跳转前先查询目标用户：不存在或接口失败时给出提示，不跳到空白页；
 * 匿名身份没有 userId，调用方应直接渲染纯文本而非按钮。
 */
export function useUserProfileLink() {
  const router = useRouter()
  const opening = ref(false)

  async function openUserProfile(userId: string | null | undefined, displayName?: string): Promise<boolean> {
    const id = String(userId ?? '').trim()
    if (!id || opening.value) {
      return false
    }
    opening.value = true
    // 延迟获取 store：组件可能在未安装 Pinia 的纯展示测试中挂载
    const store = useCampusHubStore()
    try {
      await store.fetchPublicUserProfile(id)
      if (!store.publicUserProfile || store.publicUserProfile.id !== id) {
        await useAlert('无法查看主页', `用户${displayName ? `「${displayName}」` : ''}不存在或已注销。`)
        return false
      }
      await router.push(`/users/${encodeURIComponent(id)}/profile`)
      return true
    } catch {
      await useAlert('无法查看主页', store.publicUserProfileError || '该用户主页暂时不可用，请稍后再试。')
      return false
    } finally {
      opening.value = false
    }
  }

  return { openUserProfile }
}
