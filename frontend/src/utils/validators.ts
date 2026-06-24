import { isURL } from './url'

export function validateNickname(nickname: string): string {
  if (!nickname || nickname.trim().length === 0) return '昵称不能为空'
  if (nickname.trim().length < 2) return '昵称至少 2 个字符'
  return ''
}

export function validateAvatarUrl(url: string): string {
  if (!url) return ''
  const trimmed = url.trim()
  // 允许相对路径（以 / 开头）和完整 http/https URL
  if (trimmed.startsWith('/')) return ''
  if (!isURL(trimmed)) return '请输入有效的图片链接'
  return ''
}

export function validateLoginForm(form: { studentId: string; password: string }): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!form.studentId || !form.studentId.trim()) errors.studentId = '请输入学号'
  if (!form.password || form.password.length === 0) errors.password = '请输入密码'
  return errors
}

export function validateRegisterForm(form: {
  studentId: string
  password: string
  emailPrefix: string
  verificationCode: string
}): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!form.studentId || !form.studentId.trim()) errors.studentId = '请输入学号'
  if (!form.password || form.password.length < 8) errors.password = '密码至少 8 位'
  if (!form.emailPrefix || !/^[0-9]+$/.test(form.emailPrefix.trim())) errors.emailPrefix = '请输入数字邮箱前缀'
  if (!form.verificationCode || !/^\d{6}$/.test(form.verificationCode.trim())) errors.verificationCode = '请输入 6 位验证码'
  return errors
}
