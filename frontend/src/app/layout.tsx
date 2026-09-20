import type { Metadata } from 'next';
import './globals.css';
import { OfflineShell } from '@/components/OfflineShell';
export const metadata: Metadata = { title: 'PageTurner — Web Reader', description: 'A local-first reader with translation, annotations and portable backups.' };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}<OfflineShell /></body></html>;
}
