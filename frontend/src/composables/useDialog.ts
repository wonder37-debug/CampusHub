import { ref } from 'vue'

export type DialogType = 'confirm' | 'alert'

export interface DialogOptions {
  title: string
  message: string
  type?: DialogType
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

interface PendingDialog {
  id: number
  options: DialogOptions
  resolve: (value: boolean) => void
}

const pendingDialogs = ref<PendingDialog[]>([])
let dialogIdCounter = 0

/**
 * 显示确认对话框（替代 window.confirm）
 * @param title 标题
 * @param message 描述文本
 * @param options 可选配置
 * @returns Promise<boolean> - true 表示用户点击了确认，false 表示取消
 */
export function useConfirm(
  title: string,
  message: string,
  options?: Partial<DialogOptions>
): Promise<boolean> {
  return showDialog({
    type: 'confirm',
    title,
    message,
    confirmText: '确认',
    cancelText: '取消',
    ...options
  })
}

/**
 * 显示提示对话框（替代 window.alert）
 * @param title 标题
 * @param message 描述文本
 * @param options 可选配置
 * @returns Promise<void>
 */
export function useAlert(
  title: string,
  message: string,
  options?: Partial<DialogOptions>
): Promise<void> {
  return showDialog({
    type: 'alert',
    title,
    message,
    confirmText: '确定',
    ...options
  }).then(() => {})
}

/**
 * 显示对话框
 */
function showDialog(options: DialogOptions): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const id = ++dialogIdCounter
    pendingDialogs.value.push({
      id,
      options: {
        type: 'confirm',
        confirmText: '确认',
        cancelText: '取消',
        danger: false,
        ...options
      },
      resolve
    })
  })
}

/**
 * 处理对话框响应
 */
export function handleDialogResponse(id: number, confirmed: boolean): void {
  const index = pendingDialogs.value.findIndex((d) => d.id === id)
  if (index !== -1) {
    const dialog = pendingDialogs.value[index]
    dialog.resolve(confirmed)
    pendingDialogs.value.splice(index, 1)
  }
}

/**
 * 获取当前待处理的对话框列表
 */
export function getPendingDialogs() {
  return pendingDialogs
}
