import type { LibraryOrganizationScope, LibraryOrganizationView } from './libraryOrganizationApi'

export const organizationExportErrors = {
  stale: '계정 또는 서버 연결이 변경되어 분류 조회를 취소했습니다. 다시 선택해 주세요.',
  offline: '최신 계정 분류를 포함하려면 서버에 다시 로그인해 주세요.',
  pending: '먼저 계정 분류의 대기 중인 변경이나 충돌을 해결한 뒤 다시 내보내 주세요.',
  response: '최신 계정 분류 응답이 기존 기록과 일치하지 않습니다. 다시 조회해 주세요.',
} as const

/** Read-only session: never opens a controller, initializes a journal, or sends mutations. */
export interface FreshLibraryOrganizationReader {
  assertCurrent(): void
  read(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<LibraryOrganizationView>
  checkReady(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<void>
}
