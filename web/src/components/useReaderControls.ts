import { useEffect, useRef, useState, type PointerEvent, type RefObject } from 'react'
import { touchPageDirection, type ReaderPreferences } from '../lib/readerPreferences'

export function useReaderTouch(direction: ReaderPreferences['touchDirection'], turn: (direction: -1 | 1) => void) {
  const down = useRef<{ x: number; y: number; time: number } | undefined>(undefined)
  return {
    onPointerDown: (event: PointerEvent<HTMLElement>) => { if (event.isPrimary && event.button === 0) down.current = { x: event.clientX, y: event.clientY, time: performance.now() } },
    onPointerUp: (event: PointerEvent<HTMLElement>) => {
      const start = down.current; down.current = undefined
      if (!start || !event.isPrimary || performance.now() - start.time > 500 || Math.hypot(event.clientX - start.x, event.clientY - start.y) > 10 || !window.getSelection()?.isCollapsed) return
      const bounds = event.currentTarget.getBoundingClientRect(), move = touchPageDirection((event.clientX - bounds.left) / bounds.width, direction)
      if (move) turn(move)
    },
    onPointerCancel: () => { down.current = undefined },
  }
}

export function useReaderFullscreen(element: RefObject<HTMLElement | null>) {
  const [fullscreen, setFullscreen] = useState(false), [error, setError] = useState('')
  useEffect(() => { const update = () => setFullscreen(!!document.fullscreenElement); document.addEventListener('fullscreenchange', update); return () => document.removeEventListener('fullscreenchange', update) }, [])
  async function toggle() {
    try {
      setError('')
      if (document.fullscreenElement) await document.exitFullscreen()
      else if (element.current?.requestFullscreen) await element.current.requestFullscreen()
      else throw new Error('이 브라우저에서는 전체 화면을 사용할 수 없습니다.')
    } catch { setError('전체 화면을 전환하지 못했습니다. 브라우저의 전체 화면 지원과 권한을 확인해 주세요.') }
  }
  return { fullscreen, error, toggle }
}
