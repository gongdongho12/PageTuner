import React from 'react'
import ReactDOM from 'react-dom/client'
import SharingApp from './SharingApp'
import '../styles.css'

// The phone serves this complete shell over HTTP. No cloud login, service worker,
// IndexedDB or WebCrypto is needed; library data and session tokens stay in memory.
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><SharingApp/></React.StrictMode>)
