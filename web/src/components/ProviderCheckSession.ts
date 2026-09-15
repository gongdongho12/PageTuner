import { ApiError } from '../lib/errors';
import type { ProviderCheckInput, ProviderCheckResult } from '../lib/providerCheck';
import { ProviderCheckError } from '../lib/providerCheck';

export type ProviderCheckHandler = (input: ProviderCheckInput, signal?: AbortSignal) => Promise<ProviderCheckResult>;
export type ProviderCheckState = {
  busy: boolean;
  result: ProviderCheckResult | null;
  failure: 'authentication' | 'connection' | null;
  failureCode: string | null;
};
const empty = (): ProviderCheckState => ({ busy: false, result: null, failure: null, failureCode: null });

/** The check always uses the server's fixed sample, never a chapter or reading selection. */
export function providerCheckInput(input: ProviderCheckInput): ProviderCheckInput {
  return {
    providerKind: input.providerKind,
    sourceLanguage: 'auto',
    targetLanguage: input.targetLanguage?.trim() || 'ko',
    ...(input.apiKey?.trim() ? { apiKey: input.apiKey.trim() } : {}),
    ...(input.endpoint?.trim() ? { endpoint: input.endpoint.trim() } : {}),
    ...(input.model?.trim() ? { model: input.model.trim() } : {}),
  };
}
function same(a: ProviderCheckInput | null, b: ProviderCheckInput): boolean {
  return !!a && a.providerKind === b.providerKind && a.sourceLanguage === b.sourceLanguage && a.targetLanguage === b.targetLanguage &&
    a.apiKey === b.apiKey && a.endpoint === b.endpoint && a.model === b.model;
}

/** One in-memory check belonging to one mounted settings panel and one authenticated client. */
export class ProviderCheckSession {
  private input: ProviderCheckInput | null = null;
  private controller: AbortController | null = null;
  private version = 0;
  private state = empty();
  private listeners = new Set<() => void>();
  constructor(private readonly check?: ProviderCheckHandler) {}

  snapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  matches(input: ProviderCheckInput) { return same(this.input, providerCheckInput(input)); }
  configure(input: ProviderCheckInput) {
    const next = providerCheckInput(input);
    if (same(this.input, next)) return;
    this.cancel();
    this.input = next;
  }
  cancel() {
    this.version++;
    this.controller?.abort();
    this.controller = null;
    this.input = null;
    this.publish(empty());
  }
  async run(input: ProviderCheckInput): Promise<void> {
    this.configure(input);
    if (!this.check || this.state.busy) return;
    const version = ++this.version;
    const controller = new AbortController();
    this.controller = controller;
    this.publish({ busy: true, result: null, failure: null, failureCode: null });
    try {
      const result = await this.check(providerCheckInput(input), controller.signal);
      if (version !== this.version || controller.signal.aborted) return;
      this.publish({ busy: false, result, failure: null, failureCode: null });
    } catch (error) {
      if (version !== this.version || controller.signal.aborted) return;
      // Arbitrary thrown/provider messages can include request data. Render bounded, known UI copy.
      this.publish({ busy: false, result: null,
        failure: error instanceof ApiError && error.kind === 'authentication' ? 'authentication' : 'connection',
        failureCode: error instanceof ProviderCheckError ? error.code : null });
    } finally {
      if (this.controller === controller) this.controller = null;
    }
  }
  private publish(state: ProviderCheckState) { this.state = state; this.listeners.forEach(listener => listener()); }
}
