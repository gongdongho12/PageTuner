import type { components } from '../generated/accounts'
import { ApiError } from './errors'

export type ChangePassword = components['schemas']['ChangePasswordRequest']
export const passwordPolicyMessage = '비밀번호는 10자 이상, UTF-8 72바이트 이내로 입력해 주세요. 제어문자는 사용할 수 없습니다.'
export function validatePassword(value: string): void {
  if (Array.from(value).length < 10 || new TextEncoder().encode(value).length > 72 || /[\u0000-\u001f\u007f-\u009f]/.test(value))
    throw new ApiError('invalid-request', passwordPolicyMessage)
}
export function validatePasswordChange(input: ChangePassword, confirmation?: string): ChangePassword {
  // Existing bootstrap accounts can have a password shorter than the registration minimum.
  if (!input.currentPassword || new TextEncoder().encode(input.currentPassword).length > 72 || /[\u0000-\u001f\u007f-\u009f]/.test(input.currentPassword))
    throw new ApiError('invalid-request', '현재 비밀번호를 입력해 주세요.')
  validatePassword(input.newPassword)
  if (input.currentPassword === input.newPassword)
    throw new ApiError('invalid-request', '현재 비밀번호와 다른 새 비밀번호를 입력해 주세요.')
  if (confirmation !== undefined && input.newPassword !== confirmation)
    throw new ApiError('invalid-request', '새 비밀번호와 확인 입력이 일치하지 않습니다.')
  return { currentPassword: input.currentPassword, newPassword: input.newPassword }
}

/** Only allowlisted codes and fixed messages are exposed; server detail may contain credentials. */
export function passwordChangeError(code: unknown, status: number): ApiError {
  const message = status === 400 && code === 'CURRENT_PASSWORD_INCORRECT' ? '현재 비밀번호가 일치하지 않습니다.'
    : status === 400 && code === 'PASSWORD_UNCHANGED' ? '현재 비밀번호와 다른 새 비밀번호를 입력해 주세요.'
    : status === 400 && code === 'INVALID_PASSWORD' ? passwordPolicyMessage
    : status === 429 ? '비밀번호 변경 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    : status === 409 ? '다른 요청에서 비밀번호가 변경되었습니다. 다시 로그인해 주세요.'
    : '비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인을 확인해 주세요.'
  return new ApiError(status === 409 ? 'conflict' : status >= 500 ? 'server' : 'invalid-request', message, status)
}
