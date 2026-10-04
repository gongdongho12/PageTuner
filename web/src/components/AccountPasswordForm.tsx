import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { AccountClient } from '../lib/accountApi'
import { validatePasswordChange } from '../lib/accountPassword'
import { ApiError } from '../lib/errors'
import { useLocale } from '../lib/locale'

export function AccountPasswordForm({ client, username, onBack, onChanged, onUncertain }: {
  client: AccountClient; username: string; onBack: () => void; onChanged: () => void; onUncertain: () => void
}) {
  const { t } = useLocale()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const request = useRef<AbortController | null>(null)
  useEffect(() => {
    // Capture this client's callback: cleanup must never disconnect a replacement login.
    const uncertain = onUncertain
    return () => {
      if (request.current) {
        request.current.abort()
        request.current = null
        // Navigation cannot tell whether the server committed an in-flight mutation.
        uncertain()
      }
    }
  }, [client])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (request.current) return
    setError('')
    let input
    try { input = validatePasswordChange({ currentPassword, newPassword }, confirmation) }
    catch (failure) { setError(failure instanceof ApiError ? failure.message : '현재 비밀번호를 입력해 주세요.'); return }
    const controller = new AbortController()
    request.current = controller
    setBusy(true)
    try {
      await client.changePassword(input, controller.signal)
      if (controller.signal.aborted) return
      setCurrentPassword(''); setNewPassword(''); setConfirmation('')
      request.current = null
      onChanged()
    } catch (failure) {
      if (!controller.signal.aborted) {
        const known = failure instanceof ApiError && !['network', 'timeout', 'server', 'invalid-response', 'conflict', 'authentication'].includes(failure.kind)
        if (known) setError(failure.message)
        else {
          setCurrentPassword(''); setNewPassword(''); setConfirmation('')
          request.current = null
          onUncertain()
        }
      }
    } finally {
      if (!controller.signal.aborted) { request.current = null; setBusy(false) }
    }
  }

  return <section className="account-view">
    <div className="account-intro"><h2>{t('비밀번호 변경')}</h2><p>{t('변경 후 새 비밀번호로 다시 로그인해 주세요.')}</p></div>
    <div className="account-card account-password-card">
      <nav className="workflow-subtabs"><button type="button" disabled={busy} onClick={onBack}>{t('계정 설정으로')}</button></nav>
      <form onSubmit={event => { void submit(event) }}>
        <div className="account-fields">
          <h3>{t('비밀번호 변경')}</h3>
          <input type="hidden" name="username" autoComplete="username" value={username}/>
          <label>{t('현재 비밀번호')}<input name="currentPassword" type="password" autoComplete="current-password" value={currentPassword} maxLength={72} required disabled={busy} onChange={e => setCurrentPassword(e.target.value)}/></label>
          <label>{t('새 비밀번호')}<input name="newPassword" type="password" autoComplete="new-password" value={newPassword} maxLength={72} required disabled={busy} onChange={e => setNewPassword(e.target.value)} aria-describedby="password-policy"/></label>
          <label>{t('새 비밀번호 확인')}<input name="confirmation" type="password" autoComplete="new-password" value={confirmation} maxLength={72} required disabled={busy} onChange={e => setConfirmation(e.target.value)}/></label>
          <p id="password-policy" className="workflow-help">{t('10자 이상 · UTF-8 72바이트 이내')}<br/>{t('변경 후 새 비밀번호로 다시 로그인해 주세요.')}</p>
          {error && <p className="account-error" role="alert">{t(error)}</p>}
        </div>
        <div className="account-actions"><button className="button-primary" disabled={busy}>{t(busy ? '변경 중…' : '비밀번호 변경')}</button></div>
      </form>
    </div>
  </section>
}
