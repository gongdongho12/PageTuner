import { validAnchor, type ReadingDocument } from "./readingDocument";
import type { ReadingAnchor } from "./offline";
const key = (username: string, document: ReadingDocument) =>
  `pageturner.workflow-position:${encodeURIComponent(username)}:${encodeURIComponent(document.id)}`;
export function getWorkflowPosition(
  username: string,
  document: ReadingDocument,
): ReadingAnchor | undefined {
  try {
    const value = JSON.parse(
      localStorage.getItem(key(username, document)) ?? "null",
    );
    if (value && validAnchor(document, value)) return value;
  } catch {
    /* Optional device progress never prevents reading. */
  }
}
export function setWorkflowPosition(
  username: string,
  document: ReadingDocument,
  anchor: ReadingAnchor,
) {
  if (!validAnchor(document, anchor)) return;
  localStorage.setItem(key(username, document), JSON.stringify(anchor));
}
