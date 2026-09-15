import type { components } from '../generated/workflow'
import type { ProviderKind } from './workflowTypes'
import { ApiError } from './errors'

export type ProviderCheckInput = components['schemas']['TranslationProviderCheckRequest']
export type ProviderCheckResult = components['schemas']['TranslationProviderCheckResponse']
/** Only a public error code crosses from HTTP failure handling into the settings UI. */
export class ProviderCheckError extends ApiError {
  constructor(readonly code: string, status: number) {
    super('invalid-request', providerFailureMessage(code), status)
  }
}
const kinds: readonly ProviderKind[] = ['GOOGLE_WEB_TRANSLATE_HTML', 'GOOGLE_CLOUD', 'DEEPSEEK', 'OPENAI_COMPATIBLE_LLM']
const invalid = () => new ApiError('invalid-response', '번역기 연결 확인 응답이 현재 설정과 일치하지 않습니다. 다시 확인해 주세요.')
const badSettings = () => new ApiError('invalid-request', '번역기·언어·연결 설정을 확인해 주세요.')
const language = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z][A-Za-z0-9-]{0,23}$/.test(value)
const control = /[\u0000-\u001f\u007f]/

/** Project only provider settings. Source text, glossary and document identities never enter a check. */
export function createProviderCheckInput(input: ProviderCheckInput): ProviderCheckInput {
  if (!kinds.includes(input.providerKind) || !language(input.targetLanguage?.trim() ?? 'ko') ||
    !language(input.sourceLanguage?.trim() || 'auto')) throw badSettings()
  const sourceLanguage = input.sourceLanguage?.trim() || 'auto'
  const targetLanguage = input.targetLanguage?.trim() ?? 'ko'
  if (targetLanguage.toLowerCase() === 'auto' || sourceLanguage.toLowerCase() === targetLanguage.toLowerCase()) throw badSettings()
  const apiKey = input.apiKey?.trim() || undefined
  if (input.apiKey && (input.apiKey.length > 4096 || control.test(input.apiKey))) throw badSettings()
  const llm = input.providerKind === 'DEEPSEEK' || input.providerKind === 'OPENAI_COMPATIBLE_LLM'
  const endpoint = llm ? input.endpoint?.trim().replace(/\/+$/, '') || undefined : undefined
  const model = llm ? input.model?.trim() || undefined : undefined
  if (model && (model.length > 200 || control.test(model))) throw badSettings()
  if (endpoint) {
    try {
      const url = new URL(endpoint)
      const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      if (endpoint.length > 2000 || control.test(endpoint) || url.username || url.password || url.search || url.hash ||
        !(url.protocol === 'https:' || url.protocol === 'http:' && loopback)) throw badSettings()
    } catch { throw badSettings() }
  }
  return { providerKind: input.providerKind, sourceLanguage, targetLanguage, ...(apiKey ? { apiKey } : {}),
    ...(endpoint ? { endpoint } : {}), ...(model ? { model } : {}) }
}

export function validateProviderCheck(value: unknown, input: ProviderCheckInput): ProviderCheckResult {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
  const result = value as Record<string, unknown>
  if (!['SUCCESS', 'FAILED'].includes(String(result.status)) || result.providerKind !== input.providerKind ||
    !language(result.sourceLanguage) || !language(result.targetLanguage) ||
    result.sourceLanguage.toLowerCase() !== (input.sourceLanguage || 'auto').toLowerCase() ||
    result.targetLanguage.toLowerCase() !== (input.targetLanguage || 'ko').toLowerCase() ||
    typeof result.model !== 'string' || result.model.length > 200 || control.test(result.model) ||
    (input.model && result.model !== input.model) ||
    typeof result.code !== 'string' || !/^[A-Z_]{1,100}$/.test(result.code) ||
    (result.status === 'SUCCESS') !== (result.code === 'PROVIDER_CHECK_OK') ||
    typeof result.message !== 'string' || !result.message.trim() || result.message.length > 500) throw invalid()
  return { status: result.status, providerKind: input.providerKind, sourceLanguage: result.sourceLanguage,
    targetLanguage: result.targetLanguage, model: result.model, code: result.code, message: result.message } as ProviderCheckResult
}

export function providerFailureMessage(code: string | null | undefined, fallback = '번역을 완료하지 못했습니다. 번역 설정과 연결을 확인해 주세요.'): string {
  switch (code) {
    case 'PROVIDER_CHECK_OK': return '번역기 연결과 응답 형식을 확인했습니다.'
    case 'PROVIDER_CHECK_TIMEOUT': return '번역기 연결 확인이 시간 내 끝나지 않았습니다. 잠시 후 다시 시도해 주세요.'
    case 'PROVIDER_CHECK_CANCELLED': return '번역기 연결 확인이 취소되었습니다.'
    case 'PROVIDER_CHECK_BUSY': return '연결 확인 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
    case 'PROVIDER_NOT_CONFIGURED': return '제공자 API 키를 확인한 뒤 다시 번역해 주세요.'
    case 'ENDPOINT_NOT_ALLOWED': return '서버에서 허용한 제공자 주소를 사용해 주세요.'
    case 'INVALID_PROVIDER': return '지원하지 않는 번역 제공자입니다.'
    case 'TRANSLATION_RATE_LIMITED': return '번역 제공자가 요청을 제한하고 있습니다. 잠시 후 다시 시도해 주세요.'
    case 'TRANSLATION_AUTHENTICATION_FAILED': return '번역 제공자 인증에 실패했습니다. API 키와 접근 권한을 확인해 주세요.'
    case 'TRANSLATION_QUOTA_EXCEEDED': return '번역 제공자의 사용량 한도에 도달했습니다. 제공자 계정의 한도를 확인해 주세요.'
    case 'TRANSLATION_BAD_REQUEST': return '번역 제공자가 요청을 처리할 수 없습니다. 언어와 번역 설정을 확인해 주세요.'
    case 'TRANSLATION_SERVER_ERROR': return '번역 제공자 서버에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
    case 'TRANSLATION_NETWORK_ERROR': return '번역 제공자에 연결하지 못했습니다. 연결 상태를 확인해 주세요.'
    case 'TRANSLATION_INVALID_RESPONSE': return '번역 제공자가 완전한 번역 결과를 보내지 않았습니다. 설정을 확인하고 다시 시도해 주세요.'
    case 'TRANSLATION_CONFIGURATION_ERROR': return '번역 제공자 설정이 올바르지 않습니다. 서버 주소와 모델 설정을 확인해 주세요.'
    default: return fallback
  }
}
